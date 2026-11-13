package cz.medsy.domain.lab

import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.RangeEvaluator
import cz.medsy.domain.calc.RangeStatus
import cz.medsy.domain.calc.UnitConverter
import cz.medsy.domain.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/**
 * The lab's printed range is written in the units the lab reports (pg/mL, ng/dL),
 * while every stored value is normalised to SI. Comparing one to the other is
 * meaningless, so the printed bounds must be converted too.
 */
class LabRangeNormalizationTest {

    @Test
    fun `printed estradiol range is converted to SI`() {
        // "Estradiol 610 pmol/L" with a printed range in the same unit needs no change.
        val converted = LabRangeConverter.toSi("E2", BigDecimal("367"), BigDecimal("734"), "pmol/L")
        assertEquals(0, converted.first!!.compareTo(BigDecimal("367")))
        assertEquals(0, converted.second!!.compareTo(BigDecimal("734")))
    }

    @Test
    fun `printed range in conventional units is converted`() {
        // 100-200 pg/mL for estradiol = 367-734 pmol/L
        val converted = LabRangeConverter.toSi("E2", BigDecimal("100"), BigDecimal("200"), "pg/mL")
        assertEquals(0, converted.first!!.setScale(0, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("367")))
        assertEquals(0, converted.second!!.setScale(0, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("734")))
    }

    @Test
    fun `testosterone printed range in ng per dl is converted`() {
        val converted = LabRangeConverter.toSi(
            "TEST_TOTAL",
            BigDecimal("300"),
            BigDecimal("1000"),
            "ng/dL",
        )
        assertEquals(0, converted.first!!.setScale(1, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("10.4")))
        assertEquals(0, converted.second!!.setScale(1, java.math.RoundingMode.HALF_UP).compareTo(BigDecimal("34.7")))
    }

    @Test
    fun `missing bounds stay missing`() {
        val converted = LabRangeConverter.toSi("E2", null, BigDecimal("734"), "pmol/L")
        assertNull(converted.first)
        assertEquals(0, converted.second!!.compareTo(BigDecimal("734")))
    }

    @Test
    fun `unknown biomarker passes bounds through unchanged`() {
        val converted = LabRangeConverter.toSi("UNKNOWN", BigDecimal("1"), BigDecimal("2"), "mg/L")
        assertEquals(0, converted.first!!.compareTo(BigDecimal("1")))
        assertEquals(0, converted.second!!.compareTo(BigDecimal("2")))
    }

    @Test
    fun `unrecognised unit passes bounds through rather than dropping them`() {
        val converted = LabRangeConverter.toSi("E2", BigDecimal("5"), BigDecimal("9"), "furlongs")
        assertEquals(0, converted.first!!.compareTo(BigDecimal("5")))
        assertEquals(0, converted.second!!.compareTo(BigDecimal("9")))
    }

    @Test
    fun `converted range evaluates in the right direction`() {
        val (min, max) = LabRangeConverter.toSi("E2", BigDecimal("100"), BigDecimal("200"), "pg/mL")
        val profile = cz.medsy.domain.model.ReferenceRangeProfile(
            biomarkerCode = "E2",
            profileType = ProfileType.LAB_PRINTED,
            targetMinSi = min,
            targetMaxSi = max,
        )
        // 500 pmol/L is inside the printed 100-200 pg/mL window
        assertEquals(RangeStatus.IN_RANGE, RangeEvaluator.evaluate(BigDecimal("500"), profile).status)
        // 1000 pmol/L is above it
        assertEquals(RangeStatus.HIGH, RangeEvaluator.evaluate(BigDecimal("1000"), profile).status)
    }

}
