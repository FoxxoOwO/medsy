package cz.medsy.data.lab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The join rule decides whether a lab table survives OCR. ML Kit can return the analyte
 * name and its value either as one line (the usual case for Czech reports) or as two
 * blocks side by side; only newline joining keeps both cases from inventing text.
 */
class OcrLineJoinTest {

    @Test
    fun `a name and its value on one line stay on one line`() {
        val document = OcrDocument(blocks = listOf(listOf("Estradiol 610 pmol/L")))

        assertEquals("Estradiol 610 pmol/L", document.text())
    }

    @Test
    fun `separate blocks are newline joined, never space joined`() {
        val document = OcrDocument(
            blocks = listOf(
                listOf("Biochemie", "Estradiol", "Prolaktin"),
                listOf("Jednotka", "610 pmol/L", "8,4 ug/l"),
            ),
        )

        // A space join would produce "Estradiol 610 pmol/L" by accident here, and
        // "Prolaktin Jednotka" across columns. Lines are the only safe boundary.
        assertEquals(
            "Biochemie\nEstradiol\nProlaktin\nJednotka\n610 pmol/L\n8,4 ug/l",
            document.text(),
        )
    }

    @Test
    fun `blank lines are dropped and whitespace is trimmed`() {
        val document = OcrDocument(
            blocks = listOf(
                listOf("  Estradiol 610 pmol/L  ", "   ", ""),
                listOf(""),
            ),
        )

        assertEquals("Estradiol 610 pmol/L", document.text())
        assertEquals(listOf("Estradiol 610 pmol/L"), document.lines())
    }

    @Test
    fun `an empty document yields an empty string, not a blank line`() {
        assertEquals("", OcrDocument(blocks = emptyList()).text())
        assertEquals("", OcrDocument(blocks = listOf(emptyList(), emptyList())).text())
    }
}
