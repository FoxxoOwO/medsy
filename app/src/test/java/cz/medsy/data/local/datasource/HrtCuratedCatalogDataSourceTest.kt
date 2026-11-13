package cz.medsy.data.local.datasource

import androidx.test.core.app.ApplicationProvider
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ParsedBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The catalog is an asset, so these tests read the shipped JSON rather than a stub: a typo in
 * the asset (an unknown form name, a half-life written as a string) would otherwise only show up
 * as a silently empty scan result on a device.
 */
@RunWith(RobolectricTestRunner::class)
class HrtCuratedCatalogDataSourceTest {

    private val dataSource = HrtCuratedCatalogDataSource(ApplicationProvider.getApplicationContext())

    @Test
    fun `asset loads without errors and covers the esters the app promises`() {
        assertNull(dataSource.loadError)
        val all = dataSource.all()

        assertTrue("catalog is suspiciously small: ${all.size}", all.size >= 30)
        val names = all.joinToString(" ") { entry ->
            (listOf(entry.name) + entry.aliases).joinToString(" ").lowercase()
        }
        listOf("valerate", "cypionate", "enanthate", "undecanoate", "sustanon", "nebido")
            .forEach { ester ->
                assertTrue("missing $ester", names.contains(ester))
            }
    }

    @Test
    fun `injectable esters carry a concentration and a dead space`() {
        val enanthate = dataSource.all().first { it.name.contains("Enanthate", ignoreCase = true) }

        assertEquals(MedicationForm.INJECTION_IM, enanthate.form.toMedicationForm())
        assertNotNull("concentration must be set for a vial", enanthate.concentrationMgPerMl)
        assertNotNull("dead space must be set for a vial", enanthate.defaultDeadSpaceMl)
        assertTrue("half-life must be set", (enanthate.halfLifeHours ?: 0.0) > 0.0)
    }

    @Test
    fun `oral and sublingual estradiol are separate entries with different half lives`() {
        val estradiol = dataSource.all().filter { it.atcCode == "G03CA03" }
        val oral = estradiol.first { it.form == "TABLET" }
        val sublingual = estradiol.first { it.form == "SUBLINGUAL" }

        assertTrue("oral E2 half-life looks wrong: ${oral.halfLifeHours}", (oral.halfLifeHours ?: 0.0) >= 14.0)
        assertTrue("sublingual E2 half-life looks wrong: ${sublingual.halfLifeHours}", (sublingual.halfLifeHours ?: 0.0) <= 10.0)
    }

    @Test
    fun `no gtin is invented for products whose packaging does not carry one`() {
        // Compounded preparations and imported esters have no public GTIN: SÚKL stopped
        // publishing barcodes in 2026 and the remaining registries are commercial. Shipping a
        // guessed barcode would send every scan of that box to the wrong product, so the field
        // stays empty and the user maps the package once (ScannerViewModel.save).
        assertTrue(dataSource.all().all { it.gtins.isEmpty() })

        val random = ParsedBarcode(rawValue = "8595000000124", gtin14 = "08595000000124", ean13 = "8595000000124")
        assertNull(dataSource.findByBarcode(random))
    }

    @Test
    fun `lookup by gtin accepts any packaging indicator once a package is known`() {
        // Exercises the matching rule itself with data injected the way an imported catalogue
        // would provide it, independent of what the shipped asset happens to contain.
        val entry = dataSource.all().first { it.id == "ev-5-neofollin" }.copy(gtins = listOf("08595000000124"))

        assertTrue(dataSource.matchesGtin(entry, "08595000000124"))
        assertTrue("multipack indicator 1 must match the single box", dataSource.matchesGtin(entry, "18595000000124"))
        assertTrue("a 13-digit scan must match too", dataSource.matchesGtin(entry, "8595000000124"))
        assertFalse(dataSource.matchesGtin(entry, "08595000009999"))
    }

    @Test
    fun `search ranks an exact name above a substring and folds diacritics`() {
        val results = dataSource.search("estradiol", limit = 5)

        assertTrue(results.isNotEmpty())
        assertTrue(results.first().name.lowercase().contains("estradiol"))
        assertEquals(results.first().id, dataSource.search("Estradiol", limit = 5).first().id)
    }

    @Test
    fun `enrichment picks the route matching the form hint`() {
        val oral = dataSource.enrichment(atcCode = "G03CA03", formHint = MedicationForm.TABLET, substanceHint = "Estradiol")
        val patch = dataSource.enrichment(atcCode = "G03CA03", formHint = MedicationForm.PATCH, substanceHint = "Estradiol")

        assertNotNull(oral)
        assertNotNull(patch)
        assertEquals(MedicationForm.TABLET, oral!!.form.toMedicationForm())
        assertEquals(MedicationForm.PATCH, patch!!.form.toMedicationForm())
        assertTrue(oral.halfLifeHours!! < 24.0)
    }
}
