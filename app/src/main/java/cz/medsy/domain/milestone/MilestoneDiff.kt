package cz.medsy.domain.milestone

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.domain.model.ChangeType
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import java.math.BigDecimal

/**
 * Decides which milestones a schedule edit produces.
 *
 * A milestone is only worth recording when something clinically changed: a new regimen,
 * a different dose, a different frequency, a pause or a resume. Renaming a note or
 * toggling site rotation is not a milestone, and recording it would bury the real ones
 * under noise.
 */
object MilestoneDiff {

    data class Change(
        val changeType: ChangeType,
        val message: Message,
    )

    /**
     * @param previous the schedule as it was, or null when it is newly created
     * @param current the schedule after the edit
     */
    fun between(previous: Schedule?, current: Schedule): List<Change> {
        if (previous == null) {
            return listOf(Change(ChangeType.STARTED, message(MessageKey.MS_STARTED, describe(current))))
        }

        val changes = ArrayList<Change>(2)

        val doseChanged = previous.doseAmount.compareTo(current.doseAmount) != 0 ||
            !previous.doseUnit.equals(current.doseUnit, ignoreCase = true)
        if (doseChanged) {
            changes += Change(
                ChangeType.DOSE_CHANGED,
                message(MessageKey.MS_DOSE_CHANGED, doseText(previous), doseText(current)),
            )
        }

        val frequencyChanged = previous.scheduleType != current.scheduleType ||
            !sameInterval(previous, current) ||
            previous.timesOfDay != current.timesOfDay ||
            previous.taperSteps != current.taperSteps
        // A dose change already carries the description, so only report frequency when
        // the dose itself did not change.
        if (frequencyChanged && !doseChanged) {
            changes += Change(
                ChangeType.ROUTE_CHANGED,
                message(MessageKey.MS_SCHEDULE_CHANGED, describe(previous), describe(current)),
            )
        }

        if (previous.isActive && !current.isActive) {
            changes += Change(ChangeType.STOPPED, message(MessageKey.MS_PAUSED, describe(current)))
        } else if (!previous.isActive && current.isActive) {
            changes += Change(ChangeType.STARTED, message(MessageKey.MS_RESUMED, describe(current)))
        }

        return changes
    }

    fun stopped(schedule: Schedule): Change =
        Change(ChangeType.STOPPED, message(MessageKey.MS_STOPPED, describe(schedule)))

    private fun sameInterval(a: Schedule, b: Schedule): Boolean {
        val left = a.intervalValue
        val right = b.intervalValue
        return when {
            left == null && right == null -> true
            left == null || right == null -> false
            else -> left.compareTo(right) == 0
        }
    }

    /** Short human description, e.g. "5 mg every 3.5 days at 08:00". */
    fun describe(schedule: Schedule): Message {
        val dose = doseText(schedule)
        val recurrence = when (schedule.scheduleType) {
            ScheduleType.FIXED_DAILY -> message(MessageKey.SCHEDULE_DAILY)
            ScheduleType.INTERVAL_DAYS -> everyN(schedule, MessageKey.SCHEDULE_EVERY_DAYS)
            ScheduleType.INTERVAL_HOURS -> everyN(schedule, MessageKey.SCHEDULE_EVERY_HOURS)
            ScheduleType.CYCLICAL -> message(
                MessageKey.SCHEDULE_CYCLICAL,
                schedule.intervalValue?.stripTrailingZeros()?.toPlainString() ?: "21",
            )

            ScheduleType.TAPERING -> message(MessageKey.SCHEDULE_TAPERING)
            ScheduleType.PRN -> message(MessageKey.SCHEDULE_AS_NEEDED)
        }
        val times = schedule.timesOfDay.joinToString("/") { it.toString() }
        return if (times.isEmpty()) {
            message(MessageKey.SCHEDULE_SUMMARY, dose, recurrence)
        } else {
            message(MessageKey.SCHEDULE_SUMMARY_AT_TIMES, dose, recurrence, times)
        }
    }

    private fun doseText(schedule: Schedule): String =
        "${schedule.doseAmount.stripTrailingZeros().toPlainString()} ${schedule.doseUnit}"

    private fun everyN(schedule: Schedule, key: MessageKey): Message {
        val value = schedule.intervalValue?.stripTrailingZeros()
        val count = value?.toInt() ?: 0
        return messagePlural(key, count, value?.toPlainString() ?: "?")
    }
}
