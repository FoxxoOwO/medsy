package cz.medsy.domain.schedule

import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The overlay merges every dose that falls on the same minute, so this predicate is what stops
 * two unrelated regimens from being shown as one batch.
 */
class DoseGroupTest {

    private val prague = ZoneId.of("Europe/Prague")

    private fun schedule(
        id: Long,
        medicationId: Long,
        times: List<LocalTime>,
        start: LocalDate = LocalDate.of(2026, 1, 1),
    ) = Schedule(
        id = id,
        medicationId = medicationId,
        scheduleType = ScheduleType.FIXED_DAILY,
        intervalValue = null,
        timesOfDay = times,
        doseAmount = BigDecimal("1"),
        doseUnit = "tbl",
        mealRelation = MealRelation.NONE,
        startDate = start,
        daysOfWeekBitmask = null,
        taperSteps = emptyList(),
    )

    private fun instant(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), prague).toInstant()

    @Test
    fun `keeps only the schedules due on the same minute`() {
        val morning = schedule(1, 1, listOf(LocalTime.of(8, 0)))
        val sameTime = schedule(2, 2, listOf(LocalTime.of(8, 0)))
        val evening = schedule(3, 3, listOf(LocalTime.of(20, 0)))

        val group = dosesDueAt(listOf(morning, sameTime, evening), instant("2026-03-10", "08:00"), prague)

        assertEquals(listOf(1L, 2L), group.map { it.id })
    }

    @Test
    fun `a minute later is not the same batch`() {
        val eight = schedule(1, 1, listOf(LocalTime.of(8, 0)))
        val quarterPast = schedule(2, 2, listOf(LocalTime.of(8, 15)))

        assertTrue(dosesDueAt(listOf(eight, quarterPast), instant("2026-03-10", "08:15"), prague).map { it.id } == listOf(2L))
    }

    @Test
    fun `a schedule that started later contributes nothing`() {
        val future = schedule(1, 1, listOf(LocalTime.of(8, 0)), start = LocalDate.of(2026, 5, 1))

        assertTrue(dosesDueAt(listOf(future), instant("2026-03-10", "08:00"), prague).isEmpty())
    }
}
