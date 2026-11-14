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
import cz.medsy.domain.model.LabImportSource
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
    /** Every imported report, newest first: one import is one draw. */
    val history: List<ScanHistoryRow> = emptyList(),
    /** The report being corrected, or null when the editor is closed. */
    val editing: ScanDraft? = null,
    /** The report a confirmation dialog is asking about. */
    val pendingDelete: ScanHistoryRow? = null,
    val isLoading: Boolean = true,
)

/** A row of the history list: what was imported, when, and how many values it brought. */
data class ScanHistoryRow(
    val sessionId: Long,
    val timestamp: Instant,
    val labName: String?,
    val source: LabImportSource,
    val valueCount: Int,
) {
    companion object {
        /**
         * One import is one draw, so the history is the session list — the values only add
         * the count. Draws without values are still shown: an import that matched nothing
         * is exactly the one worth deleting.
         */
        fun from(sessions: List<BloodTestSession>, values: List<BiomarkerValue>): List<ScanHistoryRow> {
            val counts = values.groupingBy { it.sessionId }.eachCount()
            return sessions
                .sortedByDescending { it.timestamp }
                .map { session ->
                    ScanHistoryRow(
                        sessionId = session.id,
                        timestamp = session.timestamp,
                        labName = session.labName,
                        source = session.source,
                        valueCount = counts[session.id] ?: 0,
                    )
                }
        }
    }
}

/** One editable value row; [text] is what the lab printed, not the stored SI number. */
data class ScanValueDraft(
    val id: Long,
    val code: String,
    val displayName: String,
    val text: String,
    val unit: String,
    val invalid: Boolean = false,
)

data class ScanDraft(
    val sessionId: Long,
    val timestamp: Instant,
    val labName: String,
    val fasting: Boolean,
    val values: List<ScanValueDraft>,
) {
    val hasInvalidValues: Boolean get() = values.any { it.invalid }
}

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
                _state.update {
                    it.copy(
                        sessions = sessions,
                        values = values,
                        history = ScanHistoryRow.from(sessions, values),
                        isLoading = false,
                    )
                }
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

    // The history list is the session list: every import landed as one draw, so correcting
    // a report means correcting a session and its values, not re-importing the file.

    fun editScan(sessionId: Long) {
        val current = _state.value
        val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
        val values = current.values
            .filter { it.sessionId == sessionId }
            .sortedBy { Biomarkers.find(it.biomarkerCode)?.displayName ?: it.biomarkerCode }
            .map { value ->
                ScanValueDraft(
                    id = value.id,
                    code = value.biomarkerCode,
                    displayName = Biomarkers.find(value.biomarkerCode)?.displayName
                        ?: value.biomarkerCode,
                    text = value.rawValue.stripTrailingZeros().toPlainString(),
                    unit = value.rawUnit,
                )
            }
        _state.update {
            it.copy(
                pendingDelete = null,
                editing = ScanDraft(
                    sessionId = session.id,
                    timestamp = session.timestamp,
                    labName = session.labName.orEmpty(),
                    fasting = session.fastingStatus,
                    values = values,
                ),
            )
        }
    }

    fun setDraftTimestamp(timestamp: Instant) = updateDraft { it.copy(timestamp = timestamp) }

    fun setDraftLabName(name: String) = updateDraft { it.copy(labName = name) }

    fun setDraftFasting(fasting: Boolean) = updateDraft { it.copy(fasting = fasting) }

    fun setDraftValue(id: Long, text: String) = updateDraft { draft ->
        draft.copy(
            values = draft.values.map { row ->
                if (row.id != id) {
                    row
                } else {
                    // A cleared field is an unfinished deletion, not a typo; only text that
                    // was meant as a number can be wrong.
                    row.copy(text = text, invalid = text.isNotBlank() && parseValue(text) == null)
                }
            },
        )
    }

    fun removeDraftValue(id: Long) = updateDraft { draft ->
        draft.copy(values = draft.values.filterNot { it.id == id })
    }

    private fun updateDraft(transform: (ScanDraft) -> ScanDraft) {
        _state.update { current ->
            current.editing?.let { current.copy(editing = transform(it)) } ?: current
        }
    }

    fun saveScan() {
        val current = _state.value
        val draft = current.editing ?: return
        if (draft.hasInvalidValues) return
        val session = current.sessions.firstOrNull { it.id == draft.sessionId } ?: return
        val originals = current.values
            .filter { it.sessionId == draft.sessionId }
            .associateBy { it.id }
        val kept = draft.values.mapNotNull { row ->
            val original = originals[row.id] ?: return@mapNotNull null
            val raw = parseValue(row.text) ?: return@mapNotNull null
            if (raw.compareTo(original.rawValue) == 0) return@mapNotNull original
            // The edited number is the one the lab printed, so it goes back through the same
            // unit conversion the import used instead of overwriting the SI column directly.
            val converted = repository.normalize(row.code, raw, original.rawUnit)
                ?: return@mapNotNull null
            original.copy(
                rawValue = raw,
                normalizedSiValue = converted.first,
                normalizedSiUnit = converted.second,
            )
        }
        val removed = originals.keys - draft.values.map { it.id }.toSet()
        viewModelScope.launch {
            repository.applyScanEdit(
                session = session.copy(
                    timestamp = draft.timestamp,
                    labName = draft.labName.trim().ifEmpty { null },
                    fastingStatus = draft.fasting,
                ),
                values = kept,
                removedValueIds = removed.toList(),
            )
            _state.update { it.copy(editing = null) }
        }
    }

    fun cancelScanEdit() = _state.update { it.copy(editing = null) }

    fun askDelete(sessionId: Long) = _state.update { current ->
        current.copy(pendingDelete = current.history.firstOrNull { it.sessionId == sessionId })
    }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete() {
        val row = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            // The values go with the session: the foreign key cascades, so there is no
            // second delete to forget.
            repository.deleteSession(row.sessionId)
            _state.update { it.copy(pendingDelete = null) }
        }
    }

    /** Comma decimals are what Czech labs print, so both separators have to parse. */
    private fun parseValue(text: String): BigDecimal? =
        text.trim().replace(',', '.').toBigDecimalOrNull()
}
