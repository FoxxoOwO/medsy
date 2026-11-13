package cz.medsy.ui.bloodwork

import cz.medsy.domain.message.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.CustomTargetValidator
import cz.medsy.domain.calc.ReferenceRanges
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class CustomTargetUiState(
    val code: String = "",
    val displayName: String = "",
    val siUnit: String = "",
    val conventionalUnit: String? = null,
    val minText: String = "",
    val maxText: String = "",
    /** Unit the user is typing in; values are converted to SI before saving. */
    val inputUnit: String = "",
    val existing: ReferenceRangeProfile? = null,
    val errors: List<String> = emptyList(),
    /**
     * Errors phrased as messages, so they follow the phone language. The validator still
     * returns plain sentences in `errors`; both lists are shown in the same card.
     */
    val messageErrors: List<cz.medsy.domain.message.Message> = emptyList(),
    val warnings: List<String> = emptyList(),
    val saved: Boolean = false,
    val removed: Boolean = false,
)

/**
 * Lets the patient enter their own therapeutic window ("doctor target").
 *
 * The typed numbers are interpreted in [CustomTargetUiState.inputUnit] and converted to
 * SI before saving, because every stored value is SI. Skipping that conversion is how a
 * 100–200 pg/mL target silently becomes 100–200 pmol/L.
 */
@HiltViewModel
class CustomTargetViewModel @Inject constructor(
    private val repository: BloodTestRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CustomTargetUiState())
    val state: StateFlow<CustomTargetUiState> = _state.asStateFlow()

    fun load(code: String) {
        val spec = Biomarkers.find(code) ?: return
        viewModelScope.launch {
            val existing = repository.observeRanges()
                .first()
                .firstOrNull { it.biomarkerCode == code && it.profileType == ProfileType.CUSTOM }
            _state.value = CustomTargetUiState(
                code = spec.code,
                displayName = spec.displayName,
                siUnit = spec.siUnit,
                conventionalUnit = spec.conventionalUnit,
                inputUnit = existing?.let { spec.siUnit } ?: spec.siUnit,
                minText = existing?.targetMinSi?.stripTrailingZeros()?.toPlainString().orEmpty(),
                maxText = existing?.targetMaxSi?.stripTrailingZeros()?.toPlainString().orEmpty(),
                existing = existing,
            )
        }
    }

    fun setMin(value: String) = _state.update {
        it.copy(minText = value.filterDecimal(), errors = emptyList(), messageErrors = emptyList(), warnings = emptyList())
    }

    fun setMax(value: String) = _state.update {
        it.copy(maxText = value.filterDecimal(), errors = emptyList(), messageErrors = emptyList(), warnings = emptyList())
    }

    fun setInputUnit(unit: String) = _state.update {
        it.copy(inputUnit = unit, errors = emptyList(), messageErrors = emptyList(), warnings = emptyList())
    }

    fun save() {
        val current = _state.value
        if (current.code.isEmpty()) return

        // Convert from the chosen input unit into SI first, then validate the SI result.
        val minSi = toSi(current, current.minText)
        val maxSi = toSi(current, current.maxText)
        if (current.minText.isNotBlank() && minSi == null) {
            _state.update {
                it.copy(
                    messageErrors = listOf(
                        message(
                            cz.medsy.domain.message.MessageKey.TARGET_MIN_UNREADABLE,
                            current.inputUnit,
                        ),
                    ),
                )
            }
            return
        }
        if (current.maxText.isNotBlank() && maxSi == null) {
            _state.update {
                it.copy(
                    messageErrors = listOf(
                        message(
                            cz.medsy.domain.message.MessageKey.TARGET_MAX_UNREADABLE,
                            current.inputUnit,
                        ),
                    ),
                )
            }
            return
        }

        when (val result = CustomTargetValidator.validate(
            code = current.code,
            minText = minSi?.toPlainString().orEmpty(),
            maxText = maxSi?.toPlainString().orEmpty(),
        )) {
            is CustomTargetValidator.Result.Invalid ->
                _state.update { it.copy(errors = result.errors) }
            is CustomTargetValidator.Result.Valid -> viewModelScope.launch {
                _state.update { it.copy(messageErrors = emptyList()) }
                runCatching {
                    repository.saveRange(result.range.copy(id = current.existing?.id ?: 0))
                }.onSuccess {
                    _state.update {
                        it.copy(errors = emptyList(), warnings = result.warnings, saved = true)
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            messageErrors = listOf(message(cz.medsy.domain.message.MessageKey.SAVE_TARGET_FAILED)),
                        )
                    }
                }
            }
        }
    }

    fun remove() {
        val current = _state.value
        if (current.code.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.deleteRange(current.code, ProfileType.CUSTOM) }
                .onSuccess {
                    _state.update { it.copy(removed = true, existing = null) }
                }
        }
    }

    private fun toSi(state: CustomTargetUiState, text: String): BigDecimal? {
        if (text.isBlank()) return null
        val value = runCatching { BigDecimal(text) }.getOrNull() ?: return null
        if (state.inputUnit == state.siUnit) return value
        return CustomTargetValidator.toSi(state.code, value, state.inputUnit)
    }

    private fun String.filterDecimal(): String =
        filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')
}
