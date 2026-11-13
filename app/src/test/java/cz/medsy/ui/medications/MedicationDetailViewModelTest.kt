package cz.medsy.ui.medications

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.remote.TranslationService
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.repository.TranslationRepository
import cz.medsy.data.repository.TranslationRepositoryImpl
import cz.medsy.domain.model.MedicationForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The detail screen's job is to keep several observations in step — the row, its schedules, its
 * stock, its cached translations — and to close itself only when the row is genuinely gone.
 *
 * These tests keep a collector alive and wait on it. `stateIn(WhileSubscribed)` stops recomputing
 * when nobody listens, so reading `state.value` between calls would return a stale snapshot, and
 * `advanceUntilIdle()` is useless here because Room answers on its own executor, not on the test
 * scheduler. Waiting therefore happens on the real thread.
 */
@RunWith(RobolectricTestRunner::class)
class MedicationDetailViewModelTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: MedicationRepository
    private lateinit var inventoryRepository: InventoryRepository
    private lateinit var service: FakeTranslationService
    private lateinit var translations: TranslationRepository
    private var medicationId: Long = 0

    /** Collectors live outside `runBlocking`, which would otherwise wait for them forever. */
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class FakeTranslationService : TranslationService {
        var fail = false
        override suspend fun translate(text: String, target: String): String? =
            if (fail) null else "[$target] $text"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao())
        inventoryRepository = InventoryRepository(
            db.inventoryDao(),
            db.consumableDao(),
            db.medicationDao(),
            db.scheduleDao(),
        )
        service = FakeTranslationService()
        translations = TranslationRepositoryImpl(db.translationDao(), service)
        medicationId = runBlocking {
            db.medicationDao().insert(
                MedicationEntity(
                    name = "Testovaci",
                    activeSubstance = "Estradiolum valeras",
                    form = MedicationForm.INJECTION_IM.name,
                    notes = "Ráno",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        watchers.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * Keeps the ViewModel's state hot and hands back the latest snapshot.
     */
    private fun watch(viewModel: MedicationDetailViewModel): () -> MedicationDetailUi {
        var latest = MedicationDetailUi()
        watchers.launch { viewModel.state.collect { latest = it } }
        return { latest }
    }

    private fun viewModel() = MedicationDetailViewModel(
        repository = repository,
        inventoryRepository = inventoryRepository,
        translations = translations,
        savedStateHandle = SavedStateHandle(mapOf("medicationId" to medicationId)),
    )

    /** A scope that outlives `first`, so the few updates the assertion needs are already delivered. */
    private fun watching(block: suspend CoroutineScope.(MedicationDetailViewModel) -> Unit) {
        runBlocking { block(viewModel()) }
    }

    @Test
    fun `the row is observed, not passed in`() = watching { viewModel ->
        val state = watch(viewModel)

        viewModel.state.first { it.medication != null }

        assertEquals("Testovaci", state().medication?.name)
        assertFalse(state().gone)
    }

    @Test
    fun `an edit is persisted`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        val loaded = state().medication!!
        viewModel.save(loaded, MedicationEdit.of(loaded).copy(name = "Testovaci 2"))
        viewModel.state.first { it.medication?.name == "Testovaci 2" }

        assertEquals("Testovaci 2", repository.getById(medicationId)?.name)
    }

    @Test
    fun `deleting the row closes the screen once it is gone`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        viewModel.delete()
        viewModel.state.first { it.gone }

        assertTrue(state().gone)
        assertNull(repository.getById(medicationId))
    }

    @Test
    fun `archiving marks the row and leaves the screen`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        viewModel.archive()
        viewModel.state.first { it.gone }

        assertTrue(state().gone)
        assertTrue(repository.getById(medicationId)!!.isArchived)
    }

    @Test
    fun `a translation is stored, shown and removable`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        viewModel.translate("en")
        viewModel.state.first { it.translations.isNotEmpty() }

        assertEquals("[en] Testovaci", state().translations.single().name)
        assertNull(state().translating)
        assertFalse(state().translationFailed)

        viewModel.removeTranslation("en")
        viewModel.state.first { it.translations.isEmpty() }
    }

    @Test
    fun `a failed translation is reported, not silently dropped`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }
        service.fail = true

        viewModel.translate("en")
        viewModel.state.first { it.translationFailed }

        assertTrue(state().translationFailed)
        assertTrue(state().translations.isEmpty())
        assertNull(state().translating)
    }
}
