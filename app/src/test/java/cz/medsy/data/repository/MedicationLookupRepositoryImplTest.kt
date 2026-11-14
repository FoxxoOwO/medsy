package cz.medsy.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.local.MedsyDatabase
import cz.medsy.data.local.datasource.AtcFallbackDataSource
import cz.medsy.data.local.datasource.HrtCuratedCatalogDataSource
import cz.medsy.data.local.datasource.SuklCatalogDataSource
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.repository.SupplementLookup
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the waterfall end to end against a real Room database and the real assets, with only
 * the network stage faked. Each test pins one rung of the ladder, because the whole design rests
 * on stage order: a product must never be answered by a later, less authoritative source when an
 * earlier one knows it.
 */
@RunWith(RobolectricTestRunner::class)
class MedicationLookupRepositoryImplTest {

    private lateinit var db: MedsyDatabase
    private lateinit var repository: MedicationLookupRepositoryImpl
    private val supplements = FakeSupplementLookup()

    /** The GTIN the SÚKL row carries, and its FMD payload as printed on a Czech box. */
    private val suklGtin14 = "08595000000124"
    private val fmdPayload = "01085950000001241727033110LOT42\u001D21SN0001"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MedsyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MedicationLookupRepositoryImpl(
            sukl = SuklCatalogDataSource(db.suklDao()),
            hrt = HrtCuratedCatalogDataSource(context),
            supplements = supplements,
            atc = AtcFallbackDataSource(context),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertEstrofem() {
        db.suklDao().upsertAll(
            listOf(
                SuklMedicationEntity(
                    suklCode = "0012345",
                    gtin = suklGtin14,
                    name = "ESTROFEM 2 mg",
                    activeSubstance = "Estradiolum",
                    atcCode = "G03CA03",
                    strength = "2 mg",
                    formName = "potahovaná tableta",
                    holder = "Novo Nordisk",
                ),
            ),
        )
    }

    @Test
    fun `stage one answers a czech box and keeps the scanned batch and expiry`() = runTest {
        insertEstrofem()

        val candidate = repository.resolveByBarcode(fmdPayload).getOrThrow()

        assertEquals(DataSourceOrigin.SUKL_CZ, candidate.origin)
        assertEquals("0012345", candidate.suklCode)
        assertEquals("08595000000124", candidate.gtin14)
        assertEquals("8595000000124", candidate.ean13)
        assertEquals("LOT42", candidate.scannedBatchNumber)
        assertEquals(LocalDate.of(2027, 3, 31), candidate.scannedExpirationDate)
        assertEquals(MedicationForm.TABLET, candidate.form)
        assertTrue(
            "the leaflet link should point at the SÚKL medicine page",
            candidate.pilPdfUrl?.endsWith("/leciva/0012345") == true,
        )
        assertEquals(candidate.pilPdfUrl, candidate.spcPdfUrl)
        // Enriched from the curated catalogue: the register knows no half-life.
        assertNotNull(candidate.halfLifeHours)
        assertEquals(false, supplements.called)
    }

    @Test
    fun `multipack barcode still finds the single box in sukl`() = runTest {
        insertEstrofem()

        val candidate = repository.resolveByBarcode("18595000000124").getOrThrow()

        assertEquals(DataSourceOrigin.SUKL_CZ, candidate.origin)
        assertEquals("0012345", candidate.suklCode)
    }

    @Test
    fun `stage three answers a supplement the register does not know`() = runTest {
        supplements.result = supplementCandidate("08594000009997")

        val candidate = repository.resolveByBarcode("8594000009997").getOrThrow()

        assertEquals(DataSourceOrigin.OPEN_FOOD_FACTS, candidate.origin)
        assertTrue(supplements.called)
    }

    @Test
    fun `unknown barcode falls back to manual entry with the scan pre-filled`() = runTest {
        val candidate = repository.resolveByBarcode(fmdPayload).getOrThrow()

        assertEquals(DataSourceOrigin.MANUAL_ATC_FALLBACK, candidate.origin)
        assertTrue(candidate.needsManualCompletion)
        assertEquals("08595000000124", candidate.gtin14)
        assertEquals("LOT42", candidate.scannedBatchNumber)
        assertEquals(LocalDate.of(2027, 3, 31), candidate.scannedExpirationDate)
    }

    @Test
    fun `text that is not a barcode is an error, not a silent empty result`() = runTest {
        assertTrue(repository.resolveByBarcode("IBUPROFEN 400").isFailure)
        assertTrue(repository.resolveByBarcode("").isFailure)
    }

    @Test
    fun `search finds the sukl row by name and by substance`() = runTest {
        insertEstrofem()

        val byName = repository.searchByQuery("Estrofem").first()
        val bySubstance = repository.searchByQuery("Estradiol").first()

        assertEquals("0012345", byName.first().suklCode)
        assertTrue(bySubstance.any { it.suklCode == "0012345" })
    }

    @Test
    fun `search offers atc candidates when no catalogue row matches`() = runTest {
        val results = repository.searchByQuery("Ibuprofen").first()

        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.origin == DataSourceOrigin.MANUAL_ATC_FALLBACK })
        assertEquals("M01AE01", results.first { it.atcCode != null }.atcCode)
    }

    @Test
    fun `a one character query does not scan the whole catalogue`() = runTest {
        assertTrue(repository.searchByQuery("e").first().isEmpty())
    }

    @Test
    fun `manual candidate links an atc code and keeps the scanned metadata`() = runTest {
        val candidate = repository.manualCandidate(
            rawBarcode = fmdPayload,
            atcCode = "G03CA03",
            nameHint = "Estradiol inj.",
        )

        assertEquals("G03CA03", candidate.atcCode)
        assertEquals("Estradiolum", candidate.activeSubstance)
        assertEquals("Estradiol inj.", candidate.name)
        assertEquals("08595000000124", candidate.gtin14)
        assertTrue(candidate.needsManualCompletion)
    }

    @Test
    fun `atc suggestions are ranked for the manual form`() = runTest {
        val suggestions = repository.suggestAtc("estrad", limit = 5)

        assertTrue(suggestions.isNotEmpty())
        assertEquals("G03CA03", suggestions.first().code)
    }

    private fun supplementCandidate(gtin: String) = ResolvedMedicationCandidate(
        origin = DataSourceOrigin.OPEN_FOOD_FACTS,
        name = "Biotin 5 mg",
        form = MedicationForm.SUPPLEMENT,
        gtin14 = gtin,
        ean13 = ParsedBarcode.ean13Of(gtin),
        hasBiotinInterference = true,
    )

    private class FakeSupplementLookup : SupplementLookup {
        var result: ResolvedMedicationCandidate? = null
        var called = false

        override suspend fun lookup(parsed: ParsedBarcode): ResolvedMedicationCandidate? {
            called = true
            return result
        }
    }
}

private fun <T> kotlin.Result<T>.getOrThrow(): T = getOrElse { throw it }
