package cz.medsy.ui.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.domain.inventory.BatchDraft
import cz.medsy.domain.inventory.BatchDraftValidator
import cz.medsy.domain.inventory.BatchValidation
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.Consumable
import cz.medsy.domain.model.Medication
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

data class TravelPlanInput(
    val tripStart: LocalDate = LocalDate.now(),
    val tripEnd: LocalDate = LocalDate.now().plusDays(7),
    val bufferDays: Int = 2,
)

@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repository: InventoryRepository,
) : ViewModel() {

    val stockLines: StateFlow<List<InventoryCalculator.StockLine>> =
        repository.observeStockSummary().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val consumables: StateFlow<List<Consumable>> = repository.observeConsumables().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val medications: StateFlow<List<Medication>> = repository.observeMedications().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** Pure check, so the dialog can show errors before anything is written. */
    fun validateBatch(draft: BatchDraft, medication: Medication?): BatchValidation =
        if (medication == null) {
            BatchValidation(errors = listOf(message(MessageKey.BATCH_CHOOSE_MEDICATION)))
        } else {
            BatchDraftValidator.validate(draft, medication.form)
        }

    fun addBatch(draft: BatchDraft, medication: Medication) {
        viewModelScope.launch {
            repository.addBatch(draft, medication.form)
        }
    }

    private val _travelInput = MutableStateFlow(TravelPlanInput())
    val travelInput: StateFlow<TravelPlanInput> = _travelInput.asStateFlow()

    private val _travelPlan = MutableStateFlow<InventoryCalculator.TravelPlan?>(null)
    val travelPlan: StateFlow<InventoryCalculator.TravelPlan?> = _travelPlan.asStateFlow()

    fun updateTrip(start: LocalDate, end: LocalDate, buffer: Int) {
        _travelInput.value = TravelPlanInput(start, end, buffer)
    }

    fun calculateTravelPlan() {
        viewModelScope.launch {
            val input = _travelInput.value
            _travelPlan.value = repository.travelPlan(input.tripStart, input.tripEnd, input.bufferDays)
        }
    }

    fun updateConsumable(consumable: Consumable, count: Int) {
        viewModelScope.launch {
            repository.saveConsumable(consumable.copy(remainingCount = count.coerceAtLeast(0)))
        }
    }

    fun addConsumable(name: String, count: Int) {
        viewModelScope.launch {
            repository.saveConsumable(
                Consumable(
                    name = name,
                    remainingCount = count,
                    lowStockAlertCount = 5,
                ),
            )
        }
    }

    fun clearTravelPlan() {
        _travelPlan.value = null
    }

    @Suppress("unused")
    private fun totalUnits(lines: List<InventoryCalculator.StockLine>): BigDecimal =
        lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.remaining) }
}
