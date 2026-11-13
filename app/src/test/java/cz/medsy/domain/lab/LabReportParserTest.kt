package cz.medsy.domain.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class LabReportParserTest {

    private val synlabSample = """
        SYNLAB Czech s.r.o.
        Pacient: Novák Jan
        Odběr: 11.11.2026 07:40

        Estradiol                    610    pmol/L    367 - 734
        Testosteron celkový          1,20   nmol/L    8,64 - 29,00
        Prolaktin                    420    mIU/L     86 - 324
        SHBG                         54,3   nmol/L    10 - 57
        ALT                          0,42   µkat/L    0,17 - 0,83
        Kreatinin                    82     µmol/L    62 - 106
        Glukóza                      5,1    mmol/L    3,9 - 5,6
        Cholesterol celkový          4,8    mmol/L    2,9 - 5,0
        Hematokrit                   0,46   %         0,40 - 0,50
    """.trimIndent()

    @Test
    fun `parses the expected biomarkers from a czech lab report`() {
        val rows = LabReportParser.parse(synlabSample)
        val codes = rows.map { it.biomarkerCode }.toSet()
        assertTrue(codes.containsAll(
            setOf("E2", "TEST_TOTAL", "PRL", "SHBG", "ALT", "CREA", "GLU", "CHOL", "HCT"),
        ))
    }

    @Test
    fun `estradiol value and unit are extracted`() {
        val row = LabReportParser.parse(synlabSample).first { it.biomarkerCode == "E2" }
        assertEquals(0, row.rawValue.compareTo(BigDecimal("610")))
        assertEquals("pmol/l", row.rawUnit)
    }

    @Test
    fun `decimal comma is converted to a decimal point`() {
        val row = LabReportParser.parse(synlabSample).first { it.biomarkerCode == "TEST_TOTAL" }
        assertEquals(0, row.rawValue.compareTo(BigDecimal("1.20")))
    }

    @Test
    fun `printed lab range is captured`() {
        val row = LabReportParser.parse(synlabSample).first { it.biomarkerCode == "E2" }
        assertEquals(0, row.labMin!!.compareTo(BigDecimal("367")))
        assertEquals(0, row.labMax!!.compareTo(BigDecimal("734")))
    }

    @Test
    fun `diacritics do not break matching`() {
        val text = "Glukóza 5,1 mmol/L 3,9 - 5,6"
        val rows = LabReportParser.parse(text)
        assertEquals("GLU", rows.first().biomarkerCode)
    }

    @Test
    fun `unknown british spelling still matches testosterone`() {
        val rows = LabReportParser.parse("Testosterone 520 ng/dL 300 - 1000")
        assertEquals("TEST_TOTAL", rows.first().biomarkerCode)
    }

    @Test
    fun `duplicate biomarker lines keep only the first result`() {
        val text = """
            Estradiol 610 pmol/L 367 - 734
            Estradiol 700 pmol/L 367 - 734
        """.trimIndent()
        assertEquals(1, LabReportParser.parse(text).size)
    }

    @Test
    fun `lines without a unit are ignored`() {
        val text = "Estradiol 610"
        assertTrue(LabReportParser.parse(text).isEmpty())
    }

    @Test
    fun `range extraction ignores single numbers`() {
        val (min, max) = LabReportParser.extractLabRange("Estradiol 610 pmol/L")
        assertNull(min)
        assertNull(max)
    }

    @Test
    fun `range extraction handles en dash`() {
        val (min, max) = LabReportParser.extractLabRange("ALT 0,42 µkat/L 0,17–0,83")
        assertEquals(0, min!!.compareTo(BigDecimal("0.17")))
        assertEquals(0, max!!.compareTo(BigDecimal("0.83")))
    }

    @Test
    fun `recognized check reflects whether anything matched`() {
        assertTrue(LabReportParser.isRecognized(synlabSample))
        assertFalse(LabReportParser.isRecognized("Hello, this is not a lab report."))
    }

    @Test
    fun `normalize strips diacritics and collapses whitespace`() {
        assertEquals("testosteron celkovy", LabReportParser.normalize("Testosteron   celkový"))
    }
}
