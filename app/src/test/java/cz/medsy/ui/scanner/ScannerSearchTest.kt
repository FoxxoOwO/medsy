package cz.medsy.ui.scanner

import cz.medsy.domain.message.MessageKey

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.datasource.AtcFallbackDataSource
import cz.medsy.data.local.datasource.HrtCuratedCatalogDataSource
import cz.medsy.data.local.datasource.SuklCatalogDataSource
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.data.repository.MedicationLookupRepositoryImpl
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.repository.SupplementLookup
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.data.repository.SuklRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * Typing a name must never be overwritten by a search result. A real SÚKL match is
 * offered as a tappable suggestion instead.
 */
@RunWith(RobolectricTestRunner::class)
class ScannerSearchTest {

    private lateinit var db: MedsyDatabase
    private lateinit var viewModel: ScannerViewModel

    @Before
    fun setUp() {
        // viewModelScope posts to Dispatchers.Main. Under Robolectric the test thread *is*
        // the main thread, so runBlocking would deadlock unless Main is redirected.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = ScannerViewModel(
            suklRepository = SuklRepository(db.suklDao()),
            medicationRepository = MedicationRepository(db.medicationDao(), db.scheduleDao(), db.milestoneDao()),
            inventoryRepository = InventoryRepository(
                db.inventoryDao(),
                db.consumableDao(),
                db.medicationDao(),
                db.scheduleDao(),
            ),
            prescriptionRepository = PrescriptionRepository(db.prescriptionDao()),
            lookupRepository = MedicationLookupRepositoryImpl(
                sukl = SuklCatalogDataSource(db.suklDao()),
                hrt = HrtCuratedCatalogDataSource(context),
                // The waterfall's network stage is the only thing faked here: every other rung
                // runs against the real database and the real shipped assets.
                supplements = object : SupplementLookup {
                    override suspend fun lookup(parsed: ParsedBarcode): ResolvedMedicationCandidate? = null
                },
                atc = AtcFallbackDataSource(context),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * The repository hops to Dispatchers.IO, which a single-threaded test cannot join,
     * so poll until the condition holds instead of asserting immediately.
     */
    private fun <T> await(timeoutMs: Long = 2_000, condition: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            condition()?.let { return it }
            Thread.sleep(20)
        }
        error("condition not met within ${timeoutMs}ms")
    }

    private suspend fun seed() {
        db.suklDao().upsertAll(
            listOf(
                SuklMedicationEntity(suklCode = "0213070", name = "Estrofem 2mg", activeSubstance = "Estradiolum"),
                SuklMedicationEntity(suklCode = "0213071", name = "Estrofem Forte", activeSubstance = "Estradiolum"),
            ),
        )
    }

    @Test
    fun `typing a name is not overwritten by a search result`() = kotlinx.coroutines.runBlocking {
        seed()
        viewModel.updateManualName("Estro")
        viewModel.searchMedications("Estro")
        await { viewModel.state.value.matches.ifEmpty { null } }

        assertEquals("Estro", viewModel.state.value.manualName)
    }

    @Test
    fun `search offers suggestions`() = kotlinx.coroutines.runBlocking {
        seed()
        // Probe the repository directly to separate "search is broken" from
        // "viewModelScope did not run".
        val direct = SuklRepository(db.suklDao()).search("Estrofem")
        assertTrue("repository search must find the seeded row: $direct", direct.isNotEmpty())

        viewModel.searchMedications("Estrofem")
        val suggestions = await { viewModel.state.value.matches.ifEmpty { null } }

        assertTrue(suggestions.isNotEmpty())
    }

    @Test
    fun `a one character query is not searched`() {
        viewModel.searchMedications("E")
        Thread.sleep(200)
        assertTrue(viewModel.state.value.matches.isEmpty())
    }

    @Test
    fun `picking a suggestion fills the form and clears the list`() = kotlinx.coroutines.runBlocking {
        seed()
        viewModel.searchMedications("Estrofem")
        val match = await { viewModel.state.value.matches.firstOrNull() }

        viewModel.pickMatch(match)

        val state = viewModel.state.value
        assertEquals("Estrofem 2mg", state.manualName)
        assertEquals("0213070", state.resolved?.suklCode)
        assertTrue(state.matches.isEmpty())
    }

    @Test
    fun `unparseable datamatrix reports an error instead of silence`() {
        viewModel.onBarcode("not-a-gs1-code", com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX)
        assertEquals(MessageKey.SCAN_NOT_GS1, viewModel.state.value.statusMessage?.key)
    }

    @Test
    fun `invalid ean13 reports a check digit error`() {
        viewModel.onBarcode("8595000000011", com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13)
        assertEquals(MessageKey.SCAN_EAN_INVALID, viewModel.state.value.statusMessage?.key)
    }
}
