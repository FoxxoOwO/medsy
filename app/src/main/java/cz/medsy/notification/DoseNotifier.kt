package cz.medsy.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.medsy.MainActivity
import cz.medsy.R
import cz.medsy.ui.message.asText
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoseNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Whether the app may launch an activity over the lock screen from a notification.
     * Android 14+ revokes USE_FULL_SCREEN_INTENT from everything that is not an alarm or
     * calling app, so asking unconditionally would silently downgrade the alert.
     */
    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)
    }

    /**
     * Posting is guarded by [canPostNotifications] and wrapped in `runCatching`, because the
     * permission can be revoked between the check and the call; lint cannot see through that,
     * so the four posting helpers are annotated instead of repeating the check inline.
     */
    @SuppressLint("MissingPermission")
    fun notifyDose(
        notificationId: Int,
        medicationId: Long,
        scheduleId: Long,
        medicationName: String,
        doseText: String,
        timeText: String,
        discreet: Boolean,
        dueAt: Long,
        soundEnabled: Boolean = true,
        vibrationEnabled: Boolean = true,
        /** Show [DoseAlarmActivity] over the lock screen instead of just a heads-up. */
        fullScreenAlert: Boolean = false,
        snoozeMinutes: Int = DoseActionHandler.DEFAULT_SNOOZE_MINUTES,
    ) {
        // Without the runtime permission (API 33+) notify() is silently dropped, so
        // reporting it as "sent" would be a lie in the UI.
        if (!canPostNotifications()) return

        val title = if (discreet) {
            context.getString(R.string.notification_dose_title_discreet)
        } else {
            context.getString(R.string.notification_dose_title, medicationName)
        }
        val text = if (discreet) {
            context.getString(R.string.notification_dose_text_discreet)
        } else {
            context.getString(R.string.notification_dose_text, doseText, timeText)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val channel = NotificationChannels.doseChannel(soundEnabled, vibrationEnabled)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPending)
            .addAction(
                0,
                context.getString(R.string.dose_take),
                action(DoseActionReceiver.takeIntent(context, medicationId, scheduleId, dueAt), notificationId * 10 + 1),
            )
            .addAction(
                0,
                context.getString(R.string.dose_snooze),
                action(
                    DoseActionReceiver.snoozeIntent(
                        context,
                        medicationId,
                        scheduleId,
                        dueAt,
                        snoozeMinutes,
                    ),
                    notificationId * 10 + 2,
                ),
            )
            .addAction(
                0,
                context.getString(R.string.dose_skip),
                action(DoseActionReceiver.skipIntent(context, medicationId, scheduleId, dueAt), notificationId * 10 + 3),
            )

        if (fullScreenAlert) {
            val alertIntent = DoseAlarmActivity.intent(context, medicationId, scheduleId, dueAt)
            val alertPending = PendingIntent.getActivity(
                context,
                notificationId,
                alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(alertPending, true)
        }

        val notification = builder.build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyTest(discreet: Boolean, soundEnabled: Boolean, vibrationEnabled: Boolean = true) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(
            context,
            NotificationChannels.doseChannel(soundEnabled, vibrationEnabled),
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (discreet) {
                    context.getString(R.string.notification_test_title_discreet)
                } else {
                    context.getString(R.string.notification_test_title)
                },
            )
            .setContentText(context.getString(R.string.notification_test_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(TEST_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyStock(notificationId: Int, message: String, discreet: Boolean) {
        if (!canPostNotifications()) return
        val text = if (discreet) {
            context.getString(R.string.notification_low_stock_discreet)
        } else {
            message
        }
        val notification = NotificationCompat.Builder(context, NotificationChannels.INVENTORY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.channel_inventory))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    /**
     * Tells the user their PRN limit stopped a notification-confirmed dose. The dose is
     * deliberately not written: silently exceeding the ceiling from a lock-screen button
     * would defeat the limit entirely.
     */
    @SuppressLint("MissingPermission")
    fun notifyLimitBlocked(scheduleId: Long, message: cz.medsy.domain.message.Message?) {
        if (!canPostNotifications()) return
        val reason = message?.asText(context) ?: context.getString(R.string.msg_notify_limit_default)
        val notification = NotificationCompat.Builder(context, NotificationChannels.DOSES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.msg_notify_limit_title))
            .setContentText(reason)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.msg_notify_limit_message, reason),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(
                (LIMIT_NOTIFICATION_BASE + scheduleId).toInt(),
                notification,
            )
        }
    }

    fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun action(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val TEST_NOTIFICATION_ID = 7777
        const val LIMIT_NOTIFICATION_BASE = 9000L
    }
}
