package cz.medsy.data.local.datasource

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The asset is data, so the tests do two jobs: prove the lookup order, and prove the file itself is
 * sound. A typo in a frequency name would otherwise only show up as a silently downgraded band.
 */
@RunWith(RobolectricTestRunner::class)
class SideEffectCatalogTest {

    private val catalog = SideEffectCatalog(ApplicationProvider.getApplicationContext())

    @Test
    fun `the asset loads and is not tiny`() = runBlocking {
        assertNull(catalog.loadError)
        assertTrue("only ${catalog.size()} rows", catalog.size() >= 40)
    }

    @Test
    fun `a known substance resolves exactly`() = runBlocking {
        val profile = catalog.profileFor("G03CA03")

        assertNotNull(profile)
        assertEquals("G03CA03", profile!!.atcCode)
        assertEquals("Estradiol", profile.substance)
        assertFalse(profile.isGroupLevel)
        assertTrue(profile.effects.isNotEmpty())
    }

    @Test
    fun `an unknown ester falls back to the group`() = runBlocking {
        val profile = catalog.profileFor("G03CA09")!!

        assertEquals("G03CA", profile.atcCode)
        assertTrue(profile.isGroupLevel)
    }

    @Test
    fun `a wholly unknown code has no profile`() = runBlocking {
        assertNull(catalog.profileFor("Z99ZZ99"))
        assertNull(catalog.profileFor(null))
        assertNull(catalog.profileFor(""))
        assertNull(catalog.profileFor("G"))
    }

    @Test
    fun `the code is normalised before it is matched`() = runBlocking {
        assertEquals("G03CA03", catalog.profileFor(" g03ca03 ")?.atcCode)
        assertEquals("G03CA03", catalog.profileFor("G03 CA 03")?.atcCode)
    }

    /**
     * The bands are serialised as names; a misspelt name would silently land in `UNCOMMON` because
     * of the parser's fallback, so every band in the asset is checked against the enum.
     */
    @Test
    fun `every band in the asset is a real one`() = runBlocking {
        val names = cz.medsy.domain.model.SideEffectFrequency.values().map { it.name }.toSet()
        val raw = ApplicationProvider.getApplicationContext<android.app.Application>()
            .assets.open("side_effects.json").bufferedReader().use { it.readText() }
        val bands = Regex("\"frequency\"\\s*:\\s*\"([A-Z_]+)\"").findAll(raw)
            .map { it.groupValues[1] }
            .toList()

        assertTrue(bands.isNotEmpty())
        assertEquals(emptyList<String>(), bands.filterNot { it in names })
    }

    @Test
    fun `every row is a real ATC level`() = runBlocking {
        val raw = ApplicationProvider.getApplicationContext<android.app.Application>()
            .assets.open("side_effects.json").bufferedReader().use { it.readText() }
        val codes = Regex("\"atc\"\\s*:\\s*\"([A-Z0-9]+)\"").findAll(raw)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(codes.size, codes.distinct().size)
        // ATC: level 2 = 3 chars, level 3 = 4, level 4 = 5, level 5 (substance) = 7.
        assertEquals(emptyList<String>(), codes.filterNot { it.length in setOf(3, 4, 5, 7) })
    }
}
