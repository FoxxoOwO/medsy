package cz.medsy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every label exists in both languages. A key added only to `values/strings.xml` silently
 * falls back to English on a Czech phone, which is exactly what this test catches.
 */
class StringsParityTest {

    private fun stringsFile(dir: String) = listOf(
        File("src/main/res/$dir/strings.xml"),
        File("app/src/main/res/$dir/strings.xml"),
    ).first { it.exists() }

    private fun keys(path: File): Set<String> {
        val text = path.readText()
        return Regex("<string name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every English key has a Czech translation`() {
        val english = keys(stringsFile("values"))
        val czech = keys(stringsFile("values-cs"))

        assertEquals(
            "missing from values-cs: ${(english - czech).sorted()}",
            english.sorted(),
            czech.sorted(),
        )
    }

    @Test
    fun `extracted labels are real keys, not placeholders`() {
        val text = stringsFile("values").readText()

        assertTrue(text.contains("<string name=\"common_cancel\""))
        assertTrue(text.contains("<string name=\"dashboard_log_dose\""))
        assertTrue(text.contains("<string name=\"symptoms_patterns\""))
    }

    private fun plurals(path: File): Map<String, Map<String, String>> {
        val pattern = Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
        val item = Regex("<item quantity=\"([^\"]+)\">([^<]*)</item>")
        return pattern.findAll(path.readText()).associate { block ->
            block.groupValues[1] to item.findAll(block.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2] }
        }
    }

    @Test
    fun `every plural is translated with the same placeholders`() {
        val english = plurals(stringsFile("values"))
        val czech = plurals(stringsFile("values-cs"))
        val placeholder = Regex("%[0-9]+\\$[sd]")

        assertEquals(
            "missing plurals in values-cs: ${(english.keys - czech.keys).sorted()}",
            english.keys.sorted(),
            czech.keys.sorted(),
        )
        val mismatched = english.filter { (key, forms) ->
            val expected = placeholder.findAll(forms.getValue("other")).map { it.value }.toSet()
            czech[key]?.any { (_, value) ->
                placeholder.findAll(value).map { it.value }.toSet() != expected
            } ?: true
        }.keys

        assertTrue("plural placeholder mismatch: $mismatched", mismatched.isEmpty())
    }

    @Test
    fun `every plural has the other form that Android falls back to`() {
        val english = plurals(stringsFile("values"))
        val czech = plurals(stringsFile("values-cs"))
        val missing = (english.keys + czech.keys)
            .filter { english[it]?.containsKey("other") != true || czech[it]?.containsKey("other") != true }

        assertTrue("plurals without an other form: $missing", missing.isEmpty())
    }

    @Test
    fun `formatted strings keep their placeholders in both languages`() {
        val pattern = Regex("(<string name=\"([^\"]+)\"[^>]*>)([^<]*)</string>")
        val english = pattern.findAll(stringsFile("values").readText())
            .associate { it.groupValues[2] to it.groupValues[3] }
        val czech = pattern.findAll(stringsFile("values-cs").readText())
            .associate { it.groupValues[2] to it.groupValues[3] }
        val placeholder = Regex("%[0-9]+\\$[sd]")

        val mismatched = english.filter { (key, value) ->
            val en = placeholder.findAll(value).map { it.value }.toSet()
            val cs = czech[key]?.let { placeholder.findAll(it).map { m -> m.value }.toSet() }
            en.isNotEmpty() && en != cs
        }.keys

        assertTrue("placeholder mismatch: $mismatched", mismatched.isEmpty())
    }
}
