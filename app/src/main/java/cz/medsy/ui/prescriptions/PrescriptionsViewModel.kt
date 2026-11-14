package cz.medsy.ui.prescriptions

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.prescription.PrescriptionImporter
import cz.medsy.data.prescription.PrescriptionImporter.ImportResult
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.domain.prescription.QueuedPrescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class PrescriptionsViewModel @Inject constructor(
    private val repository: PrescriptionRepository,
    private val importer: PrescriptionImporter,
) : ViewModel() {

    val items: StateFlow<List<QueuedPrescription>> = repository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _importStatus = MutableStateFlow<Message?>(null)

    /**
     * The last outcome of an import, kept in state rather than fired as an event: a rotation
     * during the file read must not lose the sentence that says what happened to the file.
     */
    val importStatus: StateFlow<Message?> = _importStatus.asStateFlow()

    private var importing = false

    /**
     * Reads an eRecept out of a picked file and queues it.
     *
     * Every way of failing gets its own sentence — "no code in this text" tells the user to find
     * the code, "this PDF is a scan" tells them to photograph the paper. Reporting both as one
     * generic failure would leave the reason the import did nothing unstated.
     */
    fun import(uri: Uri, now: Instant = Instant.now()) {
        if (importing) return
        importing = true
        viewModelScope.launch {
            _importStatus.value = describe(importer.import(uri, now))
            importing = false
        }
    }

    fun clearImportStatus() {
        _importStatus.value = null
    }

    private suspend fun describe(result: ImportResult): Message = when (result) {
        is ImportResult.Found -> {
            val prescription = result.prescription
            when (repository.save(prescription)) {
                PrescriptionRepository.SaveResult.SAVED -> messagePlural(
                    MessageKey.SCAN_RX_SAVED,
                    prescription.medications.size,
                    prescription.code,
                    prescription.medications.size,
                )

                PrescriptionRepository.SaveResult.ALREADY_SAVED ->
                    message(MessageKey.SCAN_RX_DUPLICATE, prescription.code)
            }
        }

        ImportResult.NoCode -> message(MessageKey.RX_IMPORT_NO_CODE)
        ImportResult.NoTextLayer -> message(MessageKey.RX_IMPORT_NO_TEXT_LAYER)
        ImportResult.Unreadable -> message(MessageKey.RX_IMPORT_UNREADABLE)
    }

    fun markDispensed(id: Long) = viewModelScope.launch {
        repository.markDispensed(id)
    }

    fun delete(id: Long) = viewModelScope.launch {
        repository.delete(id)
    }
}
