package cz.medsy.domain.calc

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ReferenceRangeTest {

    @Test
    fun `feminizing estradiol target matches WPATH 100 to 200 pg per ml`() {
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM)!!
        assertEquals(0, range.targetMinSi!!.round(0).compareTo(BigDecimal("367")))
        assertEquals(0, range.targetMaxSi!!.round(0).compareTo(BigDecimal("734")))
    }

    @Test
    fun `feminizing testosterone upper bound is 1_7 nmol per l`() {
        val range = ReferenceRanges.forBiomarker("TEST_TOTAL", ProfileType.HRT_FEM)!!
        assertEquals(0, range.targetMaxSi!!.compareTo(BigDecimal("1.7")))
    }

    @Test
    fun `testosterone below target evaluates low`() {
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM)
        val evaluation = RangeEvaluator.evaluate(BigDecimal("200"), range)
        assertEquals(RangeStatus.LOW, evaluation.status)
    }

    @Test
    fun `estradiol inside target evaluates in range`() {
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM)
        val evaluation = RangeEvaluator.evaluate(BigDecimal("500"), range)
        assertEquals(RangeStatus.IN_RANGE, evaluation.status)
    }

    @Test
    fun `estradiol above target evaluates high`() {
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM)
        val evaluation = RangeEvaluator.evaluate(BigDecimal("900"), range)
        assertEquals(RangeStatus.HIGH, evaluation.status)
    }

    @Test
    fun `estradiol far above warning evaluates critical high`() {
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM)
        val evaluation = RangeEvaluator.evaluate(BigDecimal("1500"), range)
        assertEquals(RangeStatus.CRITICAL_HIGH, evaluation.status)
    }

    @Test
    fun `missing range yields no range status`() {
        val evaluation = RangeEvaluator.evaluate(BigDecimal("1"), null)
        assertEquals(RangeStatus.NO_RANGE, evaluation.status)
    }

    @Test
    fun `deltas to bounds are computed`() {
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM)
        val evaluation = RangeEvaluator.evaluate(BigDecimal("400"), range)
        assertEquals(0, evaluation.deltaToMin!!.compareTo(BigDecimal("33")))
        assertEquals(0, evaluation.deltaToMax!!.compareTo(BigDecimal("-334")))
    }

    @Test
    fun `custom doctor range overrides the built in one`() {
        val custom = cz.medsy.domain.model.ReferenceRangeProfile(
            biomarkerCode = "E2",
            profileType = ProfileType.HRT_FEM,
            targetMinSi = BigDecimal("500"),
            targetMaxSi = BigDecimal("600"),
        )
        val range = ReferenceRanges.forBiomarker("E2", ProfileType.HRT_FEM, listOf(custom))!!
        assertEquals(0, range.targetMinSi!!.compareTo(BigDecimal("500")))
    }

    @Test
    fun `masculinizing haematocrit warns above 54 percent`() {
        val range = ReferenceRanges.forBiomarker("HCT", ProfileType.HRT_MASC)!!
        val evaluation = RangeEvaluator.evaluate(BigDecimal("55"), range)
        assertEquals(RangeStatus.CRITICAL_HIGH, evaluation.status)
    }
}

class BloodworkCalculatorTest {

    private val draw = Instant.parse("2026-11-11T07:30:00Z")

    @Test
    fun `draw just before next injection is a true trough`() {
        val last = Instant.parse("2026-11-07T08:00:00Z") // 3.5 days earlier
        val next = Instant.parse("2026-11-11T08:00:00Z") // 30 min after the draw
        val assessment = BloodworkCalculator.assess(draw, last, next, 84.0)
        assertTrue(assessment.isTrough)
        assertNotNull(assessment.hoursSinceLastDose)
    }

    @Test
    fun `explicit true trough window`() {
        val last = Instant.parse("2026-11-04T08:00:00Z")
        val next = Instant.parse("2026-11-11T09:00:00Z")
        val assessment = BloodworkCalculator.assess(draw, last, next, 84.0)
        assertTrue(assessment.isTrough)
        assertEquals(MessageKey.BLOODWORK_TRUE_TROUGH, assessment.label.key)
    }

    @Test
    fun `draw shortly after a dose is near peak`() {
        val last = Instant.parse("2026-11-11T06:00:00Z")
        val next = Instant.parse("2026-11-12T06:00:00Z")
        val assessment = BloodworkCalculator.assess(draw, last, next, 24.0)
        assertFalse(assessment.isTrough)
        assertEquals(MessageKey.BLOODWORK_NEAR_PEAK, assessment.label.key)
    }

    @Test
    fun `no prior dose cannot be assessed`() {
        val assessment = BloodworkCalculator.assess(draw, null, null, 24.0)
        assertFalse(assessment.isTrough)
        assertNull(assessment.hoursSinceLastDose)
    }

    @Test
    fun `draw before the last dose is flagged as a data problem`() {
        val last = Instant.parse("2026-11-11T09:00:00Z")
        val assessment = BloodworkCalculator.assess(draw, last, null, 24.0)
        assertFalse(assessment.isTrough)
        assertEquals(MessageKey.BLOODWORK_DRAW_BEFORE_DOSE, assessment.label.key)
    }

    @Test
    fun `mid cycle draw reports a percentage of the interval`() {
        val last = Instant.parse("2026-11-08T08:00:00Z")
        val assessment = BloodworkCalculator.assess(draw, last, null, 168.0)
        assertFalse(assessment.isTrough)
    }

    @Test
    fun `trough window scales with interval but never below six hours`() {
        assertEquals(6L, BloodworkCalculator.troughWindow(24.0).toHours())
        assertEquals(6L, BloodworkCalculator.troughWindow(null).toHours())
        assertEquals(16L, BloodworkCalculator.troughWindow(84.0).toHours())
    }

    @Test
    fun `nominal interval picks the longest schedule`() {
        val daily = schedule(ScheduleType.FIXED_DAILY, null)
        val weekly = schedule(ScheduleType.INTERVAL_DAYS, BigDecimal("7"))
        assertEquals(168.0, BloodworkCalculator.nominalIntervalHours(listOf(daily, weekly))!!, 0.001)
    }

    @Test
    fun `pre test warnings are produced for a morning draw with tablets`() {
        val warnings = BloodworkCalculator.preTestWarnings(
            listOf(schedule(ScheduleType.FIXED_DAILY, null, LocalTime.of(7, 0))),
            drawHourOfDay = 8,
        )
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.any { it.title.key == MessageKey.BLOODWORK_HOLD_MORNING_TITLE })
    }

    @Test
    fun `hours formatting is human readable`() {
        assertEquals("96 h 30 min", BloodworkCalculator.formatHours(96.5))
        assertEquals("30 min", BloodworkCalculator.formatHours(0.5))
    }

    private fun schedule(
        type: ScheduleType,
        interval: BigDecimal?,
        time: LocalTime = LocalTime.of(8, 0),
    ) = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = type,
        intervalValue = interval,
        timesOfDay = listOf(time),
        doseAmount = BigDecimal.ONE,
        doseUnit = "mg",
        mealRelation = MealRelation.FASTING_BEFORE,
        startDate = LocalDate.of(2026, 1, 1),
    )
}

class InventoryCalculatorTest {

    private fun schedule(type: ScheduleType, dose: String, interval: String? = null) = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = type,
        intervalValue = interval?.let(::BigDecimal),
        timesOfDay = listOf(LocalTime.of(8, 0)),
        doseAmount = BigDecimal(dose),
        doseUnit = "tbl",
        startDate = LocalDate.of(2026, 1, 1),
    )

    @Test
    fun `daily consumption equals the dose`() {
        assertEquals(
            0,
            InventoryCalculator.dailyConsumption(listOf(schedule(ScheduleType.FIXED_DAILY, "2")))
                .compareTo(BigDecimal("2")),
        )
    }

    @Test
    fun `three and a half day patch cycle consumes 0_286 per day`() {
        val daily = InventoryCalculator.dailyConsumption(
            listOf(schedule(ScheduleType.INTERVAL_DAYS, "1", "3.5")),
        )
        assertEquals(0, daily.round(3).compareTo(BigDecimal("0.286")))
    }

    @Test
    fun `every ten weeks injection consumes a seventh of a dose per day`() {
        val daily = InventoryCalculator.dailyConsumption(
            listOf(schedule(ScheduleType.INTERVAL_DAYS, "1", "70")),
        )
        assertEquals(0, daily.round(4).compareTo(BigDecimal("0.0143")))
    }

    @Test
    fun `twelve hour interval doubles daily consumption`() {
        val daily = InventoryCalculator.dailyConsumption(
            listOf(schedule(ScheduleType.INTERVAL_HOURS, "1", "12")),
        )
        assertEquals(0, daily.compareTo(BigDecimal("2")))
    }

    @Test
    fun `cyclical 21 on 7 off consumes three quarters of a dose per day`() {
        val daily = InventoryCalculator.dailyConsumption(
            listOf(schedule(ScheduleType.CYCLICAL, "1", "21")),
        )
        assertEquals(0, daily.round(3).compareTo(BigDecimal("0.750")))
    }

    @Test
    fun `prn does not count towards daily consumption`() {
        assertEquals(
            0,
            InventoryCalculator.dailyConsumption(listOf(schedule(ScheduleType.PRN, "1"))).signum(),
        )
    }

    @Test
    fun `days remaining floors`() {
        assertEquals(16, InventoryCalculator.daysRemaining(BigDecimal("2.4"), BigDecimal("0.143")))
    }

    @Test
    fun `low stock is detected against the threshold`() {
        val today = LocalDate.of(2026, 11, 11)
        val batches = listOf(
            InventoryBatch(
                id = 1,
                medicationId = 1,
                initialQuantity = BigDecimal("10"),
                remainingQuantity = BigDecimal("3"),
                unit = "tbl",
            ),
        )
        val line = InventoryCalculator.buildStockLine(
            medicationId = 1,
            medicationName = "Test",
            unit = "tbl",
            batches = batches,
            schedules = listOf(schedule(ScheduleType.FIXED_DAILY, "1")),
            today = today,
            thresholdDays = 7,
        )
        assertTrue(line.isLow)
        assertEquals(3, line.daysRemaining)
    }

    @Test
    fun `beyond use date is derived from first puncture`() {
        val batch = InventoryBatch(
            id = 1,
            medicationId = 1,
            firstPuncturedDate = LocalDate.of(2026, 10, 1),
            beyondUseDaysLimit = 28,
            initialQuantity = BigDecimal("10"),
            remainingQuantity = BigDecimal("5"),
            unit = "mL",
        )
        assertEquals(LocalDate.of(2026, 10, 29), batch.beyondUseDate())
    }

    @Test
    fun `beyond use passed batches are reported`() {
        val today = LocalDate.of(2026, 11, 11)
        val batch = InventoryBatch(
            id = 1,
            medicationId = 1,
            firstPuncturedDate = LocalDate.of(2026, 9, 1),
            beyondUseDaysLimit = 28,
            initialQuantity = BigDecimal("10"),
            remainingQuantity = BigDecimal("5"),
            unit = "mL",
        )
        val line = InventoryCalculator.buildStockLine(
            1, "Vial", "mL", listOf(batch), emptyList(), today,
        )
        assertEquals(1, line.beyondUsePassed.size)
    }

    @Test
    fun `travel plan flags a shortfall`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule(ScheduleType.FIXED_DAILY, "2"))),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = mapOf(1L to BigDecimal("10")),
            tripDays = 7,
            bufferDays = 2,
        )
        assertTrue(plan.hasShortfall)
        val item = plan.items.first()
        assertEquals(0, item.required.compareTo(BigDecimal("18")))
        assertEquals(0, item.shortfall.compareTo(BigDecimal("8")))
    }

    @Test
    fun `travel plan with enough stock has no shortfall`() {
        val plan = InventoryCalculator.travelPlan(
            schedulesByMedication = mapOf(1L to listOf(schedule(ScheduleType.FIXED_DAILY, "1"))),
            names = mapOf(1L to "Tablets"),
            units = mapOf(1L to "tbl"),
            availableByMedication = mapOf(1L to BigDecimal("100")),
            tripDays = 5,
            bufferDays = 2,
        )
        assertFalse(plan.hasShortfall)
    }

    @Test
    fun `trip length is inclusive of both dates`() {
        assertEquals(8, InventoryCalculator.daysBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 8)))
    }

    @Test
    fun `injection consumables match the spec`() {
        val consumables = InventoryCalculator.consumablesPerInjection()
        assertEquals(2, consumables[InventoryCalculator.ConsumableKind.ALCOHOL_SWAB])
        assertEquals(1, consumables[InventoryCalculator.ConsumableKind.SYRINGE])
    }
}

class AdherenceCalculatorTest {

    @Test
    fun `summary derives missed doses`() {
        val summary = AdherenceCalculator.summarize(taken = 85, skipped = 3, expected = 90)
        assertEquals(2, summary.missed)
        assertEquals(90, summary.total)
    }

    @Test
    fun `percent is zero when nothing was expected`() {
        assertEquals(0.0, AdherenceCalculator.percent(0, 0), 0.001)
    }

    @Test
    fun `percent never exceeds one hundred`() {
        assertEquals(100.0, AdherenceCalculator.percent(10, 5), 0.001)
    }
}

@Suppress("unused")
private val unusedSite = ApplicationSite.LEFT_DELTOID
