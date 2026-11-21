package cz.medsy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.data.settings.SuklSyncStore
import cz.medsy.data.update.ApkInstaller
import cz.medsy.data.update.UpdateChecker
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** SÚKL catalogue sync and the self-update card. Both are background jobs with visible state. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workScheduler: WorkScheduler,
    private val workManager: WorkManager,
    private val suklSyncStore: SuklSyncStore,
    private val updateChecker: UpdateChecker,
    private val apkInstaller: ApkInstaller,
) : ViewModel() {

    private val settings = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = cz.medsy.data.settings.AppSettings(),
    )

    private val _updateAction = kotlinx.coroutines.flow.MutableStateFlow(UpdateAction())

    val update: StateFlow<UpdateUi> = combine(settings, _updateAction) { current, action ->
        UpdateUi(
            latestVersion = current.latestVersion,
            checkedAt = current.updateCheckedAt.takeIf { it > 0L },
            checking = action.checking,
            updating = action.updating,
            message = action.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UpdateUi(),
    )

    /**
     * The button used to be a fire-and-forget: `syncSuklNow()` queued a worker and the screen said
     * nothing, so a failed download looked exactly like a successful one. The published state is
     * the last stored outcome combined with whether the unique work is still queued or running.
     */
    val suklSync: StateFlow<SuklSyncUi> = combine(
        suklSyncStore.record,
        workManager.getWorkInfosForUniqueWorkFlow(WorkScheduler.SUKL_SYNC_NOW),
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

    fun setUpdateCheck(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUpdateCheck(enabled) }
    }

    /** Pressed on demand; the daily worker does the same thing without the spinner. */
    fun checkForUpdates() {
        if (_updateAction.value.checking) return
        _updateAction.update { it.copy(checking = true, message = null) }
        viewModelScope.launch {
            val available = runCatching { updateChecker.check() }.getOrNull()
            _updateAction.update {
                it.copy(
                    checking = false,
                    message = if (available == null) {
                        message(MessageKey.UPDATE_UP_TO_DATE)
                    } else {
                        message(MessageKey.UPDATE_AVAILABLE, available.version)
                    },
                )
            }
        }
    }

    /**
     * Downloads the release and opens the system installer. Android never installs silently for a
     * side-loaded app, so this is as automatic as the platform allows.
     */
    fun installUpdate() {
        val url = settings.value.latestApkUrl ?: return
        if (_updateAction.value.updating) return
        _updateAction.update { it.copy(updating = true, message = null) }
        viewModelScope.launch {
            val result = runCatching { apkInstaller.downloadAndInstall(url) }
                .getOrDefault(ApkInstaller.Result.Failed)
            _updateAction.update {
                it.copy(
                    updating = false,
                    message = message(
                        when (result) {
                            ApkInstaller.Result.Started -> MessageKey.UPDATE_INSTALL_STARTED
                            ApkInstaller.Result.AskedForPermission -> MessageKey.UPDATE_INSTALL_PERMISSION
                            ApkInstaller.Result.Failed -> MessageKey.UPDATE_DOWNLOAD_FAILED
                        },
                    ),
                )
            }
        }
    }
}
