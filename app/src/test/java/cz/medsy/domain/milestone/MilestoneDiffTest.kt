package cz.medsy.domain.milestone

import cz.medsy.domain.message.plain
import cz.medsy.domain.message.plainArgs

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageArg
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.ChangeType
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.TaperStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Milestones explain jumps in the bloodwork graphs, so they must fire on clinical
 * changes only — recording a note edit would bury the real events under noise.
 */
class MilestoneDiffTest {

    private fun schedule(
        dose: String = "5",
        unit: String = "mg",
        type: ScheduleType = ScheduleType.INTERVAL_DAYS,
        interval: String? = "3.5",
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        active: Boolean = true,
        taper: List<TaperStep> = emptyList(),
        note: String? = null,
    ) = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = type,
        intervalValue = interval?.let(::BigDecimal),
        timesOfDay = times,
        doseAmount = BigDecimal(dose),
        doseUnit = unit,
        mealRelation = MealRelation.NONE,
        isActive = active,
        startDate = LocalDate.of(2026, 1, 1),
        taperSteps = taper,
        note = note,
    )

    private fun types(previous: Schedule?, current: Schedule): List<ChangeType> =
        MilestoneDiff.between(previous, current).map { it.changeType }

    /** Text argument at [index]; the tests assert on keys, not on English wording. */
    private fun Message.arg(index: Int): String = args[index].plain()

    /** Nested message at [index], e.g. the schedule description inside "Started …". */
    private fun Message.nested(index: Int): Message = (args[index] as MessageArg.Nested).message

    // --- creation and deletion -------------------------------------------------

    @Test
    fun `creating a schedule records STARTED`() {
        val changes = MilestoneDiff.between(null, schedule())

        assertEquals(1, changes.size)
        assertEquals(ChangeType.STARTED, changes.single().changeType)
        assertEquals(MessageKey.MS_STARTED, changes.single().message.key)
    }

    @Test
    fun `deleting a schedule records STOPPED`() {
        val change = MilestoneDiff.stopped(schedule())

        assertEquals(ChangeType.STOPPED, change.changeType)
        assertEquals(MessageKey.MS_STOPPED, change.message.key)
    }

    // --- dose ------------------------------------------------------------------

    @Test
    fun `changing the dose records DOSE_CHANGED with both values`() {
        val changes = MilestoneDiff.between(schedule(dose = "5"), schedule(dose = "7.5"))

        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals(ChangeType.DOSE_CHANGED, change.changeType)
        assertEquals(MessageKey.MS_DOSE_CHANGED, change.message.key)
        assertEquals("5 mg", change.message.arg(0))
        assertEquals("7.5 mg", change.message.arg(1))
    }

    @Test
    fun `changing only the unit is a dose change`() {
        assertEquals(
            listOf(ChangeType.DOSE_CHANGED),
            types(schedule(unit = "mg"), schedule(unit = "µg")),
        )
    }

    @Test
    fun `an identical schedule produces no milestone`() {
        assertTrue(MilestoneDiff.between(schedule(), schedule()).isEmpty())
    }

    @Test
    fun `equal but differently scaled doses are not a change`() {
        // 5 vs 5.0 must not look like an edit.
        assertEquals(
            emptyList<ChangeType>(),
            types(schedule(dose = "5"), schedule(dose = "5.0")),
        )
    }

    // --- frequency -------------------------------------------------------------

    @Test
    fun `changing the interval records a schedule change`() {
        val changes = MilestoneDiff.between(
            schedule(interval = "3.5"),
            schedule(interval = "5"),
        )

        assertEquals(1, changes.size)
        assertEquals(ChangeType.ROUTE_CHANGED, changes.single().changeType)
        val (before, after) = changes.single().message.args
            .map { (it as MessageArg.Nested).message }
        val beforeRecurrence = before.nested(1)
        val afterRecurrence = after.nested(1)
        assertEquals(MessageKey.MS_SCHEDULE_CHANGED, changes.single().message.key)
        assertEquals(MessageKey.SCHEDULE_EVERY_DAYS, beforeRecurrence.key)
        assertEquals(3, beforeRecurrence.count)
        assertEquals(5, afterRecurrence.count)
    }

    @Test
    fun `changing the schedule type records a schedule change`() {
        val changes = MilestoneDiff.between(
            schedule(type = ScheduleType.FIXED_DAILY, interval = null),
            schedule(type = ScheduleType.INTERVAL_DAYS, interval = "3.5"),
        )
        assertEquals(ChangeType.ROUTE_CHANGED, changes.single().changeType)
    }

    @Test
    fun `adding a second daily time records a schedule change`() {
        val changes = MilestoneDiff.between(
            schedule(type = ScheduleType.FIXED_DAILY, interval = null, times = listOf(LocalTime.of(8, 0))),
            schedule(
                type = ScheduleType.FIXED_DAILY,
                interval = null,
                times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)),
            ),
        )
        assertEquals(1, changes.size)
        assertEquals(ChangeType.ROUTE_CHANGED, changes.single().changeType)
    }

    @Test
    fun `dose and frequency change together records one dose milestone`() {
        val changes = MilestoneDiff.between(
            schedule(dose = "5", interval = "3.5"),
            schedule(dose = "7", interval = "5"),
        )

        // One milestone, not two: the dose line already tells the story and two pins in
        // the same place would overlap on the chart.
        assertEquals(1, changes.size)
        assertEquals(ChangeType.DOSE_CHANGED, changes.single().changeType)
    }

    // --- activation ------------------------------------------------------------

    @Test
    fun `pausing records STOPPED`() {
        val changes = MilestoneDiff.between(schedule(active = true), schedule(active = false))

        assertEquals(1, changes.size)
        assertEquals(ChangeType.STOPPED, changes.single().changeType)
        assertEquals(MessageKey.MS_PAUSED, changes.single().message.key)
    }

    @Test
    fun `resuming records STARTED`() {
        val changes = MilestoneDiff.between(schedule(active = false), schedule(active = true))

        assertEquals(1, changes.size)
        assertEquals(ChangeType.STARTED, changes.single().changeType)
        assertEquals(MessageKey.MS_RESUMED, changes.single().message.key)
    }

    @Test
    fun `pausing with a dose change records both`() {
        val types = types(schedule(dose = "5", active = true), schedule(dose = "2", active = false))
        assertEquals(listOf(ChangeType.DOSE_CHANGED, ChangeType.STOPPED), types)
    }

    // --- non-clinical edits ----------------------------------------------------

    @Test
    fun `editing only the note is not a milestone`() {
        assertTrue(MilestoneDiff.between(schedule(note = null), schedule(note = "after food")).isEmpty())
    }

    @Test
    fun `changing the note alongside a dose still records the dose`() {
        val changes = MilestoneDiff.between(schedule(dose = "5", note = "a"), schedule(dose = "6", note = "b"))
        assertEquals(1, changes.size)
        assertEquals(ChangeType.DOSE_CHANGED, changes.single().changeType)
    }

    // --- descriptions ----------------------------------------------------------

    @Test
    fun `description of an interval schedule names dose, interval and time`() {
        val text = MilestoneDiff.describe(schedule())
        assertEquals(MessageKey.SCHEDULE_SUMMARY_AT_TIMES, text.key)
        assertEquals("5 mg", text.arg(0))
        assertEquals(MessageKey.SCHEDULE_EVERY_DAYS, text.nested(1).key)
        assertEquals("3.5", text.nested(1).arg(0))
        assertEquals("08:00", text.arg(2))
    }

    @Test
    fun `a daily schedule uses the daily key`() {
        val text = MilestoneDiff.describe(schedule(type = ScheduleType.FIXED_DAILY, interval = null))
        assertEquals(MessageKey.SCHEDULE_DAILY, text.nested(1).key)
    }

    @Test
    fun `cyclical description names the on period`() {
        val text = MilestoneDiff.describe(schedule(type = ScheduleType.CYCLICAL, interval = "21"))
        assertEquals(MessageKey.SCHEDULE_CYCLICAL, text.nested(1).key)
        assertEquals("21", text.nested(1).arg(0))
    }

    @Test
    fun `prn description says as needed`() {
        val text = MilestoneDiff.describe(schedule(type = ScheduleType.PRN, interval = null))
        assertEquals(MessageKey.SCHEDULE_AS_NEEDED, text.nested(1).key)
    }

    @Test
    fun `taper change produces a schematic description`() {
        val text = MilestoneDiff.describe(
            schedule(
                type = ScheduleType.TAPERING,
                interval = null,
                taper = listOf(TaperStep(BigDecimal("4"), 7)),
            ),
        )
        assertEquals(MessageKey.SCHEDULE_TAPERING, text.nested(1).key)
    }
}
