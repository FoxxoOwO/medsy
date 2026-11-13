package cz.medsy.notification

import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.repository.PrnLimitExceeded
import cz.medsy.domain.message.Message
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.schedule.Occurrence
import cz.medsy.domain.schedule.ScheduleEngine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Take / Skip / Snooze actions in one place.
 *
 * They used to be inline in [DoseActionReceiver]; the full-screen lock-screen alert needs
 * exactly the same three actions, and a second copy would drift (the notification path
 * still used a hard-coded 15 minutes after the snooze length became a setting).
 */
@Singleton
class DoseActionHandler @Inject constructor(
    private val doseRepository: DoseRepository,
    private val medicationRepository: MedicationRepository,
    private val notifier: DoseNotifier,
    private val scheduler: DoseAlarmScheduler,
) {

    enum class Action { TAKE, SKIP, SNOOZE }

    sealed interface Result {
        /** The action was applied and the reminder notification is gone. */
        data object Done : Result

        /** The schedule disappeared while the reminder was on screen. */
        data object NotFound : Result

        /**
         * A PRN ceiling refused the dose. Deliberately nothing was written: silently
         * exceeding the limit from a lock-screen button would defeat the limit entirely.
         */
        data class LimitBlocked(val message: Message?) : Result
    }

    suspend fun perform(
        action: Action,
        medicationId: Long,
        scheduleId: Long,
        dueAt: Long,
        snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    ): Result {
        if (medicationId <= 0 || scheduleId <= 0 || dueAt <= 0L) return Result.NotFound
        val schedule = medicationRepository.observeSchedules(medicationId).first()
            .firstOrNull { it.id == scheduleId } ?: return Result.NotFound

        val dueInstant = Instant.ofEpochMilli(dueAt)
        val occurrence = occurrenceOf(schedule, dueInstant)
        val notificationId = DoseAlarmReceiver.notificationId(scheduleId, dueAt)

        when (action) {
            Action.TAKE -> {
                val status = ScheduleEngine.generatedStatusFor(dueInstant, Instant.now())
                try {
                    doseRepository.log(occurrence, status)
                } catch (error: PrnLimitExceeded) {
                    // The notification stays: the user still has to resolve it in the app.
                    return Result.LimitBlocked(error.limitMessage)
                }
                notifier.cancel(notificationId)
            }

            Action.SKIP -> {
                doseRepository.log(occurrence, DoseStatus.SKIPPED)
                notifier.cancel(notificationId)
            }

            Action.SNOOZE -> {
                val until = Instant.now().plusSeconds(snoozeMinutes.coerceIn(5, 120) * 60L)
                doseRepository.snooze(medicationId, scheduleId, dueInstant, until)
                notifier.cancel(notificationId)
                scheduler.scheduleSnooze(schedule, occurrence, until)
            }
        }
        return Result.Done
    }

    private fun occurrenceOf(schedule: Schedule, dueInstant: Instant): Occurrence {
        val zoned = dueInstant.atZone(ZoneId.systemDefault())
        return Occurrence(
            scheduleId = schedule.id,
            medicationId = schedule.medicationId,
            dueAt = dueInstant,
            localDate = zoned.toLocalDate(),
            localTime = zoned.toLocalTime(),
            doseAmount = schedule.doseAmount,
            doseUnit = schedule.doseUnit,
        )
    }

    companion object {
        const val DEFAULT_SNOOZE_MINUTES = 15
    }
}
