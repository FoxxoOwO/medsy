package cz.medsy.ui.symptoms

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.R
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.SymptomRepository
import cz.medsy.domain.calc.SymptomCorrelation
import cz.medsy.domain.calc.SymptomCorrelationCalculator
import cz.medsy.domain.calc.SymptomPoint
import cz.medsy.domain.calc.takenDoseEvents
import cz.medsy.domain.model.SymptomLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * `code` is what the database stores, so it must never change; the label is a resource id
 * because the recorder was showing English chips ("Hot flashes") on a Czech screen.
 */
enum class SymptomKind(val code: String, @param:StringRes val label: Int) {
    MOOD("MOOD", R.string.symptom_mood),
    ENERGY("ENERGY", R.string.symptom_energy),
    HEADACHE("HEADACHE", R.string.symptom_headache),
    HOT_FLASHES("HOT_FLASHES", R.string.symptom_hot_flashes),
    ACNE("ACNE", R.string.symptom_acne),
    CRAMPING("CRAMPING", R.string.symptom_cramping),
    INJECTION_SITE("INJECTION_SITE", R.string.symptom_injection_site),
    LIBIDO("LIBIDO", R.string.symptom_libido),
}

data class SymptomDraft(
    val kind: SymptomKind = SymptomKind.MOOD,
    val severity: Int = 3,
    val note: String = "",
    val systolic: String = "",
    val diastolic: String = "",
    val heartRate: String = "",
)

@HiltViewModel
class SymptomsViewModel @Inject constructor(
    private val repository: SymptomRepository,
    private val doseRepository: DoseRepository,
    private val healthConnectSyncer: cz.medsy.health.HealthConnectSyncer,
) : ViewModel() {

    private val _draft = MutableStateFlow(SymptomDraft())
    val draft: StateFlow<SymptomDraft> = _draft.asStateFlow()

    val recent: StateFlow<List<SymptomLog>> = repository
        .observeBetween(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now())
        .map { list -> list.sortedByDescending { it.timestamp } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Only the strongest patterns are surfaced: a symptom needs three entries on each side
     * of the window before the card claims anything at all.
     */
    val patterns: StateFlow<List<SymptomCorrelation>> = combine(
        repository.observeBetween(PATTERN_SINCE, Instant.now()),
        doseRepository.observeBetween(PATTERN_SINCE, Instant.now()),
    ) { symptoms, doses ->
        SymptomCorrelationCalculator.analyze(
            points = symptoms.map { SymptomPoint(it.timestamp, it.symptomCode, it.severity) },
            doses = takenDoseEvents(doses),
        ).filter { it.isReportable }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun setKind(kind: SymptomKind) = _draft.update { it.copy(kind = kind) }

    fun setSeverity(severity: Int) = _draft.update { it.copy(severity = severity.coerceIn(1, 5)) }

    fun setNote(note: String) = _draft.update { it.copy(note = note) }

    fun setSystolic(value: String) = _draft.update { it.copy(systolic = value.filter(Char::isDigit)) }

    fun setDiastolic(value: String) = _draft.update { it.copy(diastolic = value.filter(Char::isDigit)) }

    fun setHeartRate(value: String) = _draft.update { it.copy(heartRate = value.filter(Char::isDigit)) }

    fun save() {
        val draft = _draft.value
        viewModelScope.launch {
            val log = SymptomLog(
                timestamp = Instant.now(),
                symptomCode = draft.kind.code,
                severity = draft.severity,
                note = draft.note.ifBlank { null },
                systolic = draft.systolic.toIntOrNull(),
                diastolic = draft.diastolic.toIntOrNull(),
                heartRate = draft.heartRate.toIntOrNull(),
            )
            val id = repository.add(log)
            // Best effort: a Health Connect failure must never lose the entry the user
            // just typed. The syncer returns a result instead of throwing.
            runCatching { healthConnectSyncer.sync(listOf(log.copy(id = id))) }
            _draft.value = SymptomDraft(kind = draft.kind)
        }
    }

    fun delete(log: SymptomLog) {
        viewModelScope.launch { repository.delete(log.id) }
    }

    private fun MutableStateFlow<SymptomDraft>.update(block: (SymptomDraft) -> SymptomDraft) {
        value = block(value)
    }

    private companion object {
        /** Long enough to cover several injection intervals at the usual two-week rhythm. */
        val PATTERN_SINCE: Instant = Instant.now().minus(90, ChronoUnit.DAYS)
    }
}
