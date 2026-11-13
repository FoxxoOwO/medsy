package cz.medsy.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class UnitConverterTest {

    @Test
    fun `estradiol pg per ml converts to pmol per l`() {
        val result = UnitConverter.conventionalToSi(Biomarkers.E2, BigDecimal("100"))
        // 1 pg/mL = 3.671 pmol/L -> 100 pg/mL = 367.1 pmol/L
        assertEquals(BigDecimal("367"), result.round(0))
    }

    @Test
    fun `estradiol round trips through conventional units`() {
        val si = UnitConverter.conventionalToSi(Biomarkers.E2, BigDecimal("200"))
        val back = UnitConverter.siToConventional(Biomarkers.E2, si)
        assertEquals(0, back.compareTo(BigDecimal("200")))
    }

    @Test
    fun `testosterone ng per dl converts to nmol per l`() {
        val result = UnitConverter.conventionalToSi(Biomarkers.TEST_TOTAL, BigDecimal("50"))
        // 50 ng/dL = 1.735 nmol/L
        assertEquals(0, result.round(2).compareTo(BigDecimal("1.74")))
    }

    @Test
    fun `masculinizing target lower bound is 10_4 nmol per l`() {
        val ngDl = UnitConverter.siToConventional(Biomarkers.TEST_TOTAL, BigDecimal("10.4"))
        assertEquals(0, ngDl.round(0).compareTo(BigDecimal("300")))
    }

    @Test
    fun `masculinizing target upper bound is 1000 ng per dl`() {
        val ngDl = UnitConverter.siToConventional(Biomarkers.TEST_TOTAL, BigDecimal("34.7"))
        assertEquals(0, ngDl.round(0).compareTo(BigDecimal("1000")))
    }

    @Test
    fun `prolactin ng per ml converts using 21_2 factor`() {
        val result = UnitConverter.conventionalToSi(Biomarkers.PRL, BigDecimal("20"))
        assertEquals(0, result.round(0).compareTo(BigDecimal("424")))
    }

    @Test
    fun `same unit returns value unchanged`() {
        val result = UnitConverter.convert(Biomarkers.K, BigDecimal("4.5"), "mmol/L")
        assertEquals(0, result.compareTo(BigDecimal("4.5")))
    }

    @Test
    fun `unit matching is case insensitive`() {
        val result = UnitConverter.convert(Biomarkers.E2, BigDecimal("100"), "PG/ML")
        assertEquals(0, result.round(0).compareTo(BigDecimal("367")))
    }

    @Test
    fun `comma decimal separator is handled by callers before conversion`() {
        val raw = "100,5".replace(',', '.').toBigDecimal()
        val result = UnitConverter.conventionalToSi(Biomarkers.E2, raw)
        assertTrue(result.toDouble() > 368.0)
    }

    @Test
    fun `unknown unit throws`() {
        var threw = false
        try {
            UnitConverter.convert(Biomarkers.E2, BigDecimal("1"), "furlongs")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `molar factor for estradiol matches known conversion`() {
        // 1 pg/mL of estradiol (MW 272.38) = 3.671 pmol/L
        val factor = molarFactor(BigDecimal("272.38"))
        assertEquals(0, factor.round(3).compareTo(BigDecimal("3.671")))
    }

    @Test
    fun `biomarker lookup is case insensitive`() {
        assertNotNull(Biomarkers.find("e2"))
        assertNull(Biomarkers.find("nope"))
    }

    @Test
    fun `every catalogue biomarker has a positive or unit conversion factor`() {
        Biomarkers.all.forEach { spec ->
            val factor = spec.conventionalToSiFactor
            assertNotNull("${spec.code} has null factor", factor)
            assertTrue("${spec.code} has non-positive factor", factor!!.signum() > 0)
        }
    }
}

private fun String.toBigDecimal(): BigDecimal = BigDecimal(this)
