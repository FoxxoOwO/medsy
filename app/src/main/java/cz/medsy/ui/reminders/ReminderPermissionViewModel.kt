package cz.medsy.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.notification.ReminderCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReminderPermissionState(
    val notificationsGranted: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
)

@HiltViewModel
class ReminderPermissionViewModel @Inject constructor(
    private val coordinator: ReminderCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow(ReminderPermissionState())
    val state: StateFlow<ReminderPermissionState> = _state.asStateFlow()

    fun refresh() {
        _state.value = ReminderPermissionState(
            notificationsGranted = !coordinator.notificationsBlocked(),
            exactAlarmsAllowed = coordinator.exactAlarmsAllowed(),
        )
    }

    fun openNotificationSettings() = coordinator.openPermissionSettings(exactAlarms = false)

    fun openExactAlarmSettings() = coordinator.openPermissionSettings(exactAlarms = true)

    init {
        refresh()
        // Permission changes happen in system settings, so re-check on resume.
        viewModelScope.launch { refresh() }
    }
}
