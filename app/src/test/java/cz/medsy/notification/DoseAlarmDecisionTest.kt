package cz.medsy.notification

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * Decision logic used by [DoseAlarmReceiver] plus the reminder settings defaults.
 * The receiver itself is not instantiated: its injected collaborators need the Hilt
 * singleton graph, which Robolectric does not build. Scheduler/DAO behaviour is
 * covered by [DoseAlarmSchedulerTest] and [DoseLoggingTest].
 */
@RunWith(RobolectricTestRunner::class)
class DoseAlarmDecisionTest {

    private fun isStale(dueAt: Instant): Boolean =
        Duration.between(dueAt, Instant.now()) > DoseAlarmReceiver.MAX_STALENESS

    @Test
    fun `alarm older than six hours is treated as missed`() {
        assertTrue(isStale(Instant.now().minus(Duration.ofHours(7))))
    }

    @Test
    fun `alarm within six hours is still delivered`() {
        assertFalse(isStale(Instant.now().minus(Duration.ofHours(2))))
    }

    @Test
    fun `a future alarm is never stale`() {
        assertFalse(isStale(Instant.now().plus(Duration.ofMinutes(5))))
    }

    @Test
    fun `notification ids are stable per occurrence`() {
        val due = 1_700_000_000_000L
        assertEquals(
            DoseAlarmReceiver.notificationId(1, due),
            DoseAlarmReceiver.notificationId(1, due),
        )
    }

    @Test
    fun `notification ids differ across schedules and occurrences`() {
        val due = 1_700_000_000_000L
        assertFalse(
            DoseAlarmReceiver.notificationId(1, due) ==
                DoseAlarmReceiver.notificationId(2, due),
        )
        assertFalse(
            DoseAlarmReceiver.notificationId(1, due) ==
                DoseAlarmReceiver.notificationId(1, due + 3_600_000L),
        )
    }

    @Test
    fun `notification ids stay positive`() {
        val id = DoseAlarmReceiver.notificationId(Long.MAX_VALUE, Long.MAX_VALUE - 1)
        assertTrue(id >= 0)
    }

    @Test
    fun `reminder settings default to enabled, sound on, no lead time`() {
        // DataStore is a process-wide singleton, so assert the model defaults here and
        // cover persistence in the round-trip test below.
        val defaults = cz.medsy.data.settings.AppSettings()

        assertTrue(defaults.remindersEnabled)
        assertTrue(defaults.reminderSound)
        assertTrue(defaults.reminderVibration)
        assertEquals(0, defaults.reminderLeadMinutes)
        assertEquals(15, defaults.snoozeMinutes)
        // Covering the lock screen is intrusive, so it is never on without a deliberate opt-in.
        assertFalse(defaults.fullScreenReminders)
    }

    @Test
    fun `reminder settings round trip through DataStore`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)

        repository.setReminderLeadMinutes(15)
        repository.setSnoozeMinutes(30)
        repository.setRemindersEnabled(false)
        repository.setReminderSound(false)
        repository.setFullScreenReminders(true)

        val stored = repository.settings.first()
        assertEquals(15, stored.reminderLeadMinutes)
        assertEquals(30, stored.snoozeMinutes)
        assertFalse(stored.remindersEnabled)
        assertFalse(stored.reminderSound)
        assertTrue(stored.fullScreenReminders)
    }

    @Test
    fun `sound and vibration map to four distinct channels`() {
        val channels = setOf(
            NotificationChannels.doseChannel(sound = true, vibration = true),
            NotificationChannels.doseChannel(sound = true, vibration = false),
            NotificationChannels.doseChannel(sound = false, vibration = true),
            NotificationChannels.doseChannel(sound = false, vibration = false),
        )
        assertEquals(4, channels.size)
    }

    @Test
    fun `turning vibration off selects a non vibrating channel`() {
        val withVibration = NotificationChannels.doseChannel(sound = true, vibration = true)
        val withoutVibration = NotificationChannels.doseChannel(sound = true, vibration = false)
        assertFalse(withVibration == withoutVibration)
        assertEquals(NotificationChannels.QUIET_DOSES, withoutVibration)
    }

    @Test
    fun `fully silent channel is used when both are off`() {
        assertEquals(
            NotificationChannels.SILENT_DOSES,
            NotificationChannels.doseChannel(sound = false, vibration = false),
        )
    }

    @Test
    fun `lead time is clamped to a sane range`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)

        repository.setReminderLeadMinutes(-30)
        assertEquals(0, repository.settings.first().reminderLeadMinutes)

        repository.setReminderLeadMinutes(999)
        assertEquals(120, repository.settings.first().reminderLeadMinutes)
    }
}
