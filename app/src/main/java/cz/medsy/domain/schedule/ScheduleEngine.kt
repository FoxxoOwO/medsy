package cz.medsy.domain.schedule

import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.TaperStep
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class Occurrence(
    val scheduleId: Long,
    val medicationId: Long,
    val dueAt: Instant,
    val localDate: LocalDate,
    val localTime: LocalTime,
    val doseAmount: BigDecimal,
    val doseUnit: String,
)

object ScheduleEngine {

    private val MC = MathContext(20, RoundingMode.HALF_UP)

    private const val CYCLICAL_ON_DAYS = 21
    private const val CYCLICAL_OFF_DAYS = 7

    fun occurrences(
        schedule: Schedule,
        from: Instant,
        until: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        if (!schedule.isActive) return emptyList()
        if (!until.isAfter(from)) return emptyList()

        return when (schedule.scheduleType) {
            ScheduleType.FIXED_DAILY -> fixedDaily(schedule, from, until, zone)
            ScheduleType.INTERVAL_HOURS -> intervalHours(schedule, from, until, zone)
            ScheduleType.INTERVAL_DAYS -> intervalDays(schedule, from, until, zone)
            ScheduleType.CYCLICAL -> cyclical(schedule, from, until, zone)
            ScheduleType.TAPERING -> tapering(schedule, from, until, zone)
            ScheduleType.PRN -> emptyList()
        }.sortedBy { it.dueAt }
    }

    fun nextOccurrence(
        schedule: Schedule,
        after: Instant,
        zone: ZoneId,
        horizon: Duration = Duration.ofDays(400),
    ): Occurrence? {
        val from = alignToSecond(after)
        val until = from.plus(horizon)
        return occurrences(schedule, from, until, zone)
            .firstOrNull { it.dueAt.isAfter(after) }
    }

    private fun fixedDaily(
        schedule: Schedule,
        from: Instant,
        until: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        val times = withOffset(schedule)
        val firstDay = from.atZone(zone).toLocalDate().coerceAtLeast(schedule.startDate)
        val lastDay = until.atZone(zone).toLocalDate()
        val out = ArrayList<Occurrence>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            if (matchesDayMask(schedule, day)) {
                for (time in times) {
                    val at = wallClock(day, time, zone)
                    val instant = at.toInstant()
                    if (instant in from..until) {
                        out += occurrence(schedule, instant, day, time)
                    }
                }
            }
            day = day.plusDays(1)
        }
        return out
    }

    private fun intervalHours(
        schedule: Schedule,
        from: Instant,
        until: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        val hours = schedule.intervalValue ?: return emptyList()
        if (hours.signum() <= 0) return emptyList()
        val stepSeconds = hours.multiply(BigDecimal("3600"))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        if (stepSeconds <= 0) return emptyList()

        val anchor = anchorInstant(schedule, zone)
        val out = ArrayList<Occurrence>()
        var k = 0L
        while (true) {
            val due = anchor.plusSeconds(stepSeconds * k)
            if (due.isAfter(until)) break
            if (!due.isBefore(from)) {
                val zdt = due.atZone(zone)
                out += occurrence(schedule, due, zdt.toLocalDate(), zdt.toLocalTime())
            }
            k++
            if (k > MAX_OCCURRENCES) break
        }
        return out
    }

    private fun intervalDays(
        schedule: Schedule,
        from: Instant,
        until: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        val days = schedule.intervalValue ?: return emptyList()
        if (days.signum() <= 0) return emptyList()
        val stepSeconds = days.multiply(BigDecimal("86400"))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        if (stepSeconds <= 0) return emptyList()

        val anchor = anchorInstant(schedule, zone)
        val out = ArrayList<Occurrence>()
        var k = 0L
        while (true) {
            val due = anchor.plusSeconds(stepSeconds * k)
            if (due.isAfter(until)) break
            if (!due.isBefore(from)) {
                val zdt = due.atZone(zone)
                out += occurrence(schedule, due, zdt.toLocalDate(), zdt.toLocalTime())
            }
            k++
            if (k > MAX_OCCURRENCES) break
        }
        return out
    }

    private fun cyclical(
        schedule: Schedule,
        from: Instant,
        until: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        val times = withOffset(schedule)
        val onDays = schedule.intervalValue?.toInt()?.takeIf { it > 0 } ?: CYCLICAL_ON_DAYS
        val offDays = CYCLICAL_OFF_DAYS
        val cycleLength = onDays + offDays
        val out = ArrayList<Occurrence>()
        var day = from.atZone(zone).toLocalDate().coerceAtLeast(schedule.startDate)
        val lastDay = until.atZone(zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val dayIndex = ChronoUnit.DAYS.between(schedule.startDate, day).toInt()
            val positionInCycle = Math.floorMod(dayIndex, cycleLength)
            if (positionInCycle < onDays) {
                for (time in times) {
                    val instant = wallClock(day, time, zone).toInstant()
                    if (instant in from..until) {
                        out += occurrence(schedule, instant, day, time)
                    }
                }
            }
            day = day.plusDays(1)
        }
        return out
    }

    private fun tapering(
        schedule: Schedule,
        from: Instant,
        until: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        val steps = effectiveTaperSteps(schedule)
        if (steps.isEmpty()) return emptyList()
        val times = withOffset(schedule)
        val out = ArrayList<Occurrence>()
        var day = from.atZone(zone).toLocalDate().coerceAtLeast(schedule.startDate)
        val lastDay = until.atZone(zone).toLocalDate()
        while (!day.isAfter(lastDay)) {
            val step = stepForDay(schedule.startDate, steps, day) ?: break
            for (time in times) {
                val instant = wallClock(day, time, zone).toInstant()
                if (instant in from..until) {
                    out += occurrence(schedule, instant, day, time, dose = step.doseAmount)
                }
            }
            day = day.plusDays(1)
        }
        return out
    }

    fun effectiveTaperSteps(schedule: Schedule): List<TaperStep> =
        schedule.taperSteps.ifEmpty {
            listOf(TaperStep(schedule.doseAmount, Int.MAX_VALUE))
        }

    private fun stepForDay(startDate: LocalDate, steps: List<TaperStep>, day: LocalDate): TaperStep? {
        val dayIndex = ChronoUnit.DAYS.between(startDate, day).toInt()
        if (dayIndex < 0) return null
        var accumulated = 0
        for (step in steps) {
            val duration = if (step.durationDays <= 0) Int.MAX_VALUE else step.durationDays
            if (dayIndex < accumulated + duration) return step
            accumulated += duration
            if (accumulated > 100_000) return null
        }
        return null
    }

    fun doseOnDay(schedule: Schedule, date: LocalDate): BigDecimal? {
        if (schedule.scheduleType != ScheduleType.TAPERING) return schedule.doseAmount
        val steps = effectiveTaperSteps(schedule)
        if (date.isBefore(schedule.startDate)) return null
        return stepForDay(schedule.startDate, steps, date)?.doseAmount
    }

    private fun withOffset(schedule: Schedule): List<LocalTime> {
        val base = schedule.timesOfDay.ifEmpty {
            if (schedule.scheduleType == ScheduleType.INTERVAL_HOURS ||
                schedule.scheduleType == ScheduleType.INTERVAL_DAYS
            ) {
                emptyList()
            } else {
                listOf(LocalTime.of(8, 0))
            }
        }
        if (schedule.offsetMinutes == 0) return base
        return base.map { it.plusMinutes(schedule.offsetMinutes.toLong()) }
    }

    private fun matchesDayMask(schedule: Schedule, date: LocalDate): Boolean {
        val mask = schedule.daysOfWeekBitmask ?: return true
        if (mask == 0) return true
        val bit = 1 shl (date.dayOfWeek.value - 1)
        return mask and bit != 0
    }

    private fun wallClock(day: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(day, time, zone)

    internal fun anchorInstant(schedule: Schedule, zone: ZoneId): Instant {
        val time = schedule.timesOfDay.firstOrNull() ?: LocalTime.of(8, 0)
        val adjusted = time.plusMinutes(schedule.offsetMinutes.toLong())
        return ZonedDateTime.of(schedule.startDate, adjusted, zone).toInstant()
    }

    private fun occurrence(
        schedule: Schedule,
        at: Instant,
        date: LocalDate,
        time: LocalTime,
        dose: BigDecimal = schedule.doseAmount,
    ) = Occurrence(
        scheduleId = schedule.id,
        medicationId = schedule.medicationId,
        dueAt = at,
        localDate = date,
        localTime = time,
        doseAmount = dose,
        doseUnit = schedule.doseUnit,
    )

    private fun alignToSecond(instant: Instant): Instant =
        instant.truncatedTo(ChronoUnit.SECONDS)

    const val MAX_OCCURRENCES = 5000

    fun generatedStatusFor(scheduled: Instant, actual: Instant): DoseStatus {
        val lateBy = Duration.between(scheduled, actual)
        return if (lateBy > LATENESS_THRESHOLD) DoseStatus.LATE else DoseStatus.TAKEN
    }

    val LATENESS_THRESHOLD: Duration = Duration.ofMinutes(60)

    fun daysOfWeekMask(days: Set<DayOfWeek>): Int =
        days.fold(0) { acc, day -> acc or (1 shl (day.value - 1)) }

    fun daysFromMask(mask: Int): Set<DayOfWeek> =
        DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
}
