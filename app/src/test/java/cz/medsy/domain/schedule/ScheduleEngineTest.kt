package cz.medsy.domain.schedule

import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.TaperStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleEngineTest {

    private val prague = ZoneId.of("Europe/Prague")

    private fun schedule(
        type: ScheduleType = ScheduleType.FIXED_DAILY,
        interval: String? = null,
        times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
        start: LocalDate = LocalDate.of(2026, 1, 1),
        dose: String = "1",
        mask: Int? = null,
        taper: List<TaperStep> = emptyList(),
    ) = Schedule(
        id = 1,
        medicationId = 1,
        scheduleType = type,
        intervalValue = interval?.let(::BigDecimal),
        timesOfDay = times,
        doseAmount = BigDecimal(dose),
        doseUnit = "tbl",
        mealRelation = MealRelation.NONE,
        startDate = start,
        daysOfWeekBitmask = mask,
        taperSteps = taper,
    )

    private fun instant(date: String, time: String, zone: ZoneId = prague): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant()

    @Test
    fun `daily schedule keeps local wall clock time across the spring DST shift`() {
        // Czech DST starts 2026-03-29 at 02:00.
        val s = schedule(start = LocalDate.of(2026, 3, 27))
        val from = instant("2026-03-27", "00:00")
        val until = instant("2026-04-01", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)

        val localTimes = occurrences.map { it.localTime }
        assertTrue("all doses at 08:00: $localTimes", localTimes.all { it == LocalTime.of(8, 0) })

        val before = occurrences.first { it.localDate == LocalDate.of(2026, 3, 28) }
        val after = occurrences.first { it.localDate == LocalDate.of(2026, 3, 30) }
        // Absolute gap must be 47h (23h day), not 48h — that proves we schedule in local time.
        val gap = Duration.between(before.dueAt, after.dueAt)
        assertEquals(47L, gap.toHours())
    }

    @Test
    fun `daily schedule keeps wall clock time across the autumn DST shift`() {
        val s = schedule(start = LocalDate.of(2026, 10, 23))
        val from = instant("2026-10-23", "00:00")
        val until = instant("2026-10-28", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)
        assertTrue(occurrences.all { it.localTime == LocalTime.of(8, 0) })
    }

    @Test
    fun `interval days uses absolute time so 3_5 day patches stay stable`() {
        val s = schedule(
            type = ScheduleType.INTERVAL_DAYS,
            interval = "3.5",
            start = LocalDate.of(2026, 1, 1),
        )
        val from = instant("2026-01-01", "00:00")
        val until = instant("2026-01-22", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)

        assertTrue(occurrences.size >= 5)
        occurrences.zipWithNext().forEach { (a, b) ->
            val gap = Duration.between(a.dueAt, b.dueAt)
            // 3.5 days = 302400 s
            assertEquals("gap was $gap", 302_400L, gap.toSeconds())
        }
    }

    @Test
    fun `interval hours anchors on start date time`() {
        val s = schedule(
            type = ScheduleType.INTERVAL_HOURS,
            interval = "12",
            start = LocalDate.of(2026, 5, 1),
        )
        val from = instant("2026-05-01", "00:00")
        val until = instant("2026-05-02", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)
        assertEquals(2, occurrences.size)
        assertEquals(LocalTime.of(8, 0), occurrences[0].localTime)
        assertEquals(LocalTime.of(20, 0), occurrences[1].localTime)
    }

    @Test
    fun `cyclical schedule is on for 21 days then off for 7`() {
        val s = schedule(type = ScheduleType.CYCLICAL, start = LocalDate.of(2026, 2, 1))
        val from = instant("2026-02-01", "00:00")
        val until = instant("2026-03-02", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)
        // Feb 1..21 on (21 doses), Feb 22..28 off, Mar 1 on.
        assertEquals(22, occurrences.size)
        assertTrue(occurrences.none { it.localDate == LocalDate.of(2026, 2, 25) })
        assertTrue(occurrences.any { it.localDate == LocalDate.of(2026, 3, 1) })
    }

    @Test
    fun `tapering walks through each step then stops`() {
        val s = schedule(
            type = ScheduleType.TAPERING,
            start = LocalDate.of(2026, 4, 1),
            taper = listOf(
                TaperStep(BigDecimal("4"), 7),
                TaperStep(BigDecimal("2"), 7),
                TaperStep(BigDecimal("1"), 7),
            ),
        )
        val from = instant("2026-04-01", "00:00")
        val until = instant("2026-05-01", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)

        assertEquals(21, occurrences.size)
        assertEquals(0, occurrences.first().doseAmount.compareTo(BigDecimal("4")))
        val day8 = occurrences.first { it.localDate == LocalDate.of(2026, 4, 8) }
        assertEquals(0, day8.doseAmount.compareTo(BigDecimal("2")))
        val day15 = occurrences.first { it.localDate == LocalDate.of(2026, 4, 15) }
        assertEquals(0, day15.doseAmount.compareTo(BigDecimal("1")))
    }

    @Test
    fun `taper dose on day helper matches engine output`() {
        val s = schedule(
            type = ScheduleType.TAPERING,
            start = LocalDate.of(2026, 4, 1),
            taper = listOf(TaperStep(BigDecimal("4"), 3), TaperStep(BigDecimal("2"), 3)),
        )
        assertEquals(0, ScheduleEngine.doseOnDay(s, LocalDate.of(2026, 4, 1))!!.compareTo(BigDecimal("4")))
        assertEquals(0, ScheduleEngine.doseOnDay(s, LocalDate.of(2026, 4, 4))!!.compareTo(BigDecimal("2")))
        assertEquals(null, ScheduleEngine.doseOnDay(s, LocalDate.of(2026, 4, 10)))
    }

    @Test
    fun `day of week mask only produces selected weekdays`() {
        val mask = ScheduleEngine.daysOfWeekMask(setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.THURSDAY))
        val s = schedule(start = LocalDate.of(2026, 6, 1), mask = mask)
        val from = instant("2026-06-01", "00:00")
        val until = instant("2026-06-15", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)
        assertTrue(occurrences.all {
            it.localDate.dayOfWeek == java.time.DayOfWeek.MONDAY ||
                it.localDate.dayOfWeek == java.time.DayOfWeek.THURSDAY
        })
        assertEquals(4, occurrences.size)
    }

    @Test
    fun `meal offset shifts the occurrence`() {
        val s = schedule(times = listOf(LocalTime.of(8, 0))).copy(offsetMinutes = 120)
        val from = instant("2026-07-01", "00:00")
        val until = instant("2026-07-02", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)
        assertEquals(LocalTime.of(10, 0), occurrences.first().localTime)
    }

    @Test
    fun `prn schedule generates no automatic occurrences`() {
        val s = schedule(type = ScheduleType.PRN)
        val from = instant("2026-07-01", "00:00")
        val until = instant("2026-07-02", "00:00")
        assertTrue(ScheduleEngine.occurrences(s, from, until, prague).isEmpty())
    }

    @Test
    fun `inactive schedule generates nothing`() {
        val s = schedule().copy(isActive = false)
        val from = instant("2026-07-01", "00:00")
        val until = Instant.parse("2026-07-02T00:00:00Z")
        assertTrue(ScheduleEngine.occurrences(s, from, until, prague).isEmpty())
    }

    @Test
    fun `next occurrence is strictly after the reference instant`() {
        val s = schedule(start = LocalDate.of(2026, 1, 1))
        val after = instant("2026-08-10", "08:00")
        val next = ScheduleEngine.nextOccurrence(s, after, prague)
        assertEquals(LocalDate.of(2026, 8, 11), next!!.localDate)
        assertEquals(LocalTime.of(8, 0), next.localTime)
    }

    @Test
    fun `late dose detection uses a one hour threshold`() {
        val scheduled = Instant.parse("2026-09-01T08:00:00Z")
        assertEquals(
            cz.medsy.domain.model.DoseStatus.TAKEN,
            ScheduleEngine.generatedStatusFor(scheduled, scheduled.plus(Duration.ofMinutes(30))),
        )
        assertEquals(
            cz.medsy.domain.model.DoseStatus.LATE,
            ScheduleEngine.generatedStatusFor(scheduled, scheduled.plus(Duration.ofMinutes(61))),
        )
    }

    @Test
    fun `day mask helpers round trip`() {
        val days = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.SUNDAY)
        val mask = ScheduleEngine.daysOfWeekMask(days)
        assertEquals(days, ScheduleEngine.daysFromMask(mask))
    }

    @Test
    fun `occurrences are sorted`() {
        val s = schedule(times = listOf(LocalTime.of(20, 0), LocalTime.of(8, 0), LocalTime.of(14, 0)))
        val from = instant("2026-07-01", "00:00")
        val until = instant("2026-07-02", "00:00")
        val occurrences = ScheduleEngine.occurrences(s, from, until, prague)
        assertEquals(
            listOf(LocalTime.of(8, 0), LocalTime.of(14, 0), LocalTime.of(20, 0)),
            occurrences.map { it.localTime },
        )
    }

    @Test
    fun `utc zone daily schedule does not shift`() {
        val utc = ZoneId.of("UTC")
        val s = schedule(start = LocalDate.of(2026, 3, 27))
        val from = ZonedDateTime.of(LocalDate.of(2026, 3, 27), LocalTime.MIN, utc).toInstant()
        val until = ZonedDateTime.of(LocalDate.of(2026, 4, 1), LocalTime.MIN, utc).toInstant()
        val occurrences = ScheduleEngine.occurrences(s, from, until, utc)
        // 5 doses (27th..31st) -> 4 full days between first and last
        assertEquals(5, occurrences.size)
        assertEquals(96L, Duration.between(occurrences.first().dueAt, occurrences.last().dueAt).toHours())
    }
}
