package cz.medsy.notification

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.domain.schedule.dosesDueAt
import cz.medsy.ui.alerts.DoseAlertDose
import cz.medsy.ui.alerts.DoseAlertUi
import cz.medsy.ui.message.asText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Backs the full-screen dose alert.
 *
 * The alarm fires once per schedule, so the activity that receives it only knows the one dose
 * that woke the phone. Several medications commonly share a time, and opening a second overlay
 * over the first used to replace it instead of adding to it — the second dose was simply never
 * shown. The overlay therefore rebuilds the whole batch itself: its own dose plus every other
 * active schedule due on the same minute.
 */
@HiltViewModel
class DoseAlertViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val medicationRepository: MedicationRepository,
    private val actionHandler: DoseActionHandler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val medicationId: Long =
        savedStateHandle.get<Long>(DoseAlarmReceiver.EXTRA_MEDICATION_ID) ?: -1L
    private val scheduleId: Long =
        savedStateHandle.get<Long>(DoseAlarmReceiver.EXTRA_SCHEDULE_ID) ?: -1L
    private val dueAt: Long = savedStateHandle.get<Long>(DoseAlarmReceiver.EXTRA_DUE_AT) ?: 0L

    private val _state = MutableStateFlow(DoseAlertUi())
    val state: StateFlow<DoseAlertUi> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun take(dose: DoseAlertDose, onFinished: () -> Unit) =
        act(DoseActionHandler.Action.TAKE, dose, onFinished)

    fun skip(dose: DoseAlertDose, onFinished: () -> Unit) =
        act(DoseActionHandler.Action.SKIP, dose, onFinished)

    /** Logs every medication still on screen; the first refusal stops the batch. */
    fun takeAll(onFinished: () -> Unit) = actAll(DoseActionHandler.Action.TAKE, onFinished)

    /** Snoozes every medication still on screen by the same amount. */
    fun snooze(minutes: Int, onFinished: () -> Unit) =
        actAll(DoseActionHandler.Action.SNOOZE, onFinished, minutes)

    private fun act(action: DoseActionHandler.Action, dose: DoseAlertDose, onFinished: () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, limitMessage = null)
        viewModelScope.launch {
            if (perform(action, dose)) {
                if (_state.value.doses.isEmpty()) onFinished()
            }
        }
    }

    private fun actAll(
        action: DoseActionHandler.Action,
        onFinished: () -> Unit,
        snoozeMinutes: Int = DoseActionHandler.DEFAULT_SNOOZE_MINUTES,
    ) {
        if (_state.value.busy) return
        val batch = _state.value.doses
        if (batch.isEmpty()) return
        _state.value = _state.value.copy(busy = true, limitMessage = null)
        viewModelScope.launch {
            for (dose in batch) {
                // A refused PRN ceiling keeps the overlay open with the reason; the rest of the
                // batch is left alone so the user decides what happens to it.
                if (!perform(action, dose, snoozeMinutes)) return@launch
            }
            onFinished()
        }
    }

    private suspend fun perform(
        action: DoseActionHandler.Action,
        dose: DoseAlertDose,
        snoozeMinutes: Int = DoseActionHandler.DEFAULT_SNOOZE_MINUTES,
    ): Boolean {
        val result = actionHandler.perform(
            action = action,
            medicationId = dose.medicationId,
            scheduleId = dose.scheduleId,
            dueAt = dose.dueAt,
            snoozeMinutes = snoozeMinutes,
        )
        return when (result) {
            is DoseActionHandler.Result.LimitBlocked -> {
                _state.value = _state.value.copy(
                    busy = false,
                    limitMessage = result.message?.let { message ->
                        message.asText(context)
                    },
                )
                false
            }

            else -> {
                _state.value = _state.value.copy(
                    busy = false,
                    doses = _state.value.doses.filterNot { it.scheduleId == dose.scheduleId },
                )
                true
            }
        }
    }

    private suspend fun load() {
        val own = ownDose()
        val doses = if (own == null) emptyList() else listOf(own) + companionsOf(own)
        _state.value = if (doses.isEmpty()) {
            DoseAlertUi(loading = false, isEmpty = true)
        } else {
            DoseAlertUi(loading = false, doses = doses, timeText = timeText())
        }
    }

    private suspend fun ownDose(): DoseAlertDose? {
        if (medicationId <= 0 || scheduleId <= 0) return null
        val medication = runCatching { medicationRepository.getById(medicationId) }.getOrNull()
            ?: return null
        val schedule = runCatching {
            medicationRepository.observeSchedules(medicationId).first()
        }.getOrNull()?.firstOrNull { it.id == scheduleId } ?: return null
        return DoseAlertDose(
            medicationId = medicationId,
            scheduleId = scheduleId,
            dueAt = dueAt,
            medicationName = medication.name,
            doseText = doseText(schedule.doseAmount, schedule.doseUnit),
        )
    }

    /** Every other active schedule that also falls on this minute, sorted by name. */
    private suspend fun companionsOf(own: DoseAlertDose): List<DoseAlertDose> {
        if (dueAt <= 0L) return emptyList()
        val zone = ZoneId.systemDefault()
        val due = Instant.ofEpochMilli(dueAt)
        val schedules = runCatching { medicationRepository.activeSchedules() }
            .getOrDefault(emptyList())
            .filter { it.id != own.scheduleId }
        val rows = dosesDueAt(schedules, due, zone).mapNotNull { schedule ->
            val medication = runCatching { medicationRepository.getById(schedule.medicationId) }
                .getOrNull() ?: return@mapNotNull null
            DoseAlertDose(
                medicationId = medication.id,
                scheduleId = schedule.id,
                dueAt = dueAt,
                medicationName = medication.name,
                doseText = doseText(schedule.doseAmount, schedule.doseUnit),
            )
        }
        // Two schedules of the same drug on one minute are one decision, not two rows.
        return rows.sortedBy { it.medicationName.lowercase() }.distinctBy { it.medicationId }
    }

    private fun doseText(amount: BigDecimal, unit: String): String =
        amount.stripTrailingZeros().toPlainString() + " " + unit

    private fun timeText(): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()))
}
