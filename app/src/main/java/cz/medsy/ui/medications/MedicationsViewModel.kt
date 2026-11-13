package cz.medsy.ui.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.calc.InteractionAlert
import cz.medsy.domain.message.Message
import cz.medsy.domain.calc.InteractionEngine
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.Schedule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MedicationListItem(
    val medication: Medication,
    val schedules: List<Schedule>,
)

@HiltViewModel
class MedicationsViewModel @Inject constructor(
    private val repository: MedicationRepository,
    private val reminderCoordinator: cz.medsy.notification.ReminderCoordinator,
) : ViewModel() {

    private val _offsetNotices = MutableStateFlow<List<Message>>(emptyList())

    /** Explanations for reminders the app moved to avoid an absorption conflict. */
    val offsetNotices: StateFlow<List<Message>> = _offsetNotices.asStateFlow()

    /** Called once when the list is shown, so the notice is not repeated forever. */
    fun refreshOffsetNotices() {
        val messages = reminderCoordinator.takeOffsetNotices().map { it.message }
        if (messages.isNotEmpty()) _offsetNotices.value = messages
    }

    fun dismissOffsetNotices() {
        _offsetNotices.value = emptyList()
    }

    val medications: StateFlow<List<Medication>> = repository.observeActive().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Alerts keyed by medication id so each row can flag its own conflicts. */
    val alertsByMedication: StateFlow<Map<Long, List<InteractionAlert>>> =
        repository.observeActive()
            .map { meds -> buildAlerts(meds) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap(),
            )

    private fun buildAlerts(meds: List<Medication>): Map<Long, List<InteractionAlert>> {
        val substances = meds.map { med ->
            InteractionEngine.ActiveSubstance(
                name = med.activeSubstance.orEmpty(),
                atcCode = med.atcCode,
                medicationId = med.id,
            )
        }
        return InteractionEngine.evaluate(substances)
            .flatMap { alert -> alert.involvesMedications.map { it to alert } }
            .groupBy({ it.first }, { it.second })
    }

    /** All active schedules grouped by medication, so cards can show and edit them. */
    val schedulesByMedication: StateFlow<Map<Long, List<Schedule>>> =
        repository.observeActive()
            .flatMapLatest { meds ->
                if (meds.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(meds.map { medication -> repository.observeSchedules(medication.id) }) { lists ->
                        meds.mapIndexed { index, medication -> medication.id to lists[index] }
                            .toMap()
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap(),
            )

    private val _selected = MutableStateFlow<Medication?>(null)
    val selected: StateFlow<Medication?> = _selected.asStateFlow()

    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    fun select(medication: Medication) {
        _selected.value = medication
        viewModelScope.launch {
            repository.observeSchedules(medication.id).collect { _schedules.value = it }
        }
    }

    fun delete(medication: Medication) {
        viewModelScope.launch {
            repository.delete(medication.id)
            _selected.value = null
        }
    }

    fun setScheduleActive(schedule: Schedule, active: Boolean) {
        viewModelScope.launch { repository.setScheduleActive(schedule.id, active) }
    }

    fun toggleArchive(medication: Medication) {
        viewModelScope.launch { repository.archive(medication.id) }
    }

    fun clearSelection() {
        _selected.value = null
        _schedules.update { emptyList() }
    }
}
