package cz.medsy.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The strength box takes what the box in the hand says. The scanner and the editor used to parse it
 * with two slightly different regexes, and the one that won stored "1,5 mg" as 1 — a 33 % overdose
 * in everything downstream (trough estimates, dose totals, refill projections).
 */
class MedicationStrengthTest {

    @Test
    fun `a decimal comma is a decimal point`() {
        assertEquals(BigDecimal("1.5"), MedicationStrength.valueOf("1,5 mg"))
        assertEquals(BigDecimal("0.5"), MedicationStrength.valueOf("0,5"))
    }

    @Test
    fun `the unit is read from the same text`() {
        assertEquals("mg", MedicationStrength.unitOf("2 mg"))
        assertEquals("µg", MedicationStrength.unitOf("100 µg"))
        assertEquals("IU", MedicationStrength.unitOf("5000 IU"))
        assertEquals("mg/mL", MedicationStrength.unitOf("50 mg/mL"))
    }

    @Test
    fun `text without a number has no value`() {
        assertNull(MedicationStrength.valueOf("mg"))
        assertNull(MedicationStrength.valueOf("  "))
        assertNull(MedicationStrength.valueOf(null))
    }

    @Test
    fun `formatting drops trailing zeros and keeps the unit`() {
        assertEquals("50 mg/mL", MedicationStrength.format(BigDecimal("50.00"), "mg/mL"))
        assertEquals("2.5 mg", MedicationStrength.format(BigDecimal("2.5"), "mg"))
        assertEquals("2", MedicationStrength.format(BigDecimal("2"), null))
        assertEquals("", MedicationStrength.format(null, "mg"))
    }

    @Test
    fun `HRT is G03 and G02, not corticosteroids`() {
        assertTrue(MedicationStrength.isHrtAtc("G03CA03"))
        assertTrue(MedicationStrength.isHrtAtc("G02BA03"))
        assertFalse("H02 is a corticosteroid, not HRT", MedicationStrength.isHrtAtc("H02AB07"))
        assertFalse(MedicationStrength.isHrtAtc(null))
    }
}
