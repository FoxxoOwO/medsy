package cz.medsy.domain.calc

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.TaperStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class InventoryCalculationTest {

    private val today = LocalDate.of(2026, 11, 11)

    private fun schedule(
        type: ScheduleType = ScheduleType.FIXED_DAILY,
        dose: String = "1",
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        interval: String? = null,
        taper: List<TaperStep> = emptyList(),
        active: Boolean = true,
    ) = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = type,
        intervalValue = interval?.let(::BigDecimal),
        timesOfDay = times,
        doseAmount = BigDecimal(dose),
        doseUnit = "tbl",
        isActive = active,
        startDate = today.minusDays(10),
        taperSteps = taper,
    )

    private fun batch(
        id: Long = 1,
        remaining: String = "10",
        expiry: LocalDate? = null,
        punctured: LocalDate? = null,
        budDays: Int? = null,
        unit: String = "tbl",
    ) = InventoryBatch(
        id = id,
        medicationId = 1,
        expirationDate = expiry,
        firstPuncturedDate = punctured,
        beyondUseDaysLimit = budDays,
        initialQuantity = BigDecimal("100"),
        remainingQuantity = BigDecimal(remaining),
        unit = unit,
    )

    private fun line(
        batches: List<InventoryBatch>,
        schedules: List<Schedule> = listOf(schedule()),
        day: LocalDate = today,
    ) = InventoryCalculator.buildStockLine(
        medicationId = 1,
        medicationName = "Test",
        unit = "tbl",
        batches = batches,
        schedules = schedules,
        today = day,
    )

    // --- daily consumption -----------------------------------------------------

    @Test
    fun `twice daily counts both administrations`() {
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(schedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))),
        )
        assertEquals(0, perDay.compareTo(BigDecimal("2")))
    }

    @Test
    fun `three doses of two tablets is six per day`() {
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(schedule(dose = "2", times = List(3) { LocalTime.of(6 + it * 6, 0) })),
        )
        assertEquals(0, perDay.compareTo(BigDecimal("6")))
    }

    @Test
    fun `single daily dose stays one`() {
        val perDay = InventoryCalculator.dailyConsumption(listOf(schedule()))
        assertEquals(0, perDay.compareTo(BigDecimal.ONE))
    }

    @Test
    fun `interval hours ignores times of day`() {
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(
                schedule(
                    type = ScheduleType.INTERVAL_HOURS,
                    interval = "12",
                    times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                ),
            ),
        )
        assertEquals(0, perDay.compareTo(BigDecimal("2")))
    }

    @Test
    fun `three and a half day patch cycle is fractional`() {
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(schedule(type = ScheduleType.INTERVAL_DAYS, interval = "3.5")),
        )
        assertEquals(0, perDay.round(3).compareTo(BigDecimal("0.286")))
    }

    @Test
    fun `cyclical with two daily doses scales by cycle and doses`() {
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(
                schedule(
                    type = ScheduleType.CYCLICAL,
                    interval = "21",
                    times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
                ),
            ),
        )
        // 21 days on in a 28-day cycle, 2 doses per on-day = 1.5
        assertEquals(0, perDay.compareTo(BigDecimal("1.5")))
    }

    @Test
    fun `tapering uses the current step not the historical maximum`() {
        val taper = listOf(TaperStep(BigDecimal("4"), 7), TaperStep(BigDecimal("1"), 30))
        val day15 = today.minusDays(10).plusDays(14)
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(schedule(type = ScheduleType.TAPERING, taper = taper)),
            day15,
        )
        assertEquals(0, perDay.compareTo(BigDecimal.ONE))
    }

    @Test
    fun `tapering on the first step uses that dose`() {
        val taper = listOf(TaperStep(BigDecimal("4"), 7), TaperStep(BigDecimal("1"), 30))
        val perDay = InventoryCalculator.dailyConsumption(
            listOf(schedule(type = ScheduleType.TAPERING, taper = taper)),
            today.minusDays(9),
        )
        assertEquals(0, perDay.compareTo(BigDecimal("4")))
    }

    @Test
    fun `inactive schedules are excluded`() {
        assertEquals(0, InventoryCalculator.dailyConsumption(listOf(schedule(active = false))).signum())
    }

    @Test
    fun `two separate schedules for one medication add up`() {
        val morning = schedule()
        val evening = schedule().copy(id = 2, timesOfDay = listOf(LocalTime.of(20, 0)))
        val perDay = InventoryCalculator.dailyConsumption(listOf(morning, evening))
        assertEquals(0, perDay.compareTo(BigDecimal("2")))
    }

    @Test
    fun `prn contributes no scheduled consumption`() {
        assertEquals(
            0,
            InventoryCalculator.dailyConsumption(listOf(schedule(type = ScheduleType.PRN))).signum(),
        )
    }

    // --- days remaining reflects usable stock ---------------------------------

    @Test
    fun `days remaining uses the usable stock`() {
        val stock = line(listOf(batch(remaining = "10")))
        assertEquals(10, stock.daysRemaining)
    }

    @Test
    fun `expired batches do not count as available stock`() {
        val stock = line(
            listOf(
                batch(id = 1, remaining = "10", expiry = today.minusDays(30)),
                batch(id = 2, remaining = "4", expiry = today.plusDays(200)),
            ),
        )
        assertEquals(4, stock.daysRemaining)
    }

    @Test
    fun `a batch past its beyond use date does not count`() {
        val stock = line(
            listOf(
                batch(id = 1, remaining = "8", punctured = today.minusDays(60), budDays = 28),
                batch(id = 2, remaining = "3"),
            ),
        )
        assertEquals(3, stock.daysRemaining)
    }

    @Test
    fun `physical remaining still reports everything on the shelf`() {
        val stock = line(
            listOf(
                batch(id = 1, remaining = "10", expiry = today.minusDays(30)),
                batch(id = 2, remaining = "4"),
            ),
        )
        assertEquals(0, stock.remaining.compareTo(BigDecimal("14")))
        assertEquals(0, stock.usableRemaining.compareTo(BigDecimal("4")))
    }

    @Test
    fun `everything expired means zero days left and low stock`() {
        val stock = line(listOf(batch(remaining = "10", expiry = today.minusDays(1))))
        assertEquals(0, stock.daysRemaining)
        assertTrue(stock.isLow)
    }

    // --- expiry classification ------------------------------------------------

    @Test
    fun `already expired batch is reported as expired not expiring`() {
        val stock = line(listOf(batch(expiry = today.minusDays(30))))
        assertEquals(1, stock.expiredBatches.size)
        assertTrue(stock.expiringBatches.isEmpty())
    }

    @Test
    fun `batch expiring within thirty days is expiring soon`() {
        val stock = line(listOf(batch(expiry = today.plusDays(10))))
        assertEquals(1, stock.expiringBatches.size)
        assertTrue(stock.expiredBatches.isEmpty())
    }

    @Test
    fun `batch expiring beyond thirty days is neither`() {
        val stock = line(listOf(batch(expiry = today.plusDays(90))))
        assertTrue(stock.expiringBatches.isEmpty())
        assertTrue(stock.expiredBatches.isEmpty())
    }

    @Test
    fun `batch without expiry is not reported`() {
        val stock = line(listOf(batch(expiry = null)))
        assertTrue(stock.expiringBatches.isEmpty())
        assertTrue(stock.expiredBatches.isEmpty())
    }

    // --- beyond use date ------------------------------------------------------

    @Test
    fun `beyond use date derives from puncture and limit`() {
        val batch = batch(punctured = LocalDate.of(2026, 10, 1), budDays = 28)
        assertEquals(LocalDate.of(2026, 10, 29), batch.beyondUseDate())
    }

    @Test
    fun `punctured batch past bud is flagged`() {
        val stock = line(listOf(batch(remaining = "3", punctured = today.minusDays(40), budDays = 28)))
        assertEquals(1, stock.beyondUsePassed.size)
    }

    @Test
    fun `empty vial past bud is not flagged for disposal`() {
        val stock = line(listOf(batch(remaining = "0", punctured = today.minusDays(90), budDays = 28)))
        assertTrue("nothing left to discard", stock.beyondUsePassed.isEmpty())
    }

    @Test
    fun `bud within a week is flagged as soon`() {
        val stock = line(listOf(batch(remaining = "3", punctured = today.minusDays(24), budDays = 28)))
        assertEquals(1, stock.beyondUseSoon.size)
        assertTrue(stock.beyondUsePassed.isEmpty())
    }

    // --- edge cases -----------------------------------------------------------

    @Test
    fun `zero stock is low`() {
        val stock = line(emptyList())
        assertEquals(0, stock.daysRemaining)
        assertTrue(stock.isLow)
    }

    @Test
    fun `prn only medication has no day estimate and is not low`() {
        val stock = line(
            listOf(batch(remaining = "10")),
            schedules = listOf(schedule(type = ScheduleType.PRN)),
        )
        assertEquals(Int.MAX_VALUE, stock.daysRemaining)
        assertFalse(stock.isLow)
    }

    @Test
    fun `low threshold is respected`() {
        assertFalse(line(listOf(batch(remaining = "30"))).isLow)
        assertTrue(line(listOf(batch(remaining = "3"))).isLow)
    }

    // --- travel plan ----------------------------------------------------------

    @Test
    fun `travel plan requires whole units for a fractional need`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule())),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = mapOf(1L to BigDecimal("100")),
            tripDays = 4,
            bufferDays = 0,
        )
        assertEquals(0, plan.items.single().required.compareTo(BigDecimal("4")))
        assertFalse(plan.hasShortfall)
    }

    @Test
    fun `travel plan rounds a half tablet up`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule(dose = "0.5"))),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = mapOf(1L to BigDecimal("100")),
            tripDays = 3,
            bufferDays = 0,
        )
        // 1.5 tablets cannot be packed as 1.5 — you take 2
        assertEquals(0, plan.items.single().required.compareTo(BigDecimal("2")))
    }

    @Test
    fun `travel plan includes the safety buffer`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule())),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = emptyMap(),
            tripDays = 5,
            bufferDays = 2,
        )
        assertEquals(0, plan.items.single().required.compareTo(BigDecimal("7")))
        assertEquals(7, plan.tripDays)
    }

    @Test
    fun `travel plan flags a shortfall`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule(dose = "2"))),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = mapOf(1L to BigDecimal("10")),
            tripDays = 7,
            bufferDays = 2,
        )
        assertTrue(plan.hasShortfall)
        assertEquals(0, plan.items.single().shortfall.compareTo(BigDecimal("8")))
    }

    @Test
    fun `travel plan uses the taper step in force on the trip`() {
        val taper = listOf(TaperStep(BigDecimal("4"), 7), TaperStep(BigDecimal("1"), 30))
        val tripStart = today.minusDays(10).plusDays(14)
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule(type = ScheduleType.TAPERING, taper = taper))),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = emptyMap(),
            tripDays = 10,
            bufferDays = 0,
            tripStart = tripStart,
        )
        assertEquals(0, plan.items.single().required.compareTo(BigDecimal("10")))
    }

    @Test
    fun `travel plan counts twice daily correctly`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(
                1L to listOf(schedule(times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))),
            ),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = emptyMap(),
            tripDays = 5,
            bufferDays = 0,
        )
        assertEquals(0, plan.items.single().required.compareTo(BigDecimal("10")))
    }

    @Test
    fun `prn only medication asks for a manual plan`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule(type = ScheduleType.PRN))),
            names = mapOf(1L to "Painkiller"),
            units = mapOf(1L to "tbl"),
            availableByMedication = emptyMap(),
            tripDays = 5,
            bufferDays = 0,
        )
        assertFalse(plan.hasShortfall)
        assertEquals(MessageKey.INVENTORY_PRN_MANUAL, plan.items.single().suggestion.key)
    }

    @Test
    fun `trip length counts both endpoints`() {
        assertEquals(8, InventoryCalculator.daysBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8)))
        assertEquals(1, InventoryCalculator.daysBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)))
    }
}
