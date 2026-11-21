package cz.medsy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.notification.ReminderCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reminder preferences and notification permissions. Split out of [SettingsViewModel]
 * because every toggle here talks to the alarm/notification layer, not to the Health Connect
 * or backup machinery that used to share the same class.
 */
@HiltViewModel
class ReminderSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val reminderCoordinator: ReminderCoordinator,
) : ViewModel() {

    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setRemindersEnabled(enabled)
        reminderCoordinator.refresh()
    }

    fun setReminderSound(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setReminderSound(enabled)
    }

    fun setReminderVibration(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setReminderVibration(enabled)
    }

    /** Channel properties cannot change at runtime, so a test proves the new channel. */
    fun sendTestNotification() = reminderCoordinator.sendTestNotification()

    fun setReminderLeadMinutes(minutes: Int) = viewModelScope.launch {
        settingsRepository.setReminderLeadMinutes(minutes)
        reminderCoordinator.refresh()
    }

    fun setSnoozeMinutes(minutes: Int) = viewModelScope.launch {
        settingsRepository.setSnoozeMinutes(minutes)
    }

    fun setFullScreenReminders(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setFullScreenReminders(enabled)
        reminderCoordinator.refresh()
    }

    /** Whether Android lets this app cover the lock screen with the dose alert. */
    fun fullScreenAllowed(): Boolean = reminderCoordinator.fullScreenAllowed()

    fun openFullScreenIntentSettings() = reminderCoordinator.openFullScreenIntentSettings()

    fun notificationsBlocked(): Boolean = reminderCoordinator.notificationsBlocked()

    fun openNotificationSettings() =
        reminderCoordinator.openPermissionSettings(exactAlarms = false)
}
