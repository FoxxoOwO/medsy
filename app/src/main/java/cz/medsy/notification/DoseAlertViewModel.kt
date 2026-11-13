package cz.medsy.notification

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.ui.alerts.DoseAlertUi
import cz.medsy.ui.message.asText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Backs [DoseAlarmActivity]. Reads the occurrence from the intent extras that the
 * full-screen intent carried, so the overlay never has to guess which dose it announces.
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

    private suspend fun load() {
        val medication = runCatching { medicationRepository.getById(medicationId) }.getOrNull()
        val schedule = runCatching {
            medicationRepository.observeSchedules(medicationId).first()
                .firstOrNull { it.id == scheduleId }
        }.getOrNull()

        // Nothing to announce (the schedule was deleted while the alarm was armed): the
        // overlay closes itself and the notification stays the only surface.
        if (medication == null || schedule == null) {
            _state.value = DoseAlertUi(loading = false, isEmpty = true)
            return
        }

        _state.value = DoseAlertUi(
            loading = false,
            medicationName = medication.name,
            doseText = buildString {
                append(schedule.doseAmount.stripTrailingZeros().toPlainString())
                append(' ')
                append(schedule.doseUnit)
            },
            timeText = Instant.ofEpochMilli(dueAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm")),
        )
    }

    fun take(onFinished: () -> Unit) = execute(DoseActionHandler.Action.TAKE, onFinished)

    fun skip(onFinished: () -> Unit) = execute(DoseActionHandler.Action.SKIP, onFinished)

    fun snooze(minutes: Int, onFinished: () -> Unit) =
        execute(DoseActionHandler.Action.SNOOZE, onFinished, minutes)

    /**
     * [DoseActionHandler.Result.LimitBlocked] is not an error the user can ignore: the dose
     * was refused, so the overlay stays open and explains why instead of closing silently.
     */
    private fun execute(
        action: DoseActionHandler.Action,
        onFinished: () -> Unit,
        snoozeMinutes: Int = DoseActionHandler.DEFAULT_SNOOZE_MINUTES,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            when (val result = actionHandler.perform(action, medicationId, scheduleId, dueAt, snoozeMinutes)) {
                is DoseActionHandler.Result.Done -> onFinished()
                is DoseActionHandler.Result.NotFound -> onFinished()
                is DoseActionHandler.Result.LimitBlocked -> _state.value = _state.value.copy(
                    busy = false,
                    limitMessage = result.message?.asText(context),
                )
            }
        }
    }
}
