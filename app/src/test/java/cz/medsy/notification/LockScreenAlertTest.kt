package cz.medsy.notification

import cz.medsy.domain.alert.LockScreenAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The gate in front of the full-screen dose alert. Android 14+ withholds
 * USE_FULL_SCREEN_INTENT from apps it does not treat as alarms, so the overlay is only
 * worth arming when the user asked for it *and* the system still allows it.
 */
class LockScreenAlertTest {

    @Test
    fun `allowed only when setting, reminders and permission all hold`() {
        assertTrue(
            LockScreenAlert.allowed(
                settingEnabled = true,
                remindersEnabled = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `a disabled setting suppresses the overlay`() {
        assertFalse(
            LockScreenAlert.allowed(
                settingEnabled = false,
                remindersEnabled = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `reminders off suppresses the overlay even with the setting on`() {
        assertFalse(
            LockScreenAlert.allowed(
                settingEnabled = true,
                remindersEnabled = false,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `a revoked full-screen intent permission suppresses the overlay`() {
        // The notification must still be posted; only the overlay is dropped, because
        // setFullScreenIntent without the permission silently falls back to a head-up.
        assertFalse(
            LockScreenAlert.allowed(
                settingEnabled = true,
                remindersEnabled = true,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun `the alert dismisses itself after five minutes`() {
        val openedAt = Instant.parse("2026-05-01T08:00:00Z")

        assertEquals(Instant.parse("2026-05-01T08:05:00Z"), LockScreenAlert.autoDismissAt(openedAt))
        assertEquals(5, LockScreenAlert.AUTO_DISMISS_MINUTES)
    }
}
