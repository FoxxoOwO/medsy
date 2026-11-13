package cz.medsy.notification

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.schedule.ScheduleEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class DoseAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var doseRepository: DoseRepository
    @Inject lateinit var medicationRepository: MedicationRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var notifier: DoseNotifier
    @Inject lateinit var scheduler: DoseAlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DOSE_ALARM) return
        val medicationId = intent.getLongExtra(EXTRA_MEDICATION_ID, -1L)
        val scheduleId = intent.getLongExtra(EXTRA_SCHEDULE_ID, -1L)
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, 0L)
        if (medicationId <= 0 || scheduleId <= 0 || dueAt <= 0L) return
        val isSnooze = intent.getBooleanExtra(EXTRA_IS_SNOOZE, false)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val medication = medicationRepository.getById(medicationId)
                val schedule = medicationRepository.observeSchedules(medicationId).first()
                    .firstOrNull { it.id == scheduleId }
                if (medication == null || schedule == null) return@launch
                if (!schedule.isActive) {
                    // The schedule was paused/deleted while the alarm was armed.
                    scheduler.cancel(scheduleId)
                    return@launch
                }

                val settings = settingsRepository.settings.first()
                if (!settings.remindersEnabled) return@launch

                val dueInstant = Instant.ofEpochMilli(dueAt)
                val now = Instant.now()

                // Staleness guard: an alarm armed before a long period without the app
                // opening must not announce a dose from days ago.
                if (!isSnooze && Duration.between(dueInstant, now) > MAX_STALENESS) {
                    armFollowing(schedule, now, settings.reminderLeadMinutes)
                    return@launch
                }

                notifier.notifyDose(
                    notificationId = notificationId(scheduleId, dueAt),
                    medicationId = medicationId,
                    scheduleId = scheduleId,
                    medicationName = medication.name,
                    doseText = "${schedule.doseAmount.stripTrailingZeros().toPlainString()} ${schedule.doseUnit}",
                    timeText = dueInstant.atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("HH:mm")),
                    discreet = settings.discreetMode,
                    dueAt = dueAt,
                    soundEnabled = settings.reminderSound,
                    vibrationEnabled = settings.reminderVibration,
                    fullScreenAlert = cz.medsy.domain.alert.LockScreenAlert.allowed(
                        settingEnabled = settings.fullScreenReminders,
                        remindersEnabled = settings.remindersEnabled,
                        permissionGranted = notifier.canUseFullScreenIntent(),
                    ),
                    snoozeMinutes = settings.snoozeMinutes,
                )

                // A snooze must not disturb the regular cadence: only the regular alarm
                // arms the following occurrence.
                if (!isSnooze) armFollowing(schedule, now, settings.reminderLeadMinutes)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Arms the following occurrence, preserving the user's "alert before" setting.
     * Dropping it here made the lead time apply only to the first alarm after a sync.
     */
    private suspend fun armFollowing(schedule: Schedule, now: Instant, leadMinutes: Int) {
        val next = ScheduleEngine.nextOccurrence(schedule, now, ZoneId.systemDefault())
        if (next != null) {
            scheduler.schedule(schedule, next, ZoneId.systemDefault(), leadMinutes)
        }
    }

    companion object {
        const val ACTION_DOSE_ALARM = "cz.medsy.action.DOSE_ALARM"
        const val EXTRA_MEDICATION_ID = "medication_id"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_DUE_AT = "due_at"
        const val EXTRA_DOSE_AMOUNT = "dose_amount"
        const val EXTRA_DOSE_UNIT = "dose_unit"
        const val EXTRA_IS_SNOOZE = "is_snooze"

        /** Alarms older than this are considered missed rather than "due now". */
        val MAX_STALENESS: Duration = Duration.ofHours(6)

        fun notificationId(scheduleId: Long, dueAt: Long): Int =
            ((scheduleId.hashCode() * 31) xor (dueAt / 60_000L).hashCode()) and 0x7FFFFFFF
    }
}

@AndroidEntryPoint
class DoseActionReceiver : BroadcastReceiver() {

    @Inject lateinit var actionHandler: DoseActionHandler
    @Inject lateinit var notifier: DoseNotifier

    override fun onReceive(context: Context, intent: Intent) {
        val medicationId = intent.getLongExtra(DoseAlarmReceiver.EXTRA_MEDICATION_ID, -1L)
        val scheduleId = intent.getLongExtra(DoseAlarmReceiver.EXTRA_SCHEDULE_ID, -1L)
        val dueAt = intent.getLongExtra(DoseAlarmReceiver.EXTRA_DUE_AT, 0L)
        if (medicationId <= 0 || scheduleId <= 0 || dueAt <= 0L) return

        val action = when (intent.action) {
            ACTION_QUICK_TAKE -> DoseActionHandler.Action.TAKE
            ACTION_QUICK_SKIP -> DoseActionHandler.Action.SKIP
            ACTION_SNOOZE -> DoseActionHandler.Action.SNOOZE
            else -> return
        }
        val snoozeMinutes = intent.getIntExtra(
            EXTRA_SNOOZE_MINUTES,
            DoseActionHandler.DEFAULT_SNOOZE_MINUTES,
        )

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = actionHandler.perform(
                    action = action,
                    medicationId = medicationId,
                    scheduleId = scheduleId,
                    dueAt = dueAt,
                    snoozeMinutes = snoozeMinutes,
                )
                // A PRN ceiling refused the dose: the notification is still there, so the
                // user needs to be told why the button appeared to do nothing.
                if (result is DoseActionHandler.Result.LimitBlocked) {
                    notifier.notifyLimitBlocked(scheduleId, result.message)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_QUICK_TAKE = "cz.medsy.action.QUICK_TAKE"
        const val ACTION_QUICK_SKIP = "cz.medsy.action.QUICK_SKIP"
        const val ACTION_SNOOZE = "cz.medsy.action.SNOOZE"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"

        fun takeIntent(
            context: Context,
            medicationId: Long,
            scheduleId: Long,
            dueAt: Long,
        ): Intent = actionIntent(context, ACTION_QUICK_TAKE, medicationId, scheduleId, dueAt)

        fun skipIntent(
            context: Context,
            medicationId: Long,
            scheduleId: Long,
            dueAt: Long,
        ): Intent = actionIntent(context, ACTION_QUICK_SKIP, medicationId, scheduleId, dueAt)

        /** The snooze length travels with the action, so the button matches the setting. */
        fun snoozeIntent(
            context: Context,
            medicationId: Long,
            scheduleId: Long,
            dueAt: Long,
            snoozeMinutes: Int = DoseActionHandler.DEFAULT_SNOOZE_MINUTES,
        ): Intent = actionIntent(context, ACTION_SNOOZE, medicationId, scheduleId, dueAt).apply {
            putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
        }

        private fun actionIntent(
            context: Context,
            action: String,
            medicationId: Long,
            scheduleId: Long,
            dueAt: Long,
        ): Intent = Intent(context, DoseActionReceiver::class.java).apply {
            this.action = action
            putExtra(DoseAlarmReceiver.EXTRA_MEDICATION_ID, medicationId)
            putExtra(DoseAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(DoseAlarmReceiver.EXTRA_DUE_AT, dueAt)
        }
    }
}

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var medicationRepository: MedicationRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var scheduler: DoseAlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                val schedules = medicationRepository.activeSchedules()
                // On timeout/timezone changes the wall clock moved, so every alarm is
                // re-armed from the new zone. Keeping the same schedules is correct.
                scheduler.sync(
                    schedules = if (settings.remindersEnabled) schedules else emptyList(),
                    leadMinutes = settings.reminderLeadMinutes,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
