package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.TroughAssessment
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Trough/peak reasoning for blood draws.
 *
 * A draw is a *true trough* when it happens in the window immediately before the next
 * scheduled dose. The window is a fraction of the dosing interval (default 20 %), with
 * an absolute floor so that daily dosing still counts a draw taken the next morning
 * before the tablet as a trough. Anything within [PEAK_WINDOW] of the last dose is a
 * peak sample.
 */
object BloodworkCalculator {

    const val TROUGH_FRACTION = 0.2
    val TROUGH_MIN_WINDOW: Duration = Duration.ofHours(6)
    val PEAK_WINDOW: Duration = Duration.ofHours(6)

    fun assess(
        drawTime: Instant,
        lastDoseTime: Instant?,
        nextDoseTime: Instant?,
        nominalIntervalHours: Double?,
    ): TroughAssessment {
        if (lastDoseTime == null) {
            return TroughAssessment(
                hoursSinceLastDose = null,
                isTrough = false,
                intervalHours = nominalIntervalHours,
                label = message(MessageKey.BLOODWORK_NO_PRIOR_DOSE),
            )
        }
        val elapsed = Duration.between(lastDoseTime, drawTime)
        val hoursElapsed = elapsed.toMinutes() / 60.0

        if (elapsed.isNegative) {
            return TroughAssessment(
                hoursSinceLastDose = hoursElapsed,
                isTrough = false,
                intervalHours = nominalIntervalHours,
                label = message(MessageKey.BLOODWORK_DRAW_BEFORE_DOSE),
            )
        }

        val window = troughWindow(nominalIntervalHours)
        val timeToNext = nextDoseTime?.let { Duration.between(drawTime, it) }

        val inTroughByNext = timeToNext != null && !timeToNext.isNegative && timeToNext <= window
        val inTroughByElapsed = timeToNext == null && nominalIntervalHours != null &&
            hoursElapsed >= nominalIntervalHours * (1.0 - TROUGH_FRACTION)

        if (inTroughByNext || inTroughByElapsed) {
            return TroughAssessment(
                hoursSinceLastDose = hoursElapsed,
                isTrough = true,
                intervalHours = nominalIntervalHours,
                label = message(MessageKey.BLOODWORK_TRUE_TROUGH, formatHours(hoursElapsed)),
            )
        }

        if (elapsed <= PEAK_WINDOW) {
            return TroughAssessment(
                hoursSinceLastDose = hoursElapsed,
                isTrough = false,
                intervalHours = nominalIntervalHours,
                label = message(MessageKey.BLOODWORK_NEAR_PEAK, formatHours(hoursElapsed)),
            )
        }

        if (nominalIntervalHours != null && nominalIntervalHours > 0) {
            val fraction = (hoursElapsed / nominalIntervalHours * 100).toInt()
            return TroughAssessment(
                hoursSinceLastDose = hoursElapsed,
                isTrough = false,
                intervalHours = nominalIntervalHours,
                label = message(
                    MessageKey.BLOODWORK_MID_CYCLE,
                    formatHours(hoursElapsed),
                    fraction,
                    formatHours(nominalIntervalHours),
                ),
            )
        }

        return TroughAssessment(
            hoursSinceLastDose = hoursElapsed,
            isTrough = false,
            intervalHours = null,
            label = message(MessageKey.BLOODWORK_DRAWN_AFTER_DOSE, formatHours(hoursElapsed)),
        )
    }

    fun troughWindow(nominalIntervalHours: Double?): Duration {
        if (nominalIntervalHours == null || nominalIntervalHours <= 0) return TROUGH_MIN_WINDOW
        val fractionWindow = Duration.ofMinutes((nominalIntervalHours * 60 * TROUGH_FRACTION).toLong())
        return if (fractionWindow > TROUGH_MIN_WINDOW) fractionWindow else TROUGH_MIN_WINDOW
    }

    fun nominalIntervalHours(schedules: List<Schedule>): Double? {
        val intervals = schedules.mapNotNull { schedule ->
            when (schedule.scheduleType) {
                cz.medsy.domain.model.ScheduleType.FIXED_DAILY -> 24.0
                cz.medsy.domain.model.ScheduleType.INTERVAL_HOURS -> schedule.intervalValue?.toDouble()
                cz.medsy.domain.model.ScheduleType.INTERVAL_DAYS -> schedule.intervalValue?.toDouble()?.times(24.0)
                cz.medsy.domain.model.ScheduleType.CYCLICAL -> 24.0
                cz.medsy.domain.model.ScheduleType.TAPERING -> 24.0
                cz.medsy.domain.model.ScheduleType.PRN -> null
            }
        }.filter { it > 0 }
        return intervals.maxOrNull()
    }

    fun formatHours(hours: Double): String {
        if (hours.isNaN() || hours < 0) return "—"
        val whole = hours.toInt()
        val minutes = ((hours - whole) * 60).toInt()
        return if (hours < 1) {
            String.format(Locale.ROOT, "%d min", (hours * 60).toInt())
        } else {
            String.format(Locale.ROOT, "%d h %02d min", whole, minutes)
        }
    }

    /**
     * Morning blood draws conflict with doses that must be taken at the same time.
     * Returns the warnings the user should see before venipuncture.
     */
    fun preTestWarnings(
        schedules: List<Schedule>,
        drawHourOfDay: Int,
    ): List<PreTestWarning> {
        val warnings = ArrayList<PreTestWarning>()
        val morning = schedules.filter { schedule ->
            schedule.timesOfDay.any { it.hour <= drawHourOfDay && it.hour >= drawHourOfDay - 4 }
        }
        if (morning.isEmpty()) return warnings

        val mustHold = morning.filter { schedule ->
            schedule.mealRelation != MealRelation.WITH_MEAL &&
                (schedule.doseUnit.lowercase() in setOf("mg", "µg", "mcg", "iu", "units", "tbl"))
        }
        if (mustHold.isNotEmpty()) {
            warnings += PreTestWarning(
                title = message(MessageKey.BLOODWORK_HOLD_MORNING_TITLE),
                detail = message(MessageKey.BLOODWORK_HOLD_MORNING_DETAIL),
            )
        }
        warnings += PreTestWarning(
            title = message(MessageKey.BLOODWORK_FAST_TITLE),
            detail = message(MessageKey.BLOODWORK_FAST_DETAIL),
        )
        warnings += PreTestWarning(
            title = message(MessageKey.BLOODWORK_BIOTIN_TITLE),
            detail = message(MessageKey.BLOODWORK_BIOTIN_DETAIL),
        )
        return warnings
    }

    data class PreTestWarning(
        val title: Message,
        val detail: Message,
    )
}
