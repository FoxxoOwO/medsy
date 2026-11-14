package cz.medsy.ui.medications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.local.datasource.SideEffectCatalog
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.calc.InteractionAlert
import cz.medsy.domain.calc.InteractionEngine
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.SideEffectProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MedicationDetailUi(
    /** null while loading, and also after deletion — [gone] tells the two apart. */
    val medication: Medication? = null,
    val schedules: List<Schedule> = emptyList(),
    val batches: List<InventoryBatch> = emptyList(),
    val alerts: List<InteractionAlert> = emptyList(),
    val gone: Boolean = false,
    /** Common effects of the medication's substance, or of its ATC group when unknown. */
    val sideEffects: SideEffectProfile? = null,
)

/**
 * Backs the medication detail screen. The deletion and archiving paths end in [MedicationDetailUi.gone]
 * rather than a callback, so the screen closes only once the row is really gone from the database —
 * popping first would show the list still containing the medication for a frame.
 */
@HiltViewModel
class MedicationDetailViewModel @Inject constructor(
    private val repository: MedicationRepository,
    private val inventoryRepository: InventoryRepository,
    private val sideEffectCatalog: SideEffectCatalog,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val medicationId: Long = savedStateHandle["medicationId"] ?: -1L

    private val gone = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    private val identity = combine(
        repository.observeById(medicationId).onEach { loaded.value = true },
        repository.observeSchedules(medicationId),
        inventoryRepository.observeBatches(medicationId),
    ) { medication, schedules, batches -> Triple(medication, schedules, batches) }

    private val core: Flow<MedicationDetailUi> = combine(
        identity,
        repository.observeActive(),
        gone,
        loaded,
    ) { (medication, schedules, batches), active, isGone, isLoaded ->
        MedicationDetailUi(
            medication = medication,
            schedules = schedules,
            batches = batches,
            alerts = MedicationAlerts.of(active, medicationId),
            // A row that disappears on its own was deleted elsewhere; the screen must not linger.
            gone = isGone || (isLoaded && medication == null),
        )
    }

    /**
     * The asset lookup is keyed by the ATC code, so it re-runs only when that code changes — not on
     * every dose or batch update. [transformLatest] clears the card and then fills it: without that
     * first `emit(null)` the previous substance's effects stay on screen while the new code is being
     * looked up, which on a slow read is the wrong medicine's side effects.
     */
    private val sideEffects: Flow<SideEffectProfile?> = core
        .map { it.medication?.atcCode }
        .distinctUntilChanged()
        .transformLatest { code ->
            emit(null)
            emit(sideEffectCatalog.profileFor(code))
        }

    val state: StateFlow<MedicationDetailUi> = combine(core, sideEffects) { ui, profile ->
        ui.copy(sideEffects = profile)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedicationDetailUi(),
    )

    /**
     * Saves an edit of [medication]. The row is passed in rather than read back from [state]: the
     * state flow only recomputes while something subscribes, and a write must not depend on a
     * collector being alive (that is exactly how an edit was silently dropped before).
     */
    fun save(medication: Medication, edit: MedicationEdit) {
        if (!edit.isValid) return
        viewModelScope.launch { repository.save(edit.applyTo(medication)) }
    }

    /** Archiving hides the row from every list, so the screen that shows it closes too. */
    fun archive() {
        viewModelScope.launch {
            repository.archive(medicationId)
            gone.value = true
        }
    }

    fun delete() {
        viewModelScope.launch {
            repository.delete(medicationId)
            gone.value = true
        }
    }

    fun setScheduleActive(schedule: Schedule, active: Boolean) {
        viewModelScope.launch { repository.setScheduleActive(schedule.id, active) }
    }

    fun deleteSchedule(schedule: Schedule) {
        viewModelScope.launch { repository.deleteSchedule(schedule.id) }
    }
}

/** Interaction alerts for one row, shared by the list and the detail screen. */
internal object MedicationAlerts {

    fun of(active: List<Medication>, medicationId: Long): List<InteractionAlert> {
        val substances = active.map { med ->
            InteractionEngine.ActiveSubstance(
                name = med.activeSubstance.orEmpty(),
                atcCode = med.atcCode,
                medicationId = med.id,
            )
        }
        return InteractionEngine.evaluate(substances)
            .filter { alert -> alert.involvesMedications.any { it == medicationId } }
    }
}
