package cz.medsy.domain.alert

import java.time.Duration
import java.time.Instant

/**
 * Rules for the full-screen dose alert that can replace the lock screen.
 *
 * Kept as pure functions because the bug they prevent is a policy bug, not a UI bug:
 * a wake-the-phone overlay shown without active reminders (or without the Android 14
 * permission) is an alarm that fires and then cannot be dismissed properly.
 */
object LockScreenAlert {

    /**
     * A wake-the-phone overlay must not outlive the reminder it announces. After this
     * window the overlay closes itself and the notification keeps the reminder reachable.
     */
    const val AUTO_DISMISS_MINUTES = 5

    /** All three gates have to be open, in this order of authority. */
    fun allowed(
        settingEnabled: Boolean,
        remindersEnabled: Boolean,
        permissionGranted: Boolean,
    ): Boolean = settingEnabled && remindersEnabled && permissionGranted

    fun autoDismissAt(openedAt: Instant): Instant =
        openedAt.plus(Duration.ofMinutes(AUTO_DISMISS_MINUTES.toLong()))
}
