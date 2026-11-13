package cz.medsy.notification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.message.Message
import cz.medsy.ui.message.asText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single place that keeps armed alarms in sync with stored schedules and settings.
 *
 * Previously alarms were only armed by the boot receiver, so a schedule created in
 * the app produced no reminders until the next reboot. Everything that mutates a
 * schedule or the reminder settings must call [refresh] (or go through this class).
 */
@Singleton
class ReminderCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: DoseAlarmScheduler,
    private val medicationRepository: MedicationRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: DoseNotifier,
    private val prescriptionRepository: PrescriptionRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Call once from Application.onCreate: arms what already exists and keeps it fresh. */
    fun start() {
        scope.launch {
            refresh()
            // Re-arm whenever the user flips a reminder setting.
            settingsRepository.settings
                .drop(1)
                .collect { refresh() }
        }
    }

    suspend fun refresh() {
        val settings = settingsRepository.settings.first()
        val schedules = medicationRepository.activeSchedules()
        if (!settings.remindersEnabled) {
            scheduler.sync(emptyList())
            return
        }
        val adjusted = applyAbsorptionOffsets(schedules)
        scheduler.sync(adjusted, leadMinutes = settings.reminderLeadMinutes)
        notifyExpiringPrescriptions(settings)
    }

    /**
     * A daily check on app start instead of another alarm: two days of warning is enough to
     * reach a pharmacy, and it keeps the alarm table to doses only.
     */
    private suspend fun notifyExpiringPrescriptions(settings: cz.medsy.data.settings.AppSettings) {
        val items = runCatching { prescriptionRepository.active() }.getOrDefault(emptyList())
        val message = cz.medsy.domain.prescription.PrescriptionQueue
            .reminderMessage(items, Instant.now()) ?: return
        notifier.notifyStock(
            PRESCRIPTION_NOTIFICATION_ID,
            message.asText(context),
            settings.discreetMode,
        )
    }

    private companion object {
        const val PRESCRIPTION_NOTIFICATION_ID = 9_001
    }

    /**
     * Moves a reminder when two medications must not be taken together, and reports what
     * was moved. Persisting the offset (rather than only shifting the alarm) keeps the
     * schedule list honest about when the dose is actually due.
     */
    private suspend fun applyAbsorptionOffsets(
        schedules: List<cz.medsy.domain.model.Schedule>,
    ): List<cz.medsy.domain.model.Schedule> {
        val medications = schedules.map { schedule ->
            schedule.medicationId
        }.distinct().mapNotNull { id -> medicationRepository.getById(id) }

        val substances = medications.map { medication ->
            cz.medsy.domain.calc.InteractionEngine.ActiveSubstance(
                name = medication.activeSubstance.orEmpty(),
                atcCode = medication.atcCode,
                medicationId = medication.id,
            )
        }
        val conflicts = cz.medsy.domain.calc.InteractionEngine.absorptionConflicts(substances)
        if (conflicts.isEmpty()) return schedules

        val outcome = cz.medsy.domain.schedule.AbsorptionOffsetPlanner.plan(schedules, conflicts)
        if (outcome.plans.isEmpty()) return schedules

        val byId = schedules.associateBy { it.id }
        outcome.plans.forEach { plan ->
            val schedule = byId[plan.scheduleId] ?: return@forEach
            runCatching {
                medicationRepository.saveSchedule(
                    schedule.copy(offsetMinutes = plan.toOffsetMinutes),
                )
            }
            lastOffsetNotices += OffsetNotice(
                scheduleId = plan.scheduleId,
                message = plan.explanation,
            )
        }

        // Re-read so the caller arms the shifted times, not the old ones.
        return medicationRepository.activeSchedules()
    }

    data class OffsetNotice(val scheduleId: Long, val message: Message)

    private val lastOffsetNotices = java.util.Collections.synchronizedList(mutableListOf<OffsetNotice>())

    /** Offsets applied by the last [refresh]; the UI surfaces these once. */
    fun takeOffsetNotices(): List<OffsetNotice> {
        synchronized(lastOffsetNotices) {
            val copy = lastOffsetNotices.toList()
            lastOffsetNotices.clear()
            return copy
        }
    }

    /**
     * Re-syncs after a single schedule is edited. A full sync is used deliberately:
     * it also cancels alarms of schedules that were deleted or paused, which a
     * per-schedule refresh would leave armed.
     */
    suspend fun refreshSchedule(@Suppress("UNUSED_PARAMETER") scheduleId: Long) = refresh()

    fun sendTestNotification() {
        scope.launch {
            val settings = settingsRepository.settings.first()
            notifier.notifyTest(
                discreet = settings.discreetMode,
                soundEnabled = settings.reminderSound,
                vibrationEnabled = settings.reminderVibration,
            )
        }
    }

    /** Number of schedules that currently have a reminder armed. */
    suspend fun armedCount(): Int = medicationRepository.activeSchedules().count { it.isActive }

    fun notificationsBlocked(): Boolean = !notifier.canPostNotifications()

    fun exactAlarmsAllowed(): Boolean = scheduler.canScheduleExactAlarms()

    /**
     * Android 14+ withholds USE_FULL_SCREEN_INTENT from apps it does not classify as alarm or
     * calling apps, so the lock-screen overlay has to be granted by the user in system settings.
     */
    fun fullScreenAllowed(): Boolean = notifier.canUseFullScreenIntent()

    /** Opens the "allow full-screen notifications" switch for this app (Android 14+). */
    fun openFullScreenIntentSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the system screen where POST_NOTIFICATIONS / exact alarms can be granted. */
    fun openPermissionSettings(exactAlarms: Boolean) {
        val intent = if (exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}