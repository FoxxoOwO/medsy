package cz.medsy.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.HealthConnectClient
import androidx.activity.result.contract.ActivityResultContract
import cz.medsy.data.settings.AppSettings
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.health.HealthConnectAvailability
import cz.medsy.health.HealthPermissions
import cz.medsy.ui.theme.MedsyPalette
import cz.medsy.ui.theme.ThemeMode
import cz.medsy.widget.NextDoseWidget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BackupUi(
    val busy: Boolean = false,
    /** What the last action did. */
    val message: cz.medsy.domain.message.Message? = null,
    /** Why it failed, if it did. */
    val error: cz.medsy.domain.message.Message? = null,
)

/** A file the user picked, still encrypted or already readable. */
data class PendingImport(val text: String, val encrypted: Boolean)

/** What the SÚKL button should be showing right now. */
data class SuklSyncUi(
    val running: Boolean = false,
    val finishedAt: Long? = null,
    val count: Int = 0,
    val failed: Boolean = false,
)

/** What the update card shows: this build, what the last check found, and what it is doing now. */
data class UpdateUi(
    val currentVersion: String = cz.medsy.BuildConfig.VERSION_NAME,
    val latestVersion: String? = null,
    val checkedAt: Long? = null,
    val checking: Boolean = false,
    val updating: Boolean = false,
    val message: cz.medsy.domain.message.Message? = null,
) {
    /** The release to offer, or null when this install is already the newest. */
    val newer: String? get() =
        latestVersion?.takeIf { cz.medsy.data.update.isNewerVersion(it, currentVersion) }
}

internal data class UpdateAction(
    val checking: Boolean = false,
    val updating: Boolean = false,
    val message: cz.medsy.domain.message.Message? = null,
)

/** A one-time sync that is queued but waiting for network still counts as in progress. */
internal fun suklSyncRunning(states: List<androidx.work.WorkInfo.State>): Boolean =
    states.any {
        it == androidx.work.WorkInfo.State.ENQUEUED ||
            it == androidx.work.WorkInfo.State.RUNNING ||
            it == androidx.work.WorkInfo.State.BLOCKED
    }

/**
 * Core preferences and Health Connect. The reminder, backup/export and update/SÚKL groups
 * live in their own ViewModels; they used to make this one a 500-line God object.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    /**
     * `HealthConnectAvailability.status` asks the Health Connect provider, i.e. it does a
     * cross-process lookup. It used to run inside composition (`remember { viewModel…() }`),
     * which stalls the first frame of the screen; it is resolved on the IO dispatcher instead.
     */
    private val _healthStatus =
        MutableStateFlow<HealthConnectAvailability.Status?>(null)
    val healthStatus: StateFlow<HealthConnectAvailability.Status?> =
        _healthStatus.asStateFlow()

    init {
        refreshHealthState()
    }

    /**
     * Called on every resume: grants can change in the Health Connect app (the "Open settings"
     * button sends the user there) and the switch must not keep claiming a permission that was
     * revoked, nor stay off after one was granted.
     */
    fun refreshHealthState() {
        viewModelScope.launch(Dispatchers.IO) {
            _healthStatus.value = HealthConnectAvailability.status(context)
            syncHealthConnectWithGrants()
        }
    }

    fun healthConnectStatus(): HealthConnectAvailability.Status =
        HealthConnectAvailability.status(context)

    /**
     * The permission sheet needs an exported rationale activity (HealthRationaleActivity); with
     * none declared the request resolves to an empty set and the switch never moved. Whatever the
     * contract reports is therefore re-checked against the grants Health Connect actually stored.
     */
    fun onHealthPermissionsResult(granted: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = grantedPermissions()
            val effective = if (stored.isEmpty()) granted else stored
            settingsRepository.setHealthConnect(HealthPermissions.allowsWrites(effective))
        }
    }

    /** A permission revoked in the Health Connect app must not leave the switch on. */
    private suspend fun syncHealthConnectWithGrants() {
        if (_healthStatus.value != HealthConnectAvailability.Status.AVAILABLE) return
        val stored = grantedPermissions()
        if (stored.isEmpty()) return
        val enabled = settingsRepository.settings.first().healthConnectEnabled
        if (enabled && !HealthPermissions.allowsWrites(stored)) {
            settingsRepository.setHealthConnect(false)
        }
    }

    private suspend fun grantedPermissions(): Set<String> = runCatching {
        HealthConnectClient.getOrCreate(context)
            .permissionController
            .getGrantedPermissions()
    }.getOrDefault(emptySet())

    /** Permission request contract for Health Connect; launched from the settings UI. */
    fun healthConnectPermissionContract():
        ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    fun setUvWarnings(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setUvWarningsEnabled(enabled)
    }

    fun setUvCity(city: String) = viewModelScope.launch {
        settingsRepository.setUvCity(city)
    }

    fun setHealthConnect(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHealthConnect(enabled)
    }

    fun openHealthConnectSettings() {
        val action = HealthConnectAvailability.status(context)
        val intent = if (action == HealthConnectAvailability.Status.AVAILABLE) {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:com.google.android.apps.healthdata"))
        }
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun setDiscreetMode(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDiscreetMode(enabled)
        // The widget otherwise keeps showing the medication name until the next 30-minute
        // system refresh. DataStore already persisted the new value, so redraw it now.
        runCatching { withContext(Dispatchers.IO) { NextDoseWidget().updateAll(context) } }
    }

    fun setDiscreetIcon(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDiscreetIcon(enabled)
    }

    fun setBiometricLock(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBiometricLock(enabled)
    }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDynamicColor(enabled)
    }

    fun setPalette(palette: MedsyPalette) = viewModelScope.launch {
        settingsRepository.setPalette(palette)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }
}
