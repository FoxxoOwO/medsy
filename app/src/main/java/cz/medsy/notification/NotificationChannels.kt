package cz.medsy.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Dose reminders use one of four channels because sound and vibration are channel
 * properties on Android 8+; they cannot be changed per notification. Having a
 * channel per combination keeps the sound/vibration toggles honest.
 */
object NotificationChannels {

    const val DOSES = "doses"
    const val QUIET_DOSES = "doses_quiet"
    const val SILENT_DOSES = "doses_silent"
    const val VIBRATE_ONLY_DOSES = "doses_vibrate_only"
    const val INVENTORY = "inventory"
    const val BLOODWORK = "bloodwork"

    fun doseChannel(sound: Boolean, vibration: Boolean): String = when {
        sound && vibration -> DOSES
        sound && !vibration -> QUIET_DOSES
        !sound && vibration -> VIBRATE_ONLY_DOSES
        else -> SILENT_DOSES
    }

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val doseName = context.getString(cz.medsy.R.string.channel_doses)
        val doseDescription = context.getString(cz.medsy.R.string.channel_doses_desc)

        fun doseChannel(id: String, sound: Boolean, vibration: Boolean) =
            NotificationChannel(
                id,
                doseName,
                if (sound || vibration) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_DEFAULT
                },
            ).apply {
                description = doseDescription
                enableVibration(vibration)
                if (!sound) setSound(null, null)
                setShowBadge(true)
            }

        val inventory = NotificationChannel(
            INVENTORY,
            context.getString(cz.medsy.R.string.channel_inventory),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(cz.medsy.R.string.channel_inventory_desc)
        }

        val bloodwork = NotificationChannel(
            BLOODWORK,
            context.getString(cz.medsy.R.string.channel_bloodwork),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(cz.medsy.R.string.channel_bloodwork_desc)
        }

        manager.createNotificationChannels(
            listOf(
                doseChannel(DOSES, sound = true, vibration = true),
                doseChannel(QUIET_DOSES, sound = true, vibration = false),
                doseChannel(VIBRATE_ONLY_DOSES, sound = false, vibration = true),
                doseChannel(SILENT_DOSES, sound = false, vibration = false),
                inventory,
                bloodwork,
            ),
        )
    }
}
