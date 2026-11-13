package cz.medsy.domain.calc

import cz.medsy.domain.calc.InventoryCalculator.ConsumableKind
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Consumables are what makes an injection possible away from home, and they are the one
 * part of the travel plan a user cannot improvise: a vial of testosterone is useless
 * without a syringe. The plan therefore derives them from the injections that are really
 * scheduled, not from a per-day guess.
 */
class TravelConsumablesTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 1)
    private val tripStart: LocalDate = today.plusDays(2)

    private fun injectionSchedule(
        medicationId: Long = 1,
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        type: ScheduleType = ScheduleType.FIXED_DAILY,
        startDate: LocalDate = today.minusDays(10),
    ) = Schedule(
        id = medicationId,
        medicationId = medicationId,
        scheduleType = type,
        timesOfDay = times,
        doseAmount = BigDecimal.ONE,
        doseUnit = "ml",
        startDate = startDate,
    )

    private fun plan(
        schedules: Map<Long, List<Schedule>>,
        forms: Map<Long, MedicationForm>,
        tripDays: Int,
        bufferDays: Int = 0,
        stock: Map<ConsumableKind, Int> = emptyMap(),
        names: Map<Long, String> = schedules.keys.associateWith { "Medication $it" },
        units: Map<Long, String> = schedules.keys.associateWith { "ml" },
        available: Map<Long, BigDecimal> = emptyMap(),
    ) = InventoryCalculator.travelPlan(
        schedulesByMedication = schedules,
        names = names,
        units = units,
        availableByMedication = available,
        tripDays = tripDays,
        bufferDays = bufferDays,
        tripStart = tripStart,
        formsByMedication = forms,
        consumableStock = stock,
    )

    private fun InventoryCalculator.TravelPlan.required(kind: ConsumableKind): Int =
        consumables.firstOrNull { it.kind == kind }?.required ?: 0

    @Test
    fun `ten injections need ten syringes, ten draw needles, ten injection needles and twenty swabs`() {
        val result = plan(
            schedules = mapOf(1L to listOf(injectionSchedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))))),
            forms = mapOf(1L to MedicationForm.INJECTION_SUBQ),
            tripDays = 5,
        )

        assertEquals(10, result.injectionCount)
        assertEquals(10, result.required(ConsumableKind.SYRINGE))
        assertEquals(10, result.required(ConsumableKind.DRAW_NEEDLE))
        assertEquals(10, result.required(ConsumableKind.INJECTION_NEEDLE))
        assertEquals(20, result.required(ConsumableKind.ALCOHOL_SWAB))
    }

    @Test
    fun `the safety buffer adds injections and therefore consumables`() {
        val fiveDays = plan(
            schedules = mapOf(1L to listOf(injectionSchedule())),
            forms = mapOf(1L to MedicationForm.INJECTION_IM),
            tripDays = 5,
        )
        val fiveDaysAndTwo = plan(
            schedules = mapOf(1L to listOf(injectionSchedule())),
            forms = mapOf(1L to MedicationForm.INJECTION_IM),
            tripDays = 5,
            bufferDays = 2,
        )

        assertEquals(5, fiveDays.injectionCount)
        assertEquals(7, fiveDaysAndTwo.injectionCount)
        assertEquals(14, fiveDaysAndTwo.required(ConsumableKind.ALCOHOL_SWAB))
    }

    @Test
    fun `a trip without injections lists no consumables at all`() {
        val tablet = Schedule(
            id = 1,
            medicationId = 1,
            scheduleType = ScheduleType.FIXED_DAILY,
            timesOfDay = listOf(LocalTime.of(8, 0)),
            doseAmount = BigDecimal.ONE,
            doseUnit = "tbl",
            startDate = today.minusDays(10),
        )
        val result = plan(
            schedules = mapOf(1L to listOf(tablet)),
            forms = mapOf(1L to MedicationForm.TABLET),
            tripDays = 5,
            available = mapOf(1L to BigDecimal("10")),
        )

        assertEquals(0, result.injectionCount)
        assertTrue(result.consumables.isEmpty())
        assertFalse(result.hasShortfall)
    }

    @Test
    fun `a patch is the medication, not a syringe`() {
        val patch = injectionSchedule()
        val result = plan(
            schedules = mapOf(1L to listOf(patch)),
            forms = mapOf(1L to MedicationForm.PATCH),
            tripDays = 5,
            units = mapOf(1L to "patch"),
        )

        assertEquals(0, result.injectionCount)
        assertTrue(result.consumables.isEmpty())
        // The patch still appears once, as the medication itself.
        assertEquals(0, result.items.single().required.compareTo(BigDecimal("5")))
    }

    @Test
    fun `prn injections are not counted as necessary doses`() {
        val result = plan(
            schedules = mapOf(1L to listOf(injectionSchedule(type = ScheduleType.PRN))),
            forms = mapOf(1L to MedicationForm.INJECTION_IM),
            tripDays = 5,
        )

        assertEquals(0, result.injectionCount)
        assertTrue(result.consumables.isEmpty())
    }

    @Test
    fun `injections after the trip are not counted`() {
        val result = plan(
            schedules = mapOf(1L to listOf(injectionSchedule(startDate = tripStart.plusDays(30)))),
            forms = mapOf(1L to MedicationForm.INJECTION_IM),
            tripDays = 5,
        )

        assertEquals(0, result.injectionCount)
    }

    @Test
    fun `stock below the requirement is reported as a shortfall`() {
        val result = plan(
            schedules = mapOf(1L to listOf(injectionSchedule())),
            forms = mapOf(1L to MedicationForm.INJECTION_IM),
            tripDays = 5,
            stock = mapOf(ConsumableKind.SYRINGE to 4, ConsumableKind.ALCOHOL_SWAB to 40),
        )

        val syringes = result.consumables.single { it.kind == ConsumableKind.SYRINGE }
        assertEquals(4, syringes.available)
        assertEquals(1, syringes.shortfall)
        assertTrue(result.hasShortfall)

        val swabs = result.consumables.single { it.kind == ConsumableKind.ALCOHOL_SWAB }
        assertEquals(0, swabs.shortfall)
    }

    @Test
    fun `unrecorded consumable stock is treated as none, not as plenty`() {
        val result = plan(
            schedules = mapOf(1L to listOf(injectionSchedule())),
            forms = mapOf(1L to MedicationForm.INJECTION_IM),
            tripDays = 5,
        )

        val syringes = result.consumables.single { it.kind == ConsumableKind.SYRINGE }
        assertEquals(0, syringes.available)
        assertEquals(5, syringes.shortfall)
        assertTrue(result.hasShortfall)
    }

    @Test
    fun `consumable names are classified and draw needles are not injection needles`() {
        assertEquals(ConsumableKind.SYRINGE, InventoryCalculator.consumableKind("29G Insulin Syringe"))
        assertEquals(ConsumableKind.ALCOHOL_SWAB, InventoryCalculator.consumableKind("Alcohol Swab"))
        assertEquals(ConsumableKind.DRAW_NEEDLE, InventoryCalculator.consumableKind("Draw needle 18G"))
        assertEquals(ConsumableKind.INJECTION_NEEDLE, InventoryCalculator.consumableKind("Injection needle 27G"))
        assertNull(InventoryCalculator.consumableKind("Gauze"))
    }

    @Test
    fun `labels are plural and human readable`() {
        assertEquals(MessageKey.CONSUMABLE_SYRINGES, ConsumableKind.SYRINGE.label().key)
        assertEquals(MessageKey.CONSUMABLE_ALCOHOL_SWABS, ConsumableKind.ALCOHOL_SWAB.label().key)
    }
}
