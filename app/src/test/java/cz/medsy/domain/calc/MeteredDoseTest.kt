package cz.medsy.domain.calc

import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * A two-pump dose from a 60-pump bottle is 30 doses, not 60. Deducting one unit per dose
 * doubles the apparent supply and low-stock alerts never fire in time.
 */
class MeteredDoseTest {

    private fun schedule(pumps: Int? = 2, formUnit: String = "dávka") = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = ScheduleType.FIXED_DAILY,
        timesOfDay = listOf(LocalTime.of(8, 0)),
        doseAmount = BigDecimal("1.5"),
        doseUnit = formUnit,
        startDate = LocalDate.of(2026, 1, 1),
        actuationsPerDose = pumps,
    )

    private fun batch(
        remaining: String = "60",
        total: String = "60",
        unit: String = "dávka",
    ) = InventoryBatch(
        id = 1,
        medicationId = 1,
        initialQuantity = BigDecimal(total),
        remainingQuantity = BigDecimal(remaining),
        unit = unit,
    )

    // --- which forms -----------------------------------------------------------

    @Test
    fun `gels and sprays are metered dose forms`() {
        assertTrue(MeteredDose.isMeteredDose(MedicationForm.GEL))
        assertTrue(MeteredDose.isMeteredDose(MedicationForm.SPRAY))
    }

    @Test
    fun `tablets and injections are not metered dose forms`() {
        assertFalse(MeteredDose.isMeteredDose(MedicationForm.TABLET))
        assertFalse(MeteredDose.isMeteredDose(MedicationForm.INJECTION_IM))
    }

    // --- pumps per dose --------------------------------------------------------

    @Test
    fun `the configured pump count is used`() {
        assertEquals(2, MeteredDose.pumpsPerDose(schedule(pumps = 2)))
    }

    @Test
    fun `an unset pump count defaults to one`() {
        assertEquals(1, MeteredDose.pumpsPerDose(schedule(pumps = null)))
    }

    @Test
    fun `a zero or negative pump count falls back to one`() {
        assertEquals(1, MeteredDose.pumpsPerDose(schedule(pumps = 0)))
        assertEquals(1, MeteredDose.pumpsPerDose(schedule(pumps = -3)))
    }

    @Test
    fun `the deduction matches the pump count`() {
        assertEquals(0, MeteredDose.deductForDose(schedule(pumps = 3)).compareTo(BigDecimal("3")))
    }

    @Test
    fun `a missing schedule still deducts one`() {
        assertEquals(0, MeteredDose.deductForDose(null).compareTo(BigDecimal.ONE))
    }

    // --- meter arithmetic ------------------------------------------------------

    @Test
    fun `a 60 pump bottle at two pumps per dose yields 30 doses`() {
        val meter = MeteredDose.meterFor(batch(remaining = "60"), schedule(pumps = 2))!!

        assertEquals(30, meter.dosesLeft)
        assertEquals(0, meter.remainingPumps.compareTo(BigDecimal("60")))
    }

    @Test
    fun `a partial dose is not counted as available`() {
        val meter = MeteredDose.meterFor(batch(remaining = "5"), schedule(pumps = 2))!!
        assertEquals("2 whole doses from 5 pumps", 2, meter.dosesLeft)
    }

    @Test
    fun `an empty bottle reports zero doses`() {
        val meter = MeteredDose.meterFor(batch(remaining = "0"), schedule(pumps = 2))!!
        assertEquals(0, meter.dosesLeft)
        assertTrue(meter.isEmpty)
    }

    @Test
    fun `the used fraction is correct`() {
        val meter = MeteredDose.meterFor(batch(total = "60", remaining = "15"), schedule())!!
        assertEquals(0.75f, meter.usedFraction, 0.001f)
    }

    @Test
    fun `a full bottle is zero used`() {
        val meter = MeteredDose.meterFor(batch(total = "60", remaining = "60"), schedule())!!
        assertEquals(0f, meter.usedFraction, 0.001f)
    }

    @Test
    fun `an overfilled reading does not produce a negative fraction`() {
        val meter = MeteredDose.meterFor(batch(total = "60", remaining = "80"), schedule())!!
        assertEquals(0f, meter.usedFraction, 0.001f)
    }

    // --- safety: do not invent numbers -----------------------------------------

    @Test
    fun `a bottle counted in millilitres is not treated as pumps`() {
        assertNull(
            "mL cannot answer how many pumps are left",
            MeteredDose.meterFor(batch(unit = "mL"), schedule()),
        )
    }

    @Test
    fun `no batch yields no meter`() {
        assertNull(MeteredDose.meterFor(null, schedule()))
    }

    @Test
    fun `a pump unit is accepted in Czech and English spellings`() {
        listOf("dávka", "davka", "pump", "pumps", "spray", "actuation", "dose").forEach { unit ->
            assertNotNull("unit '$unit' should be recognised", MeteredDose.meterFor(batch(unit = unit), schedule()))
        }
    }
}
