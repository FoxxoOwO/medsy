package cz.medsy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.backup.BackupRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.domain.model.ProfileType
import cz.medsy.report.DoctorReportGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import java.io.File
import javax.inject.Inject

/**
 * The doctor PDF and the encrypted backup format both write files the user takes off-device.
 * Keeping them together means the plaintext-report lifecycle is owned in one place.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val reportGenerator: DoctorReportGenerator,
    private val backupRepository: BackupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _exportedReport = MutableStateFlow<File?>(null)
    val exportedReport: StateFlow<File?> = _exportedReport.asStateFlow()

    private val _backup = MutableStateFlow(BackupUi())
    val backup: StateFlow<BackupUi> = _backup.asStateFlow()

    /** Ready to be written to the file the user picked; null when there is nothing to save. */
    private val _exportPayload = MutableStateFlow<String?>(null)
    val exportPayload: StateFlow<String?> = _exportPayload.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    /**
     * Exports with the profile the user actually selected. The generator defaulted to
     * HRT_FEM, so a masculinising patient got feminising targets in the PDF.
     */
    fun exportReport() {
        viewModelScope.launch {
            val profile = runCatching {
                ProfileType.valueOf(settingsRepository.settings.first().defaultRangeProfile)
            }.getOrDefault(ProfileType.HRT_FEM)
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

    /**
     * Drops the reference and deletes the plaintext PDF from the cache. Leaving it there
     * would keep the whole medication and lab record readable outside the encrypted DB.
     */
    fun clearExportedReport() {
        runCatching { _exportedReport.value?.delete() }
        _exportedReport.value = null
    }

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
                    _backup.value = BackupUi(error = message(backupKey(failure)))
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
                BackupUi(message = message(MessageKey.BACKUP_EXPORTED))
            } else {
                BackupUi(error = message(MessageKey.BACKUP_WRITE_FAILED))
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
                    _backup.value = BackupUi(error = message(MessageKey.BACKUP_READ_FAILED))
                }
        }
    }

    fun importBackup(password: String?, mode: BackupRepository.Mode) {
        val pending = _pendingImport.value ?: return
        _backup.value = BackupUi(busy = true)
        viewModelScope.launch {
            runCatching { backupRepository.import(pending.text, password?.toCharArray(), mode) }
                .onSuccess { report ->
                    _pendingImport.value = null
                    _backup.value = BackupUi(
                        message = messagePlural(
                            MessageKey.BACKUP_RESTORED,
                            report.inserted,
                            report.inserted,
                        ),
                    )
                }
                .onFailure { failure ->
                    _backup.value = BackupUi(error = message(backupKey(failure)))
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
