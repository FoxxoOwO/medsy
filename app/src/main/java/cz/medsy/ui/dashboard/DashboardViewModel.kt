package cz.medsy.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.InjectionVolumeMissing
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.calc.InteractionAlert
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.domain.bodymap.BodyMap
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.schedule.Occurrence
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DashboardDose(
    val occurrence: Occurrence,
    val medicationName: String,
    val form: cz.medsy.domain.model.MedicationForm,
    val status: DoseStatus?,
    val isDueNow: Boolean,
    val healthcareNote: String?,
)

/**
 * Sites offered for one rotation-tracked medication: the map states plus the site the
 * rotation algorithm suggests. Held per medication because the dashboard may carry an
 * injection and a patch at the same time, each with its own candidate list.
 */
data class SiteChoices(
    val medicationId: Long,
    val form: cz.medsy.domain.model.MedicationForm,
    val recommended: ApplicationSite?,
    val states: List<BodyMap.SiteState>,
)

data class DashboardUiState(
    /** Null until the ticker has produced a time; the screen then shows the plain label. */
    val greeting: cz.medsy.domain.message.Message? = null,
    val todayLabel: String = "",
    val now: String = "",
    val nextDose: DashboardDose? = null,
    val timeUntilNext: Duration? = null,
    val dosesToday: List<DashboardDose> = emptyList(),
    val adherencePercent: Int = 0,
    val adherenceTaken: Int = 0,
    val adherenceTotal: Int = 0,
    val stockAlerts: List<InventoryCalculator.StockLine> = emptyList(),
    val interactionAlerts: List<InteractionAlert> = emptyList(),
    val lastBloodTest: Instant? = null,
    val recommendedSite: ApplicationSite? = null,
    val siteChoices: List<SiteChoices> = emptyList(),
    val discreetMode: Boolean = false,
    /** Set when a PRN dose would exceed the daily limit and needs confirmation. */
    /** The patch currently on the skin, if any. */
    val activePatch: ActivePatch? = null,
    /** A sublingual dose currently being held under the tongue. */
    val activeHold: ActiveHold? = null,
    val pendingLimitWarning: PrnLimitWarning? = null,
    /** A dose that could not be logged safely (e.g. an injection without a volume). */
    val blockingError: cz.medsy.domain.message.Message? = null,
    /** High UV today combined with a photosensitising medication; null hides the card. */
    val uvWarning: cz.medsy.domain.calc.UvWarning? = null,
    /** Scanned eRecepts expiring within the warning window; empty hides the card. */
    val expiringPrescriptions: List<cz.medsy.domain.prescription.QueuedPrescription> = emptyList(),
    val isLoading: Boolean = true,
) {
    /** Rotation choices for the medication behind [dose], or null when rotation is off. */
    fun siteChoicesFor(medicationId: Long): SiteChoices? =
        siteChoices.firstOrNull { it.medicationId == medicationId }
}

data class ActiveHold(
    val medicationName: String,
    val startedAt: Instant,
    val endsAt: Instant,
    val remaining: Duration,
    val label: String,
    val progress: Float,
    val isFinished: Boolean,
)

data class ActivePatch(
    val doseLogId: Long,
    val medicationName: String,
    val state: cz.medsy.domain.model.PatchState,
    val wornUntil: Instant,
    val description: cz.medsy.domain.message.Message,
    val askForRemoval: Boolean,
)

data class PrnLimitWarning(
    val dose: DashboardDose,
    val status: DoseStatus,
    val message: cz.medsy.domain.message.Message,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medicationRepository: MedicationRepository,
    private val doseRepository: DoseRepository,
    private val settingsRepository: SettingsRepository,
    private val uvRepository: cz.medsy.data.uv.UvRepository,
    private val loader: DashboardDataLoader,
) : ViewModel() {

    /** Latest UV inputs. Combined with the ticker and flatMapLatest so a slow lookup is cancelled. */
    private data class UvInput(val city: String?, val enabled: Boolean)

    private val uvInput = MutableStateFlow(UvInput(null, true))

    private val ticker = MutableStateFlow(Instant.now())
    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Volatile private var schedulesById: Map<Long, cz.medsy.domain.model.Schedule> = emptyMap()
    /** Needed by the UV flow: photosensitivity depends on the current regimen. */
    @Volatile private var currentMedications: List<Medication> = emptyList()

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                ticker.value = Instant.now()
                kotlinx.coroutines.delay(30_000)
            }
        }
        viewModelScope.launch {
            combine(
                medicationRepository.observeActive(),
                medicationRepository.observeActiveSchedules(),
                ticker,
            ) { meds, schedules, _ -> meds to schedules }
                .collect { (meds, schedules) -> refresh(meds, schedules) }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                uvInput.value = UvInput(settings.uvCity, settings.uvWarningsEnabled)
                _uiState.update { it.copy(discreetMode = settings.discreetMode) }
            }
        }
        // One UV job at a time: the 30-second ticker used to launch a fresh coroutine on
        // every refresh, so a slow network left several lookups writing stale cards.
        viewModelScope.launch {
            combine(uvInput, ticker) { input, _ -> input }
                .flatMapLatest { input ->
                    flow {
                        val city = input.city
                        val warning = if (!input.enabled || city.isNullOrBlank()) {
                            null
                        } else {
                            runCatching { uvRepository.current(city) }.getOrNull()
                                ?.let { reading ->
                                    cz.medsy.domain.calc.Photosensitivity.uvWarning(
                                        reading.uvIndex,
                                        currentMedications,
                                    )
                                }
                        }
                        emit(warning)
                    }.flowOn(Dispatchers.IO)
                }
                .collect { warning -> _uiState.update { it.copy(uvWarning = warning) } }
        }
    }

    private suspend fun refresh(medications: List<Medication>, schedules: List<Schedule>) {
        schedulesById = schedules.associateBy { it.id }
        currentMedications = medications
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val snapshot = loader.load(
            medications = medications,
            schedules = schedules,
            now = now,
            zone = zone,
            today = today,
            dayStart = today.atStartOfDay(zone).toInstant(),
            dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        _uiState.update {
            it.copy(
                greeting = greetingFor(now),
                todayLabel = today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d. MMMM")),
                now = now.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                nextDose = snapshot.next,
                timeUntilNext = snapshot.timeUntil,
                dosesToday = snapshot.doses,
                adherencePercent = snapshot.adherence?.percent?.toInt() ?: 0,
                adherenceTaken = snapshot.adherence?.taken ?: 0,
                adherenceTotal = snapshot.adherence?.total ?: 0,
                stockAlerts = snapshot.stock,
                interactionAlerts = snapshot.alerts,
                recommendedSite = snapshot.recommendedSite,
                siteChoices = snapshot.siteChoices,
                activePatch = snapshot.activePatch,
                expiringPrescriptions = snapshot.expiring,
                isLoading = false,
            )
        }
    }

    fun logDose(
        dose: DashboardDose,
        status: DoseStatus,
        site: ApplicationSite? = null,
        allowOverLimit: Boolean = false,
    ) {
        viewModelScope.launch {
            runCatching {
                // The repository derives the injectable volume from concentration when it
                // is not supplied, so passing null here keeps a single source of truth.
                doseRepository.log(
                    occurrence = dose.occurrence,
                    status = status,
                    actualDoseAmount = dose.occurrence.doseAmount,
                    site = site,
                    allowOverLimit = allowOverLimit,
                )
            }.onFailure { error ->
                if (error is InjectionVolumeMissing) {
                    // Nothing was written. Ask the patient to add a concentration or volume.
                    _uiState.update { it.copy(blockingError = error.userMessage) }
                    return@launch
                }
                if (error is cz.medsy.data.repository.PrnLimitExceeded) {
                    // Ask rather than refuse: the patient may have a reason, but the
                    // ceiling must not be crossed silently.
                    _uiState.update { current ->
                        current.copy(
                            pendingLimitWarning = PrnLimitWarning(
                                dose = dose,
                                status = status,
                                message = error.limitMessage,
                            ),
                        )
                    }
                    return@launch
                }
                throw error
            }
            // A sublingual dose starts a hold timer. It is advisory: the dose is already
            // saved, so the timer never blocks anything.
            if (status == DoseStatus.TAKEN || status == DoseStatus.LATE) {
                val schedule = schedulesById[dose.occurrence.scheduleId]
                if (cz.medsy.domain.calc.HoldTimer.appliesTo(dose.form)) {
                    val startedAt = Instant.now()
                    _uiState.update {
                        it.copy(
                            activeHold = ActiveHold(
                                medicationName = dose.medicationName,
                                startedAt = startedAt,
                                endsAt = cz.medsy.domain.calc.HoldTimer.endsAt(startedAt, schedule),
                                remaining = cz.medsy.domain.calc.HoldTimer.holdDuration(schedule),
                                label = cz.medsy.domain.calc.HoldTimer.formatRemaining(
                                    cz.medsy.domain.calc.HoldTimer.holdDuration(schedule),
                                ),
                                progress = 0f,
                                isFinished = false,
                            ),
                        )
                    }
                }
            }

            _uiState.update { it.copy(pendingLimitWarning = null) }
            ticker.value = Instant.now()
            refresh(
                medicationRepository.observeActive().first(),
                medicationRepository.activeSchedules(),
            )
        }
    }

    /**
     * Recomputes the hold from its start instant. Driven by the UI's frame clock rather
     * than a coroutine per second, so it stays correct after the app was backgrounded.
     */
    fun tickHold(now: Instant = Instant.now()) {
        val hold = _uiState.value.activeHold ?: return
        val schedule = schedulesById.values.firstOrNull { it.holdMinutes != null }
            ?: schedulesById.values.firstOrNull()
        val remaining = cz.medsy.domain.calc.HoldTimer.remaining(hold.startedAt, schedule, now)
        _uiState.update {
            it.copy(
                activeHold = hold.copy(
                    remaining = remaining,
                    label = cz.medsy.domain.calc.HoldTimer.formatRemaining(remaining),
                    progress = cz.medsy.domain.calc.HoldTimer.progress(hold.startedAt, schedule, now),
                    isFinished = cz.medsy.domain.calc.HoldTimer.isFinished(hold.startedAt, schedule, now),
                ),
            )
        }
    }

    fun dismissHold() {
        _uiState.update { it.copy(activeHold = null) }
    }

    fun markPatchRemoved() {
        val patch = _uiState.value.activePatch ?: return
        viewModelScope.launch {
            runCatching { doseRepository.markPatchRemoved(patch.doseLogId) }
            ticker.value = Instant.now()
            refresh(
                medicationRepository.observeActive().first(),
                medicationRepository.activeSchedules(),
            )
        }
    }

    fun confirmOverLimit() {
        val warning = _uiState.value.pendingLimitWarning ?: return
        _uiState.update { it.copy(pendingLimitWarning = null) }
        logDose(warning.dose, warning.status, allowOverLimit = true)
    }

    fun dismissLimitWarning() {
        _uiState.update { it.copy(pendingLimitWarning = null) }
    }

    fun dismissBlockingError() {
        _uiState.update { it.copy(blockingError = null) }
    }

    fun snooze(dose: DashboardDose) {
        viewModelScope.launch {
            val until = Instant.now().plusSeconds(15 * 60)
            doseRepository.snooze(
                dose.occurrence.medicationId,
                dose.occurrence.scheduleId,
                dose.occurrence.dueAt,
                until,
            )
            ticker.value = Instant.now()
        }
    }

    private fun greetingFor(now: Instant): cz.medsy.domain.message.Message {
        val hour = now.atZone(zone).hour
        return cz.medsy.domain.message.message(
            when (hour) {
                in 5..11 -> cz.medsy.domain.message.MessageKey.DASHBOARD_GREETING_MORNING
                in 12..17 -> cz.medsy.domain.message.MessageKey.DASHBOARD_GREETING_AFTERNOON
                else -> cz.medsy.domain.message.MessageKey.DASHBOARD_GREETING_EVENING
            },
        )
    }
}

