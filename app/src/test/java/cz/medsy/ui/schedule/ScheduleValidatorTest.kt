package cz.medsy.ui.schedule

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * The editor must never persist a schedule the engine cannot turn into doses. Each
 * rejection below corresponds to a real way to create a silently dead regimen.
 */
class ScheduleValidatorTest {

    private fun draft(
        type: ScheduleType = ScheduleType.FIXED_DAILY,
        interval: String = "",
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        dose: String = "1",
        unit: String = "tbl",
        taper: List<ScheduleDraft.TaperDraft> = emptyList(),
        maxDaily: String = "",
    ) = ScheduleDraft(
        medicationId = 1,
        type = type,
        intervalValue = interval,
        times = times,
        doseAmount = dose,
        doseUnit = unit,
        taperSteps = taper,
        maxDailyDose = maxDaily,
    )

    private fun errorsOf(draft: ScheduleDraft): List<Message> {
        val result = ScheduleValidator.validate(draft)
        assertTrue("expected rejection for $draft", result is ValidationResult.Invalid)
        return (result as ValidationResult.Invalid).errors
    }

    private fun validOf(draft: ScheduleDraft) =
        (ScheduleValidator.validate(draft) as ValidationResult.Valid).schedule

    // --- happy paths -----------------------------------------------------------

    @Test
    fun `a plain daily schedule is accepted`() {
        val schedule = validOf(draft())

        assertEquals(ScheduleType.FIXED_DAILY, schedule.scheduleType)
        assertEquals(0, schedule.doseAmount.compareTo(BigDecimal.ONE))
        assertEquals(listOf(LocalTime.of(8, 0)), schedule.timesOfDay)
    }

    @Test
    fun `two daily times are kept and sorted`() {
        val schedule = validOf(draft(times = listOf(LocalTime.of(20, 0), LocalTime.of(8, 0))))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), schedule.timesOfDay)
    }

    @Test
    fun `interval days keeps the interval`() {
        val schedule = validOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = "3.5"))
        assertEquals(0, schedule.intervalValue!!.compareTo(BigDecimal("3.5")))
    }

    @Test
    fun `ten week injection interval is accepted`() {
        val schedule = validOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = "70"))
        assertEquals(0, schedule.intervalValue!!.compareTo(BigDecimal("70")))
    }

    @Test
    fun `interval hours is accepted`() {
        val schedule = validOf(draft(type = ScheduleType.INTERVAL_HOURS, interval = "12"))
        assertEquals(0, schedule.intervalValue!!.compareTo(BigDecimal("12")))
    }

    @Test
    fun `cyclical schedule keeps its on-days`() {
        val schedule = validOf(draft(type = ScheduleType.CYCLICAL, interval = "21"))
        assertEquals(ScheduleType.CYCLICAL, schedule.scheduleType)
        assertEquals(0, schedule.intervalValue!!.compareTo(BigDecimal("21")))
    }

    @Test
    fun `tapering keeps the steps`() {
        val schedule = validOf(
            draft(
                type = ScheduleType.TAPERING,
                taper = listOf(
                    ScheduleDraft.TaperDraft("4", "7"),
                    ScheduleDraft.TaperDraft("1", "30"),
                ),
            ),
        )
        assertEquals(2, schedule.taperSteps.size)
        assertEquals(0, schedule.taperSteps.first().doseAmount.compareTo(BigDecimal("4")))
        assertEquals(30, schedule.taperSteps.last().durationDays)
    }

    @Test
    fun `prn with a daily maximum is accepted`() {
        val schedule = validOf(
            draft(type = ScheduleType.PRN, dose = "1", maxDaily = "4"),
        )
        assertEquals(ScheduleType.PRN, schedule.scheduleType)
        assertEquals(0, schedule.maxDailyDose!!.compareTo(BigDecimal("4")))
    }

    @Test
    fun `meal relation and site rotation survive`() {
        val schedule = validOf(
            draft().copy(mealRelation = MealRelation.FASTING_BEFORE, requireSiteRotation = true),
        )
        assertEquals(MealRelation.FASTING_BEFORE, schedule.mealRelation)
        assertTrue(schedule.requireSiteRotation)
    }

    // --- rejections ------------------------------------------------------------

    @Test
    fun `interval days without an interval is rejected`() {
        val errors = errorsOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = ""))
        assertEquals(MessageKey.SCHEDULE_INTERVAL_POSITIVE, errors.single().key)
    }

    @Test
    fun `zero interval is rejected`() {
        assertTrue(errorsOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = "0")).isNotEmpty())
    }

    @Test
    fun `negative interval is rejected`() {
        assertTrue(errorsOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = "-3")).isNotEmpty())
    }

    @Test
    fun `absurdly long interval is rejected`() {
        val errors = errorsOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = "5000"))
        assertEquals(MessageKey.SCHEDULE_INTERVAL_MAX_DAYS, errors.single().key)
    }

    @Test
    fun `zero dose is rejected`() {
        val errors = errorsOf(draft(dose = "0"))
        assertEquals(MessageKey.SCHEDULE_DOSE_POSITIVE, errors.single().key)
    }

    @Test
    fun `non numeric dose is rejected`() {
        assertTrue(errorsOf(draft(dose = "abc")).isNotEmpty())
    }

    @Test
    fun `missing dose unit is rejected`() {
        assertTrue(errorsOf(draft(unit = "  ")).isNotEmpty())
    }

    @Test
    fun `daily schedule without a time is rejected`() {
        val errors = errorsOf(draft(times = emptyList()))
        assertEquals(MessageKey.SCHEDULE_NO_TIME, errors.first().key)
    }

    @Test
    fun `tapering without steps is rejected`() {
        val errors = errorsOf(draft(type = ScheduleType.TAPERING, taper = emptyList()))
        assertEquals(MessageKey.SCHEDULE_TAPER_EMPTY, errors.single().key)
    }

    @Test
    fun `taper step with zero duration is rejected`() {
        val errors = errorsOf(
            draft(
                type = ScheduleType.TAPERING,
                taper = listOf(ScheduleDraft.TaperDraft("2", "0")),
            ),
        )
        assertEquals(MessageKey.SCHEDULE_TAPER_DAYS, errors.single().key)
    }

    @Test
    fun `prn maximum not above a single dose is rejected`() {
        val errors = errorsOf(draft(type = ScheduleType.PRN, dose = "2", maxDaily = "2"))
        assertEquals(MessageKey.SCHEDULE_MAX_DAILY_ABOVE_DOSE, errors.single().key)
    }

    @Test
    fun `too many daily times is rejected`() {
        val times = (0 until 13).map { LocalTime.of(it % 24, 0) }
        assertTrue(errorsOf(draft(times = times)).isNotEmpty())
    }

    // --- round trip ------------------------------------------------------------

    @Test
    fun `an existing interval schedule round trips through the draft`() {
        val original = validOf(draft(type = ScheduleType.INTERVAL_DAYS, interval = "3.5", dose = "5"))
        val draft = ScheduleValidator.toDraft(original)
        val restored = validOf(draft)

        assertEquals(original.scheduleType, restored.scheduleType)
        assertEquals(0, original.intervalValue!!.compareTo(restored.intervalValue!!))
        assertEquals(0, original.doseAmount.compareTo(restored.doseAmount))
        assertEquals(original.timesOfDay, restored.timesOfDay)
    }

    @Test
    fun `an existing taper round trips`() {
        val original = validOf(
            draft(
                type = ScheduleType.TAPERING,
                taper = listOf(ScheduleDraft.TaperDraft("4", "7"), ScheduleDraft.TaperDraft("2", "7")),
            ),
        )
        val restored = validOf(ScheduleValidator.toDraft(original))

        assertEquals(2, restored.taperSteps.size)
        assertEquals(0, restored.taperSteps.first().doseAmount.compareTo(BigDecimal("4")))
    }

    @Test
    fun `editing keeps the original id so it updates instead of duplicating`() {
        val original = validOf(draft())
        val withId = original.copy(id = 42)
        val restored = validOf(ScheduleValidator.toDraft(withId))

        assertEquals(42L, restored.id)
    }

    @Test
    fun `start date is preserved`() {
        val start = LocalDate.of(2026, 3, 29)
        val schedule = validOf(draft().copy(startDate = start))
        assertEquals(start, schedule.startDate)
    }

    // --- gels and sprays -------------------------------------------------------

    @Test
    fun `pumps per dose round trip through the draft`() {
        val schedule = validOf(draft().copy(actuationsPerDose = "2"))
        assertEquals(2, schedule.actuationsPerDose)
        assertEquals("2", ScheduleValidator.toDraft(schedule).actuationsPerDose)
    }

    @Test
    fun `an unset pump count stays null`() {
        assertEquals(null, validOf(draft()).actuationsPerDose)
    }

    @Test
    fun `a non numeric pump count is rejected`() {
        assertTrue(errorsOf(draft().copy(actuationsPerDose = "abc")).isNotEmpty())
    }

    @Test
    fun `a zero pump count is rejected`() {
        assertTrue(errorsOf(draft().copy(actuationsPerDose = "0")).isNotEmpty())
    }

    // --- sublingual hold -------------------------------------------------------

    @Test
    fun `hold minutes round trip through the draft`() {
        val schedule = validOf(draft().copy(holdMinutes = "15"))
        assertEquals(15, schedule.holdMinutes)
        assertEquals("15", ScheduleValidator.toDraft(schedule).holdMinutes)
    }

    @Test
    fun `an unset hold stays null`() {
        assertEquals(null, validOf(draft()).holdMinutes)
    }

    @Test
    fun `a zero hold is rejected`() {
        assertTrue(errorsOf(draft().copy(holdMinutes = "0")).isNotEmpty())
    }

    @Test
    fun `an absurd hold is rejected`() {
        assertTrue(errorsOf(draft().copy(holdMinutes = "500")).isNotEmpty())
    }
}
