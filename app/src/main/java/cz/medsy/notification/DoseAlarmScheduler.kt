package cz.medsy.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.schedule.Occurrence
import cz.medsy.domain.schedule.ScheduleEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exact alarms via [AlarmManager.setExactAndAllowWhileIdle], which is the only API
 * that fires at the precise minute on Doze-restricted Android 6+ devices.
 *
 * Design: exactly **one** pending alarm per schedule (request code derived from the
 * schedule id). When it fires, the receiver notifies and arms the next occurrence.
 * This makes cancellation reliable — a deleted or paused schedule can always be
 * cancelled without knowing which occurrence was armed, which was impossible when
 * every occurrence had its own request code.
 */
@Singleton
class DoseAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
) {

    /**
     * The clock, so a test can stand at a fixed minute. The "never arm in the past" guard below
     * makes every assertion about an armed alarm depend on the wall clock, which quietly broke a
     * test every evening between 19:45 and 20:00 (the lead time had already passed).
     */
    internal var now: () -> Instant = Instant::now

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    /**
     * Brings the armed alarms in line with [schedules]: arms the next occurrence for
     * every active schedule and cancels alarms of schedules that are paused or gone.
     * Safe to call on every app start, after every edit and after boot.
     */
    fun sync(
        schedules: List<Schedule>,
        zone: ZoneId = ZoneId.systemDefault(),
        leadMinutes: Int = 0,
    ) {
        for (schedule in schedules) {
            if (schedule.isActive) {
                markActive(schedule.id)
            } else {
                cancel(schedule.id)
                unmarkActive(schedule.id)
            }
        }
        // Cancel schedules that no longer exist at all.
        val ids = schedules.map { it.id }.toSet()
        for (orphan in knownActiveIds() - ids) {
            cancel(orphan)
            unmarkActive(orphan)
        }
        armNext(schedules.filter { it.isActive }, zone, leadMinutes)
    }

    fun rescheduleAll(schedules: List<Schedule>, zone: ZoneId = ZoneId.systemDefault()) {
        sync(schedules, zone)
    }

    fun armNext(
        schedules: List<Schedule>,
        zone: ZoneId = ZoneId.systemDefault(),
        leadMinutes: Int = 0,
    ) {
        val now = now()
        for (schedule in schedules) {
            if (!schedule.isActive) continue
            val next = ScheduleEngine.nextOccurrence(schedule, now, zone) ?: continue
            schedule(schedule, next, zone, leadMinutes)
        }
    }

    fun schedule(schedule: Schedule, occurrence: Occurrence, zone: ZoneId, leadMinutes: Int = 0) {
        val fireAt = occurrence.dueAt.minus(Duration.ofMinutes(leadMinutes.toLong()))
        // Never arm in the past; a missed occurrence is handled by the staleness guard
        // in the receiver, not by firing immediately.
        if (fireAt.isBefore(now().minusSeconds(60))) return

        val pending = pendingIntent(schedule, occurrence)
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireAt.toEpochMilli(),
                    pending,
                )
            } else {
                Log.w(TAG, "Exact alarms not permitted; falling back to inexact delivery")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireAt.toEpochMilli(),
                    pending,
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not schedule alarm for schedule ${schedule.id}", e)
        }
    }

    fun scheduleSnooze(
        schedule: Schedule,
        occurrence: Occurrence,
        until: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val intent = baseIntent(schedule, occurrence).apply {
            putExtra(DoseAlarmReceiver.EXTRA_IS_SNOOZE, true)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            snoozeRequestCode(schedule.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        armAt(until.toEpochMilli(), pending)
    }

    /** Cancels the regular alarm and any pending snooze for [scheduleId]. */
    fun cancel(scheduleId: Long) {
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = DoseAlarmReceiver.ACTION_DOSE_ALARM
            putExtra(DoseAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
        }
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context,
                requestCode(scheduleId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context,
                snoozeRequestCode(scheduleId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun armAt(triggerAtMillis: Long, pending: PendingIntent) {
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not arm alarm", e)
        }
    }

    private fun baseIntent(schedule: Schedule, occurrence: Occurrence): Intent =
        Intent(context, DoseAlarmReceiver::class.java).apply {
            action = DoseAlarmReceiver.ACTION_DOSE_ALARM
            putExtra(DoseAlarmReceiver.EXTRA_MEDICATION_ID, occurrence.medicationId)
            putExtra(DoseAlarmReceiver.EXTRA_SCHEDULE_ID, occurrence.scheduleId)
            putExtra(DoseAlarmReceiver.EXTRA_DUE_AT, occurrence.dueAt.toEpochMilli())
            putExtra(DoseAlarmReceiver.EXTRA_DOSE_AMOUNT, occurrence.doseAmount.toDouble())
            putExtra(DoseAlarmReceiver.EXTRA_DOSE_UNIT, occurrence.doseUnit)
        }

    private fun pendingIntent(schedule: Schedule, occurrence: Occurrence): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(schedule.id),
            baseIntent(schedule, occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * One request code per schedule. Kept in a distinct numeric range from
     * [snoozeRequestCode] so the two alarms never collide.
     */
    private fun requestCode(scheduleId: Long): Int =
        (scheduleId and 0x3FFF).toInt() + REQUEST_BASE

    private fun snoozeRequestCode(scheduleId: Long): Int =
        (scheduleId and 0x3FFF).toInt() + SNOOZE_BASE

    // Tracks which schedules currently own an armed alarm so orphans can be cancelled.
    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun markActive(scheduleId: Long) {
        prefs().edit().putBoolean(key(scheduleId), true).apply()
    }

    private fun unmarkActive(scheduleId: Long) {
        prefs().edit().remove(key(scheduleId)).apply()
    }

    private fun knownActiveIds(): Set<Long> =
        prefs().all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { it.removePrefix(KEY_PREFIX).toLongOrNull() }
            .toSet()

    private fun key(scheduleId: Long) = "$KEY_PREFIX$scheduleId"

    private companion object {
        const val TAG = "DoseAlarmScheduler"
        const val REQUEST_BASE = 1000
        const val SNOOZE_BASE = 100_000
        const val PREFS = "medsy_armed_alarms"
        const val KEY_PREFIX = "schedule_"
    }
}
