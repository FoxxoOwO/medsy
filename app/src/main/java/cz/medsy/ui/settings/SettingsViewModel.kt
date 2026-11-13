package cz.medsy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.settings.AppSettings
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.ui.theme.MedsyPalette
import cz.medsy.ui.theme.ThemeMode
import cz.medsy.report.DoctorReportGenerator
import cz.medsy.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

/** A one-time sync that is queued but waiting for network still counts as in progress. */
internal fun suklSyncRunning(states: List<androidx.work.WorkInfo.State>): Boolean =
    states.any {
        it == androidx.work.WorkInfo.State.ENQUEUED ||
            it == androidx.work.WorkInfo.State.RUNNING ||
            it == androidx.work.WorkInfo.State.BLOCKED
    }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workScheduler: WorkScheduler,
    private val reportGenerator: DoctorReportGenerator,
    private val reminderCoordinator: cz.medsy.notification.ReminderCoordinator,
    private val workManager: androidx.work.WorkManager,
    private val suklSyncStore: cz.medsy.data.settings.SuklSyncStore,
    private val backupRepository: cz.medsy.data.backup.BackupRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _exportedReport = MutableStateFlow<java.io.File?>(null)
    val exportedReport: StateFlow<java.io.File?> = _exportedReport.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    fun healthConnectAvailable(): Boolean =
        cz.medsy.health.HealthConnectAvailability.isAvailable(context)

    /**
     * `HealthConnectAvailability.status` asks the Health Connect provider, i.e. it does a
     * cross-process lookup. It used to run inside composition (`remember { viewModel…() }`),
     * which stalls the first frame of the screen; it is resolved on the IO dispatcher instead.
     */
    private val _healthStatus =
        MutableStateFlow<cz.medsy.health.HealthConnectAvailability.Status?>(null)
    val healthStatus: StateFlow<cz.medsy.health.HealthConnectAvailability.Status?> =
        _healthStatus.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _healthStatus.value = cz.medsy.health.HealthConnectAvailability.status(context)
            syncHealthConnectWithGrants()
        }
    }

    fun healthConnectStatus(): cz.medsy.health.HealthConnectAvailability.Status =
        cz.medsy.health.HealthConnectAvailability.status(context)

    /**
     * The permission sheet needs an exported rationale activity (HealthRationaleActivity); with
     * none declared the request resolves to an empty set and the switch never moved. Whatever the
     * contract reports is therefore re-checked against the grants Health Connect actually stored.
     */
    fun onHealthPermissionsResult(granted: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val stored = grantedPermissions()
            val effective = if (stored.isEmpty()) granted else stored
            settingsRepository.setHealthConnect(cz.medsy.health.HealthPermissions.allowsWrites(effective))
        }
    }

    /** A permission revoked in the Health Connect app must not leave the switch on. */
    private suspend fun syncHealthConnectWithGrants() {
        if (_healthStatus.value != cz.medsy.health.HealthConnectAvailability.Status.AVAILABLE) return
        val stored = grantedPermissions()
        if (stored.isEmpty()) return
        val enabled = settingsRepository.settings.first().healthConnectEnabled
        if (enabled && !cz.medsy.health.HealthPermissions.allowsWrites(stored)) {
            settingsRepository.setHealthConnect(false)
        }
    }

    private suspend fun grantedPermissions(): Set<String> = runCatching {
        androidx.health.connect.client.HealthConnectClient.getOrCreate(context)
            .permissionController
            .getGrantedPermissions()
    }.getOrDefault(emptySet())

    /** Permission request contract for Health Connect; launched from the settings UI. */
    fun healthConnectPermissionContract():
        androidx.activity.result.contract.ActivityResultContract<Set<String>, Set<String>> =
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()

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
        val action = cz.medsy.health.HealthConnectAvailability.status(context)
        val intent = if (action == cz.medsy.health.HealthConnectAvailability.Status.AVAILABLE) {
            android.content.Intent(
                androidx.health.connect.client.HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
            )
        } else {
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:com.google.android.apps.healthdata"))
        }
        runCatching {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun setDiscreetMode(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDiscreetMode(enabled)
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

    /**
     * The button used to be a fire-and-forget: `syncSuklNow()` queued a worker and the screen said
     * nothing, so a failed download looked exactly like a successful one. The published state is
     * the last stored outcome combined with whether the unique work is still queued or running.
     */
    val suklSync: StateFlow<SuklSyncUi> = kotlinx.coroutines.flow.combine(
        suklSyncStore.record,
        workManager.getWorkInfosForUniqueWorkFlow(cz.medsy.work.WorkScheduler.SUKL_SYNC_NOW),
    ) { record, infos ->
        SuklSyncUi(
            running = suklSyncRunning(infos.map { it.state }),
            finishedAt = record.finishedAt,
            count = record.count,
            failed = record.failed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SuklSyncUi(),
    )

    fun syncSukl() = workScheduler.syncSuklNow()

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

    /**
     * Exports with the profile the user actually selected. The generator defaulted to
     * HRT_FEM, so a masculinising patient got feminising targets in the PDF.
     */
    fun exportReport() {
        viewModelScope.launch {
            val profile = runCatching {
                cz.medsy.domain.model.ProfileType.valueOf(
                    settingsRepository.settings.first().defaultRangeProfile,
                )
            }.getOrDefault(cz.medsy.domain.model.ProfileType.HRT_FEM)
            runCatching { reportGenerator.generate(profile).file }
                .onSuccess { file -> _exportedReport.value = file }
        }
    }

    /** Shares the generated PDF through the app's FileProvider. */
    fun shareReport() {
        val file = _exportedReport.value ?: return
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrNull() ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Medication & HRT summary")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share report")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun clearExportedReport() {
        _exportedReport.value = null
    }

    // --- Backup and restore -------------------------------------------------

    private val _backup = MutableStateFlow(BackupUi())
    val backup: StateFlow<BackupUi> = _backup.asStateFlow()

    /** Ready to be written to the file the user picked; null when there is nothing to save. */
    private val _exportPayload = MutableStateFlow<String?>(null)
    val exportPayload: StateFlow<String?> = _exportPayload.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    /**
     * Builds the backup in memory first, so the file is only created once there is something to
     * write and a cancelled save dialog cannot leave an empty file behind.
     */
    fun exportBackup(password: String?) {
        _backup.value = BackupUi(busy = true)
        viewModelScope.launch {
            runCatching { backupRepository.export(password?.toCharArray()) }
                .onSuccess { _exportPayload.value = it }
                .onFailure { failure ->
                    _backup.value = BackupUi(error = cz.medsy.domain.message.message(backupKey(failure)))
                }
        }
    }

    fun writeExport(uri: android.net.Uri) {
        val payload = _exportPayload.value ?: return
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }
            }
            _exportPayload.value = null
            _backup.value = if (saved.isSuccess) {
                BackupUi(message = cz.medsy.domain.message.message(MessageKey.BACKUP_EXPORTED))
            } else {
                BackupUi(error = cz.medsy.domain.message.message(MessageKey.BACKUP_WRITE_FAILED))
            }
        }
    }

    fun discardExport() {
        _exportPayload.value = null
        _backup.value = BackupUi()
    }

    fun readImport(uri: android.net.Uri) {
        _backup.value = BackupUi(busy = true)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.reader(Charsets.UTF_8).readText()
                    } ?: error("no stream")
                }
            }
            text
                .onSuccess { body ->
                    _pendingImport.value = PendingImport(
                        text = body,
                        encrypted = cz.medsy.data.backup.BackupCrypto.isEncrypted(body),
                    )
                    _backup.value = BackupUi()
                }
                .onFailure {
                    _backup.value =
                        BackupUi(error = cz.medsy.domain.message.message(MessageKey.BACKUP_READ_FAILED))
                }
        }
    }

    fun importBackup(password: String?, mode: cz.medsy.data.backup.BackupRepository.Mode) {
        val pending = _pendingImport.value ?: return
        _backup.value = BackupUi(busy = true)
        viewModelScope.launch {
            runCatching { backupRepository.import(pending.text, password?.toCharArray(), mode) }
                .onSuccess { report ->
                    _pendingImport.value = null
                    _backup.value = BackupUi(
                        message = cz.medsy.domain.message.messagePlural(
                            MessageKey.BACKUP_RESTORED,
                            report.inserted,
                            report.inserted,
                        ),
                    )
                }
                .onFailure { failure ->
                    _backup.value = BackupUi(error = cz.medsy.domain.message.message(backupKey(failure)))
                }
        }
    }

    fun discardImport() {
        _pendingImport.value = null
        _backup.value = BackupUi()
    }

    private fun backupKey(failure: Throwable): MessageKey =
        (failure as? cz.medsy.data.backup.BackupException)?.key
            ?: when (failure) {
                is java.io.IOException -> MessageKey.BACKUP_WRITE_FAILED
                else -> MessageKey.BACKUP_UNREADABLE
            }
}
