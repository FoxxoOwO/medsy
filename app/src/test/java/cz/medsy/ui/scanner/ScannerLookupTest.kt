package cz.medsy.ui.scanner

import cz.medsy.domain.message.MessageKey

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.vision.barcode.common.Barcode
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.datasource.AtcFallbackDataSource
import cz.medsy.data.local.datasource.HrtCuratedCatalogDataSource
import cz.medsy.data.local.datasource.SuklCatalogDataSource
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationLookupRepositoryImpl
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.data.repository.SuklRepository
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.repository.SupplementLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The screen-level contract of the wiring: a scan goes through the waterfall, and whatever the
 * waterfall learnt — register row, curated entry, supplement, or nothing at all — reaches the form
 * the user is about to save.
 */
@RunWith(RobolectricTestRunner::class)
class ScannerLookupTest {

    private lateinit var db: MedsyDatabase
    private lateinit var viewModel: ScannerViewModel
    private val supplements = FakeSupplements()

    /** A Czech FMD symbol: GTIN, expiry 2027-03-31, batch LOT42, serial SN0001. */
    private val fmd = "01085950000001241727033110LOT42\u001D21SN0001"
    /** A real EAN-13: the check digit is what the scanner validates, unlike the GTIN in DataMatrix. */
    private val ean = "8595000000126"

    @Before
    fun setUp() {
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
                supplements = supplements,
                atc = AtcFallbackDataSource(context),
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

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
                SuklMedicationEntity(
                    suklCode = "0213070",
                    gtin = "08595000000124",
                    name = "ESTROFEM 2 mg",
                    activeSubstance = "Estradiolum",
                    atcCode = "G03CA03",
                    strength = "2 mg",
                    formName = "potahovaná tableta",
                ),
                SuklMedicationEntity(
                    suklCode = "0213071",
                    gtin = "08595000000126",
                    name = "ESTROFEM 1 mg",
                    activeSubstance = "Estradiolum",
                    atcCode = "G03CA03",
                    strength = "1 mg",
                    formName = "potahovaná tableta",
                ),
            ),
        )
    }

    @Test
    fun `a scanned register box fills the form and says where it came from`() = runBlocking {
        seed()
        viewModel.onBarcode(fmd, Barcode.FORMAT_DATA_MATRIX)
        val state = await { viewModel.state.value.takeIf { it.resolved != null } }

        assertEquals(DataSourceOrigin.SUKL_CZ, state.resolved?.origin)
        assertEquals("ESTROFEM 2 mg", state.manualName)
        assertEquals("Estradiolum", state.manualSubstance)
        assertEquals("G03CA03", state.manualAtc)
        assertEquals("2 mg", state.manualStrength)
        assertEquals(MedicationForm.TABLET, state.manualForm)
        assertEquals(MessageKey.SCAN_MATCHED_SUKL, state.statusMessage?.key)
        // The symbol's own metadata survives into the batch the save button writes.
        assertEquals("LOT42", state.scanned?.batchNumber)
        assertEquals(LocalDate.of(2027, 3, 31), state.scanned?.expirationDate)
    }

    @Test
    fun `an ean of the same product resolves too`() = runBlocking {
        seed()
        viewModel.onBarcode(ean, Barcode.FORMAT_EAN_13)
        val state = await { viewModel.state.value.takeIf { it.resolved != null } }

        assertEquals(DataSourceOrigin.SUKL_CZ, state.resolved?.origin)
        assertEquals("0213071", state.resolved?.suklCode)
    }

    @Test
    fun `a supplement is labelled as a supplement, not as a registered medicine`() = runBlocking {
        supplements.result = ResolvedMedicationCandidate(
            origin = DataSourceOrigin.OPEN_FOOD_FACTS,
            name = "Biotin 5 mg",
            form = MedicationForm.SUPPLEMENT,
            hasBiotinInterference = true,
        )
        viewModel.onBarcode(fmd, Barcode.FORMAT_DATA_MATRIX)
        val state = await { viewModel.state.value.takeIf { it.resolved != null } }

        assertEquals(DataSourceOrigin.OPEN_FOOD_FACTS, state.resolved?.origin)
        assertEquals(MessageKey.SCAN_MATCHED_SUPPLEMENT, state.statusMessage?.key)
        assertEquals(MedicationForm.SUPPLEMENT, state.manualForm)
        assertTrue(state.resolved?.hasBiotinInterference == true)
    }

    @Test
    fun `an unknown code is kept with its batch and expiry, not thrown away`() = runBlocking {
        supplements.result = null
        viewModel.onBarcode(fmd, Barcode.FORMAT_DATA_MATRIX)
        val state = await { viewModel.state.value.takeIf { it.resolved != null } }

        assertEquals(MessageKey.SCAN_PARTIAL_UNKNOWN, state.statusMessage?.key)
        assertEquals(DataSourceOrigin.MANUAL_ATC_FALLBACK, state.resolved?.origin)
        assertEquals("08595000000124", state.resolved?.gtin14)
        assertEquals("LOT42", state.resolved?.scannedBatchNumber)
        assertEquals(LocalDate.of(2027, 3, 31), state.resolved?.scannedExpirationDate)
        assertTrue(state.resolved?.needsManualCompletion == true)
    }

    @Test
    fun `a qr that is not a medicine keeps the previous state and reports it`() = runBlocking {
        viewModel.onBarcode("https://example.test/promo", Barcode.FORMAT_QR_CODE)
        val state = await { viewModel.state.value.takeIf { it.statusMessage?.key != null } }

        assertEquals(MessageKey.SCAN_NOT_GS1, state.statusMessage?.key)
        assertNull(state.resolved)
    }

    @Test
    fun `typing searches the waterfall and offers atc codes only as a last resort`() = runBlocking {
        seed()
        viewModel.searchMedications("Estrofem")
        val catalogueHits = await { viewModel.state.value.matches.takeIf { it.isNotEmpty() } }
        assertEquals(
            listOf("0213070", "0213071"),
            catalogueHits.mapNotNull { it.suklCode }.sorted(),
        )
        assertTrue(viewModel.state.value.atcSuggestions.isEmpty())

        viewModel.searchMedications("ibu")
        val atcHits = await { viewModel.state.value.atcSuggestions.takeIf { it.isNotEmpty() } }
        assertTrue(atcHits.any { it.code == "M01AE01" })
    }

    @Test
    fun `picking an atc code links the substance so interactions still work`() = runBlocking {
        viewModel.onBarcode(fmd, Barcode.FORMAT_DATA_MATRIX)
        await { viewModel.state.value.statusMessage }

        viewModel.pickAtc(AtcSuggestion(code = "G03CA03", name = "Estradiol"))
        val state = await { viewModel.state.value.takeIf { it.manualAtc == "G03CA03" } }

        assertEquals("G03CA03", state.manualAtc)
        assertEquals("Estradiolum", state.manualSubstance)
        assertEquals("08595000000124", state.resolved?.gtin14)
        assertTrue(viewModel.state.value.atcSuggestions.isEmpty())
    }

    @Test
    fun `a one character query does not touch the waterfall`() = runBlocking {
        viewModel.searchMedications("e")
        Thread.sleep(150)
        assertTrue(viewModel.state.value.matches.isEmpty())
        assertTrue(viewModel.state.value.atcSuggestions.isEmpty())
    }

    @Test
    fun `saving a manually completed package writes the lot and expiry into stock`() = runBlocking {
        supplements.result = null
        viewModel.onBarcode(fmd, Barcode.FORMAT_DATA_MATRIX)
        await { viewModel.state.value.resolved }

        viewModel.updateManualName("Estradiol besins injekce")
        viewModel.save()
        val savedId = await { viewModel.state.value.savedMedicationId }

        val medication = requireNotNull(db.medicationDao().getById(savedId))
        assertEquals("08595000000124", medication.gtin)
        assertEquals("Estradiol besins injekce", medication.name)

        val batches = db.inventoryDao().observeAll().first()
        assertEquals(1, batches.size)
        assertEquals("LOT42", batches.single().batchNumber)
        assertEquals(LocalDate.of(2027, 3, 31).toEpochDay(), batches.single().expirationDateEpochDay)
        assertEquals(savedId, batches.single().medicationId)
    }

    @Test
    fun `a second pick does not inherit strength from the first one`() = runBlocking {
        seed()
        viewModel.searchMedications("Estrofem")
        val catalogueHit = await {
            viewModel.state.value.matches.firstOrNull { it.suklCode == "0213070" }
        }
        viewModel.pickMatch(catalogueHit)
        assertEquals("2 mg", viewModel.state.value.manualStrength)

        viewModel.pickAtc(AtcSuggestion(code = "B03AA02", name = "Železo"))
        val state = await { viewModel.state.value.takeIf { it.manualAtc == "B03AA02" } }

        assertEquals("", state.manualStrength)
        assertEquals("", state.manualConcentration)
    }

    private class FakeSupplements : SupplementLookup {
        var result: ResolvedMedicationCandidate? = null

        override suspend fun lookup(parsed: ParsedBarcode): ResolvedMedicationCandidate? = result
    }
}
