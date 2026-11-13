package cz.medsy.ui.schedule

import cz.medsy.domain.message.message

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.R
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.notification.ReminderCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

data class ScheduleEditorUiState(
    val medication: Medication? = null,
    val draft: ScheduleDraft? = null,
    /** Validation and save errors, phrased as messages so they follow the language. */
    val errors: List<cz.medsy.domain.message.Message> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val isEditing: Boolean = false,
)

/**
 * Backs the schedule editor. Everything that mutates a schedule must go through here so
 * the alarm is re-armed immediately — a saved schedule that never gets an alarm is the
 * exact failure the reminder audit found.
 */
@HiltViewModel
class ScheduleEditorViewModel @Inject constructor(
    private val medicationRepository: MedicationRepository,
    private val reminderCoordinator: ReminderCoordinator,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val medicationId: Long = savedStateHandle["medicationId"] ?: -1L
    private val scheduleId: Long = savedStateHandle["scheduleId"] ?: 0L

    private val _state = MutableStateFlow(ScheduleEditorUiState())
    val state: StateFlow<ScheduleEditorUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val medication = medicationRepository.getById(medicationId)
            if (medication == null) {
                _state.update {
                    it.copy(
                        errors = listOf(
                            message(cz.medsy.domain.message.MessageKey.SCHEDULE_MEDICATION_MISSING),
                        ),
                    )
                }
                return@launch
            }
            val existing = if (scheduleId > 0) {
                medicationRepository.observeSchedules(medicationId)
                    .first()
                    .firstOrNull { it.id == scheduleId }
            } else {
                null
            }
            val draft = existing?.let(ScheduleValidator::toDraft)
                ?: defaultDraft(medication)
            _state.update {
                it.copy(
                    medication = medication,
                    draft = draft,
                    isEditing = existing != null,
                )
            }
        }
    }

    private fun defaultDraft(medication: Medication): ScheduleDraft {
        // Localised, because this text lands in a field the user reads and keeps; the formulary
        // only needs "mg" for injectables, which is the same word in both languages.
        val unit = when (medication.form) {
            MedicationForm.INJECTION_IM, MedicationForm.INJECTION_SUBQ -> context.getString(R.string.dose_unit_mg)
            else -> context.getString(cz.medsy.ui.message.defaultUnitRes(medication.form))
        }
        return ScheduleDraft(
            medicationId = medication.id,
            doseUnit = unit,
            requireSiteRotation = medication.form.requiresSite,
            // Injections and patches are rarely daily, so start at a realistic interval.
            type = when (medication.form) {
                MedicationForm.INJECTION_IM, MedicationForm.INJECTION_SUBQ -> ScheduleType.INTERVAL_DAYS
                MedicationForm.PATCH -> ScheduleType.INTERVAL_DAYS
                else -> ScheduleType.FIXED_DAILY
            },
            intervalValue = when (medication.form) {
                MedicationForm.INJECTION_IM, MedicationForm.INJECTION_SUBQ -> "3.5"
                MedicationForm.PATCH -> "3.5"
                else -> ""
            },
        )
    }

    private fun edit(block: (ScheduleDraft) -> ScheduleDraft) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = block(draft), errors = emptyList())
        }
    }

    fun setType(type: ScheduleType) = edit { it.copy(type = type) }

    fun setDose(value: String) = edit { it.copy(doseAmount = value.filterDecimal()) }

    fun setUnit(value: String) = edit { it.copy(doseUnit = value) }

    fun setInterval(value: String) = edit { it.copy(intervalValue = value.filterDecimal()) }

    fun setMealRelation(relation: MealRelation) = edit { it.copy(mealRelation = relation) }

    fun setOffsetMinutes(minutes: Int) = edit { it.copy(offsetMinutes = minutes.coerceIn(0, 240)) }

    fun setRequireSiteRotation(required: Boolean) = edit { it.copy(requireSiteRotation = required) }

    fun setActive(active: Boolean) = edit { it.copy(isActive = active) }

    fun setStartDate(date: LocalDate) = edit { it.copy(startDate = date) }

    fun setMaxDailyDose(value: String) = edit { it.copy(maxDailyDose = value.filterDecimal()) }

    fun setNote(value: String) = edit { it.copy(note = value) }

    fun setActuationsPerDose(value: String) = edit {
        it.copy(actuationsPerDose = value.filter(Char::isDigit))
    }

    fun setHoldMinutes(value: String) = edit {
        it.copy(holdMinutes = value.filter(Char::isDigit))
    }

    fun addTime(time: LocalTime) = edit { draft ->
        if (draft.times.size >= MAX_TIMES || draft.times.contains(time)) draft
        else draft.copy(times = (draft.times + time).sorted())
    }

    fun removeTime(time: LocalTime) = edit { it.copy(times = it.times - time) }

    fun addTaperStep() = edit {
        it.copy(taperSteps = it.taperSteps + ScheduleDraft.TaperDraft("", ""))
    }

    fun updateTaperStep(index: Int, dose: String, durationDays: String) = edit { draft ->
        val steps = draft.taperSteps.toMutableList()
        if (index in steps.indices) {
            steps[index] = ScheduleDraft.TaperDraft(dose.filterDecimal(), durationDays.filter(Char::isDigit))
        }
        draft.copy(taperSteps = steps)
    }

    fun removeTaperStep(index: Int) = edit { draft ->
        if (index in draft.taperSteps.indices) {
            draft.copy(taperSteps = draft.taperSteps.filterIndexed { i, _ -> i != index })
        } else {
            draft
        }
    }

    fun save() {
        val draft = _state.value.draft ?: return
        when (val result = ScheduleValidator.validate(draft)) {
            is ValidationResult.Invalid -> _state.update { it.copy(errors = result.errors) }
            is ValidationResult.Valid -> viewModelScope.launch {
                _state.update {
                    it.copy(isSaving = true, errors = emptyList())
                }
                runCatching {
                    val id = medicationRepository.saveSchedule(result.schedule)
                    // Re-arm immediately; without this the new schedule has no alarm
                    // until the next cold start.
                    reminderCoordinator.refreshSchedule(if (id > 0) id else result.schedule.id)
                }.onSuccess {
                    _state.update { it.copy(isSaving = false, saved = true) }
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            isSaving = false,
                            errors = listOf(
                                message(cz.medsy.domain.message.MessageKey.SAVE_SCHEDULE_FAILED),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun delete() {
        val id = _state.value.draft?.id ?: 0L
        if (id <= 0) return
        viewModelScope.launch {
            medicationRepository.deleteSchedule(id)
            reminderCoordinator.refreshSchedule(id)
            _state.update { it.copy(saved = true) }
        }
    }

    private fun String.filterDecimal(): String =
        filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')

    private companion object {
        const val MAX_TIMES = 12
    }
}
