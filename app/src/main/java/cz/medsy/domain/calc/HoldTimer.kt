package cz.medsy.domain.calc

import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import java.time.Duration
import java.time.Instant

/**
 * Hold timer for sublingual/buccal doses.
 *
 * The whole point of these forms is that the tablet dissolves under the tongue rather
 * than being swallowed, so the patient has to keep it there for a defined time. The
 * timer is advisory: it must never block logging the dose, and it must survive the
 * screen being locked or the app being backgrounded (the UI reconstructs the remaining
 * time from a stored start instant rather than counting callbacks).
 */
object HoldTimer {

    val DEFAULT_HOLD_MINUTES = 10
    private const val MAX_REASONABLE_HOLD_MINUTES = 60

    fun appliesTo(form: MedicationForm): Boolean = form == MedicationForm.SUBLINGUAL

    /** Configured hold, falling back to [DEFAULT_HOLD_MINUTES]. */
    fun holdDuration(schedule: Schedule?): Duration {
        val minutes = schedule?.holdMinutes
            ?.takeIf { it in 1..MAX_REASONABLE_HOLD_MINUTES }
            ?: DEFAULT_HOLD_MINUTES
        return Duration.ofMinutes(minutes.toLong())
    }

    fun endsAt(startedAt: Instant, schedule: Schedule?): Instant =
        startedAt.plus(holdDuration(schedule))

    /**
     * Remaining time, never negative. Computing it from the start instant (rather than
     * decrementing a counter) is what makes the timer correct after a process death or
     * a long screen-off period.
     */
    fun remaining(startedAt: Instant, schedule: Schedule?, now: Instant = Instant.now()): Duration {
        val left = Duration.between(now, endsAt(startedAt, schedule))
        return if (left.isNegative) Duration.ZERO else left
    }

    fun isFinished(startedAt: Instant, schedule: Schedule?, now: Instant = Instant.now()): Boolean =
        !now.isBefore(endsAt(startedAt, schedule))

    /** 0.0 at the start, 1.0 when done; used to drive the progress ring. */
    fun progress(startedAt: Instant, schedule: Schedule?, now: Instant = Instant.now()): Float {
        val total = holdDuration(schedule).toMillis().toDouble()
        if (total <= 0.0) return 1f
        val elapsed = Duration.between(startedAt, now).toMillis().toDouble()
        return (elapsed / total).coerceIn(0.0, 1.0).toFloat()
    }

    /** "7:30" style label, so the UI never has to format durations itself. */
    fun formatRemaining(remaining: Duration): String {
        val totalSeconds = remaining.seconds.coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        } else {
            "0:${seconds.toString().padStart(2, '0')}"
        }
    }
}
