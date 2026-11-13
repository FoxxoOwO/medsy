package cz.medsy.data.remote

import cz.medsy.data.remote.datasource.SupplementDetectors
import cz.medsy.domain.model.MineralTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed real payloads: the Czech and English name fields, the `nutriments`
 * slugs that only exist when the producer filled the nutrition table, and an ingredients list
 * in both languages.
 */
class OpenFoodFactsTest {

    private val api = OpenFoodFactsApi()

    @Test
    fun `product with czech name prefers it and detects biotin`() {
        val body = """
            {"status":1,"product":{
              "code":"8594001234567",
              "product_name":"Biotin 5 mg",
              "product_name_cs":"Biotin 5 mg tablety",
              "brands":"NatureVia",
              "quantity":"90 tablet",
              "ingredients_text_cs":"plnidlo: celulóza, D-biotin",
              "categories_tags":["en:dietary-supplements"],
              "nutriments":{"biotin":0.005,"vitamin-b7":0.005}
            }}
        """.trimIndent()

        val product = api.parse(body, "8594001234567")!!

        assertEquals("Biotin 5 mg tablety", product.bestName)
        assertEquals("NatureVia", product.brands)
        assertTrue(SupplementDetectors.hasBiotin(listOf(product.nameCs, product.ingredientsCs)))
        assertTrue(SupplementDetectors.hasBiotinNutriment(product.nutrientKeys))
    }

    @Test
    fun `english fallback name is used when czech is missing`() {
        val body = """
            {"status":1,"product":{"code":"5012345678900","product_name":"Magnesium 400","brands":"Biovea"}}
        """.trimIndent()

        val product = api.parse(body, "5012345678900")!!

        assertEquals("Magnesium 400", product.bestName)
        assertEquals(setOf(MineralTag.MAGNESIUM), SupplementDetectors.minerals(listOf(product.bestName)))
    }

    @Test
    fun `status zero means the barcode is unknown`() {
        assertNull(api.parse("""{"status":0,"status_verbose":"product not found"}""", "0000000000000"))
        assertNull(api.parse("""{"status":1}""", "0000000000000"))
        assertNull(api.parse("not json", "0000000000000"))
    }

    @Test
    fun `czech diacritics fold so vapnik and vápník hit the same tag`() {
        val minerals = SupplementDetectors.minerals(
            listOf("Složení: uhličitan vápenatý, oxid hořečnatý, železo, zinek"),
        )

        assertEquals(
            setOf(MineralTag.CALCIUM, MineralTag.MAGNESIUM, MineralTag.IRON, MineralTag.ZINC),
            minerals,
        )
        assertEquals(
            setOf(MineralTag.CALCIUM, MineralTag.ZINC),
            SupplementDetectors.minerals(listOf("calcium + zinc")),
        )
    }

    @Test
    fun `plain vitamin c is not mistaken for an interacting mineral`() {
        val text = "Vitamin C 1000 mg, kyselina askorbová, celulóza"

        assertTrue(SupplementDetectors.minerals(listOf(text)).isEmpty())
        assertFalse(SupplementDetectors.hasBiotin(listOf(text)))
    }

    @Test
    fun `biotin is matched in every spelling the packaging uses`() {
        listOf("Biotin", "D-biotin", "vitamín B7", "Vitamin H", "vitamin b 7").forEach { text ->
            assertTrue("not detected: $text", SupplementDetectors.hasBiotin(listOf(text)))
        }
        // "Biotinylated" describes a lab reagent, not an ingredient; the word boundary keeps it out.
        assertFalse(SupplementDetectors.hasBiotin(listOf("biotinylated streptavidin")))
    }

    @Test
    fun `washout window matches the published assay interference`() {
        assertEquals(72, SupplementDetectors.BIOTIN_WASHOUT_HOURS)
    }
}
