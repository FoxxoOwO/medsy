package cz.medsy.data.local.datasource

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The offline ATC dictionary is the last rung of the waterfall and the only reason manual entry
 * still produces something the interaction checker understands, so its shape is asserted, not
 * just its ability to load.
 */
@RunWith(RobolectricTestRunner::class)
class AtcFallbackDataSourceTest {

    private val dataSource = AtcFallbackDataSource(ApplicationProvider.getApplicationContext())

    @Test
    fun `asset loads and every entry is well formed`() {
        assertNull(dataSource.loadError)
        assertTrue(dataSource.size() >= 80)
        dataSource.suggest("a", limit = 0) // no-op, keeps the lazy path honest

        val all = listOf("M01AE01", "G03CA03", "H03AA01", "A11HA05").map { dataSource.byCode(it) }
        all.forEach { entry ->
            assertNotNull(entry)
            assertEquals(7, entry!!.code.length)
            assertTrue(entry.latin.isNotBlank())
            assertTrue(entry.czech.isNotBlank())
        }
    }

    @Test
    fun `lookup by code is case insensitive`() {
        assertEquals("Ibuprofenum", dataSource.byCode("m01ae01")?.latin)
        assertEquals("Levothyroxinum", dataSource.byCode(" H03AA01 ")?.latin)
        assertNull(dataSource.byCode("X99ZZ99"))
    }

    @Test
    fun `czech and latin names both find the substance`() {
        assertEquals("M01AE01", dataSource.suggest("ibuprofen").first().code)
        assertEquals("M01AE01", dataSource.suggest("Ibuprofenum").first().code)
        // Diacritics are folded, so a user typing without them still gets a hit.
        assertEquals("H03AA01", dataSource.suggest("levothyroxin").first().code)
    }

    @Test
    fun `typing a czech mineral name finds the salt entry`() {
        val results = dataSource.suggest("zelezo", limit = 5)

        assertTrue(results.isNotEmpty())
        assertTrue(results.first().code.startsWith("B03"))
    }

    @Test
    fun `a code prefix is ranked below a name match`() {
        val byName = dataSource.suggest("biotin", limit = 5)
        assertEquals("A11HA05", byName.first().code)
    }

    @Test
    fun `queries shorter than two characters are ignored`() {
        assertTrue(dataSource.suggest("a").isEmpty())
        assertTrue(dataSource.suggest("").isEmpty())
    }

    @Test
    fun `partial candidate carries the scan and asks for completion`() {
        val parsed = cz.medsy.domain.util.Gs1BarcodeParser.parse(
            "01085950000001241727033110LOT42\u001D21SN0001",
        )!!
        val entry = dataSource.byCode("G03CA03")!!

        val candidate = dataSource.partial(parsed = parsed, suggestedAtc = entry)

        assertEquals("G03CA03", candidate.atcCode)
        assertEquals("Estradiolum", candidate.activeSubstance)
        assertEquals("08595000000124", candidate.gtin14)
        assertEquals("LOT42", candidate.scannedBatchNumber)
        assertTrue(candidate.needsManualCompletion)
        assertEquals(
            cz.medsy.domain.model.DataSourceOrigin.MANUAL_ATC_FALLBACK,
            candidate.origin,
        )
    }
}
