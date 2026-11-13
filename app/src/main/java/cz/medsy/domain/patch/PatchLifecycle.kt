package cz.medsy.domain.patch

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.PatchState
import cz.medsy.domain.model.Schedule
import java.time.Duration
import java.time.Instant

/**
 * Lifecycle of a transdermal patch: applied → worn → removal due → removed.
 *
 * A patch is not a single event like a tablet. Its effect depends on it *staying on*,
 * and forgetting to remove it means an unintended extra day of hormone. The state
 * therefore lives on the dose log, and the removal time is derived from the schedule's
 * wear window rather than being guessed.
 */
object PatchLifecycle {

    /** Default wear window when the schedule does not specify one. */
    val DEFAULT_WEAR_HOURS = 84

    /** How far ahead the removal alarm is placed. */
    fun wearWindow(schedule: Schedule): Duration {
        val hours = schedule.intervalValue?.toLong()?.takeIf { it in 1..(24L * 30) }
            ?: DEFAULT_WEAR_HOURS.toLong()
        return Duration.ofHours(hours)
    }

    fun wornUntil(appliedAt: Instant, schedule: Schedule): Instant =
        appliedAt.plus(wearWindow(schedule))

    /**
     * State for a freshly logged patch. Only an actually-applied patch enters the
     * lifecycle; a skipped dose never touches the skin.
     */
    fun onApplied(appliedAt: Instant, schedule: Schedule): PatchApplication =
        PatchApplication(
            state = PatchState.APPLIED,
            wornUntil = wornUntil(appliedAt, schedule),
        )

    data class PatchApplication(
        val state: PatchState,
        val wornUntil: Instant,
    )

    /**
     * Whether a reminder should tell the user to take the patch off now.
     *
     * A short grace period avoids nagging during the minute the alarm actually fires;
     * a long-past removal is still reported, because the patch is still on the skin.
     */
    fun removalState(wornUntil: Instant, now: Instant): PatchState {
        val due = !now.isBefore(wornUntil)
        return if (due) PatchState.REMOVAL_DUE else PatchState.WORN
    }

    /**
     * A patch whose wear window closed more than [STALE_AFTER] ago is almost certainly
     * already gone; asking about it again would be noise, so it is treated as removed.
     */
    val STALE_AFTER: Duration = Duration.ofHours(12)

    fun shouldAskForRemoval(wornUntil: Instant, now: Instant): Boolean {
        if (now.isBefore(wornUntil)) return false
        return Duration.between(wornUntil, now) <= STALE_AFTER
    }

    /** The next state after the user confirms removal. */
    fun onRemoved(): PatchState = PatchState.REMOVED

    fun describe(state: PatchState, wornUntil: Instant?, now: Instant): Message = when (state) {
        PatchState.APPLIED -> message(
            MessageKey.PATCH_APPLIED_UNTIL,
            wornUntil?.let { formatRemaining(it, now) } ?: message(MessageKey.DURATION_NOW),
        )

        PatchState.WORN -> {
            val left = wornUntil?.let { Duration.between(now, it) }
            if (left != null && !left.isNegative) {
                message(MessageKey.PATCH_WORN_REMOVE_IN, formatRemaining(wornUntil, now))
            } else {
                message(MessageKey.PATCH_WORN)
            }
        }

        PatchState.REMOVAL_DUE -> message(MessageKey.PATCH_REMOVAL_DUE)
        PatchState.REMOVED -> message(MessageKey.PATCH_REMOVED)
    }

    private fun formatRemaining(until: Instant, now: Instant): Message {
        val minutes = Duration.between(now, until).toMinutes()
        if (minutes <= 0) return message(MessageKey.DURATION_NOW)
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) {
            message(MessageKey.DURATION_HOURS_MINUTES, hours, rest)
        } else {
            message(MessageKey.DURATION_MINUTES, minutes)
        }
    }
}
