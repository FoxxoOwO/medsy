package cz.medsy.ui.medications

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.datasource.SideEffectCatalog
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationRepository
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
 * stock, its side-effect profile — and to close itself only when the row is genuinely gone.
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
    private lateinit var sideEffects: SideEffectCatalog
    private var medicationId: Long = 0

    /** Collectors live outside `runBlocking`, which would otherwise wait for them forever. */
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

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
        sideEffects = SideEffectCatalog(context)
        medicationId = runBlocking {
            db.medicationDao().insert(
                MedicationEntity(
                    name = "Testovaci",
                    activeSubstance = "Estradiolum valeras",
                    atcCode = "G03CA03",
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
        sideEffectCatalog = sideEffects,
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
    fun `the side effects follow the substance`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        viewModel.state.first { it.sideEffects != null }

        val profile = state().sideEffects!!
        assertEquals("G03CA03", profile.atcCode)
        assertEquals("Estradiol", profile.substance)
        assertFalse(profile.isGroupLevel)
        assertTrue(profile.effects.isNotEmpty())
        assertTrue(profile.byFrequency().first().second.isNotEmpty())
    }

    @Test
    fun `a substance outside the asset falls back to its group`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        repository.getById(medicationId)!!.let { row ->
            viewModel.save(row, MedicationEdit.of(row).copy(atcCode = "G03CA09"))
        }
        viewModel.state.first { it.sideEffects?.atcCode == "G03CA" }

        assertTrue(state().sideEffects!!.isGroupLevel)
        assertEquals("Estrogeny", state().sideEffects!!.substance)
    }

    @Test
    fun `no code means no card`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        repository.getById(medicationId)!!.let { row ->
            viewModel.save(row, MedicationEdit.of(row).copy(atcCode = ""))
        }
        viewModel.state.first { it.medication?.atcCode == null }

        assertNull(state().sideEffects)
    }

    @Test
    fun `an unknown code does not invent effects`() = watching { viewModel ->
        val state = watch(viewModel)
        viewModel.state.first { it.medication != null }

        repository.getById(medicationId)!!.let { row ->
            viewModel.save(row, MedicationEdit.of(row).copy(atcCode = "Z99ZZ99"))
        }
        viewModel.state.first { it.medication?.atcCode == "Z99ZZ99" }
        // The profile is computed off the medication flow; give the lookup a moment and assert.
        runBlocking { kotlinx.coroutines.delay(50) }

        assertNull(state().sideEffects)
    }
}
