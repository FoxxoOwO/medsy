package cz.medsy.ui.bloodwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.lab.LabImportPipeline
import cz.medsy.data.lab.OcrLabExtractor
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.data.repository.BloodTestRepository
import cz.medsy.data.repository.MilestoneRepository
import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.RangeEvaluator
import cz.medsy.domain.calc.RangeBands
import cz.medsy.domain.calc.RangeStatus
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.MedicationMilestone
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import cz.medsy.domain.model.TroughAssessment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import cz.medsy.ui.components.MilestonePin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import javax.inject.Inject

data class SeriesPoint(
    val timestamp: Instant,
    val value: Double,
)

data class BiomarkerSeries(
    val code: String,
    val displayName: String,
    val unit: String,
    val points: List<SeriesPoint>,
    val lastValue: Double?,
    val status: RangeStatus,
    val bandMin: Double?,
    val bandMax: Double?,
    /** Primary band first, then overlays. */
    val bands: List<RangeBands.Band> = emptyList(),
    val delta: Double?,
)

data class BloodworkUiState(
    val series: List<BiomarkerSeries> = emptyList(),
    val selectedCode: String? = null,
    val profile: ProfileType = ProfileType.HRT_FEM,
    /** Additional windows drawn on the chart next to the primary profile. */
    val overlayProfiles: Set<ProfileType> = emptySet(),
    val sessions: List<BloodTestSession> = emptyList(),
    val values: List<BiomarkerValue> = emptyList(),
    val trough: TroughAssessment? = null,
    val warnings: List<cz.medsy.domain.message.Message> = emptyList(),
    val pendingImport: LabImportPipeline.StagingResult? = null,
    /** True while a photographed report is being read; the camera step is closed then. */
    val isReadingPhoto: Boolean = false,
    /** Milestone pins for the selected biomarker's chart. */
    val milestones: List<MilestonePin> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class BloodworkViewModel @Inject constructor(
    private val repository: BloodTestRepository,
    private val milestoneRepository: MilestoneRepository,
    private val importPipeline: LabImportPipeline,
    private val ocrExtractor: OcrLabExtractor,
    private val settingsRepository: cz.medsy.data.settings.SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BloodworkUiState())
    val state: StateFlow<BloodworkUiState> = _state.asStateFlow()

    private var customRanges: List<ReferenceRangeProfile> = emptyList()
    private var milestonesByMedication: Map<Long, List<MedicationMilestone>> = emptyMap()

    /**
     * One pin per milestone of the schedules that belong to the selected biomarker's
     * medication. Pins are limited to the chart's time window by the chart itself.
     */
    private fun rebuildMilestones() {
        val milestones = milestonesByMedication.values.flatten()
        val pins = milestones.map { milestone ->
            MilestonePin(
                timestamp = milestone.timestamp,
                label = milestone.message,
                legacyLabel = milestone.description,
            )
        }
        _state.update { it.copy(milestones = pins) }
    }

    init {
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                val ids = sessions.map { it.id }
                val values = if (ids.isEmpty()) emptyList() else repository.valuesForSessions(ids)
                _state.update { it.copy(sessions = sessions, values = values, isLoading = false) }
                rebuildSeries()
            }
        }
        viewModelScope.launch {
            repository.observeRanges().collect { ranges ->
                customRanges = ranges
                rebuildSeries()
            }
        }
        viewModelScope.launch {
            runCatching { repository.preTestWarnings() }.onSuccess { warnings ->
                _state.update { it.copy(warnings = warnings.map { w -> message(MessageKey.BLOODWORK_WARNING_SENTENCE, w.title, w.detail) }) }
            }
        }
        viewModelScope.launch {
            milestoneRepository.observeAll().collect { all ->
                milestonesByMedication = all.groupBy { it.medicationId }
                rebuildMilestones()
            }
        }
        // The chosen therapeutic profile was hardcoded to HRT_FEM and ignored the
        // setting the user had actually saved.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                val profile = runCatching { ProfileType.valueOf(settings.defaultRangeProfile) }
                    .getOrDefault(ProfileType.HRT_FEM)
                if (profile != _state.value.profile) {
                    _state.update { it.copy(profile = profile) }
                    rebuildSeries()
                }
            }
        }
        // A trough assessment is only meaningful against a real draw, so derive it from
        // the newest session instead of waiting for a UI call that never happened.
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                val newest = sessions.maxByOrNull { it.timestamp } ?: return@collect
                val assessment = runCatching { repository.assessTrough(newest.timestamp) }.getOrNull()
                _state.update { it.copy(trough = assessment) }
            }
        }
    }

    private fun rebuildSeries() {
        val current = _state.value
        val sessionsById = current.sessions.associateBy { it.id }
        val grouped = current.values.groupBy { it.biomarkerCode }

        val series = grouped.mapNotNull { (code, values) ->
            val spec = Biomarkers.find(code) ?: return@mapNotNull null
            val points = values.mapNotNull { value ->
                val session = sessionsById[value.sessionId] ?: return@mapNotNull null
                SeriesPoint(session.timestamp, value.normalizedSiValue.toDouble())
            }.sortedBy { it.timestamp }
            if (points.isEmpty()) return@mapNotNull null
            // The most recent draw carries the range that specific lab printed.
            val newest = values.maxByOrNull { value ->
                sessionsById[value.sessionId]?.timestamp ?: Instant.EPOCH
            }
            val labPrinted = cz.medsy.domain.calc.ReferenceRanges.printedRange(
                code = code,
                minSi = newest?.labMin,
                maxSi = newest?.labMax,
            )
            val range = if (current.profile == ProfileType.LAB_PRINTED) {
                labPrinted
            } else {
                cz.medsy.domain.calc.ReferenceRanges.forBiomarker(code, current.profile, customRanges)
            }
            val bands = cz.medsy.domain.calc.RangeBands.build(
                code = code,
                primary = current.profile,
                overlays = current.overlayProfiles,
                customRanges = customRanges,
                labPrinted = labPrinted,
            )
            val last = points.last()
            val evaluation = RangeEvaluator.evaluate(BigDecimal.valueOf(last.value), range)
            val delta = if (points.size >= 2) {
                val previous = points[points.size - 2].value
                if (previous != 0.0) ((last.value - previous) / previous) * 100.0 else null
            } else {
                null
            }
            BiomarkerSeries(
                code = code,
                displayName = spec.displayName,
                unit = spec.siUnit,
                points = points,
                lastValue = last.value,
                status = evaluation.status,
                bandMin = range?.targetMinSi?.toDouble(),
                bandMax = range?.targetMaxSi?.toDouble(),
                bands = bands,
                delta = delta,
            )
        }.sortedBy { it.displayName }

        _state.update { it.copy(series = series, selectedCode = it.selectedCode ?: series.firstOrNull()?.code) }
    }

    fun select(code: String) {
        _state.update { it.copy(selectedCode = code) }
        rebuildMilestones()
    }

    fun setProfile(profile: ProfileType) {
        _state.update { it.copy(profile = profile) }
        rebuildSeries()
    }

    /**
     * Turns an extra window on or off. The primary profile cannot be removed this way —
     * it decides the status colour — so toggling it is a no-op.
     */
    fun toggleOverlay(profile: ProfileType) {
        val current = _state.value
        if (profile == current.profile) return
        val next = if (profile in current.overlayProfiles) {
            current.overlayProfiles - profile
        } else {
            current.overlayProfiles + profile
        }
        _state.update { it.copy(overlayProfiles = next) }
        rebuildSeries()
    }

    fun assessTrough(timestamp: Instant) {
        viewModelScope.launch {
            val assessment = repository.assessTrough(timestamp)
            _state.update { it.copy(trough = assessment) }
        }
    }

    fun importFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            importPipeline.importPdf(uri).onSuccess { result ->
                _state.update { it.copy(pendingImport = result) }
            }
        }
    }

    /** Photographed report. Exhaustive field handling stays in the pipeline, not here. */
    fun importPhoto(uri: android.net.Uri) {
        if (_state.value.isReadingPhoto) return
        viewModelScope.launch {
            _state.update { it.copy(isReadingPhoto = true) }
            val result = ocrExtractor.import(uri)
            _state.update { it.copy(pendingImport = result, isReadingPhoto = false) }
        }
    }

    fun saveImport(timestamp: Instant, labName: String?, fasting: Boolean) {
        val staging = _state.value.pendingImport ?: return
        viewModelScope.launch {
            // Assess at the *draw* time. Using the current trough meant a report entered
            // days later was stamped with today's timing, not the venipuncture's.
            val assessment = runCatching { repository.assessTrough(timestamp) }.getOrNull()
            importPipeline.save(
                rows = staging.rows,
                timestamp = timestamp,
                labName = labName,
                fasting = fasting,
                isTrough = assessment?.isTrough ?: false,
                hoursSinceLastDose = assessment?.hoursSinceLastDose,
                attachmentUri = null,
                source = staging.source,
            )
            _state.update { it.copy(pendingImport = null, trough = assessment) }
        }
    }

    fun discardImport() = _state.update { it.copy(pendingImport = null) }
}
