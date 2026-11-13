package cz.medsy.ui.prescriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.domain.prescription.QueuedPrescription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PrescriptionsViewModel @Inject constructor(
    private val repository: PrescriptionRepository,
) : ViewModel() {

    val items: StateFlow<List<QueuedPrescription>> = repository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun markDispensed(id: Long) = viewModelScope.launch {
        repository.markDispensed(id)
    }

    fun delete(id: Long) = viewModelScope.launch {
        repository.delete(id)
    }
}
