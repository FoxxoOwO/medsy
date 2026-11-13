package cz.medsy.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.settings.AppSettings
import cz.medsy.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var lockEnabled: Boolean? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { current ->
                if (!current.biometricLock) {
                    _unlocked.value = true
                } else if (lockEnabled != true) {
                    // Turning the lock on during a session must close it immediately,
                    // otherwise the toggle appears to do nothing until the next start.
                    _unlocked.value = false
                }
                lockEnabled = current.biometricLock
            }
        }
    }

    /** Only called after a successful BiometricPrompt result. */
    fun onAuthenticated() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }
}
