package cz.medsy.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.medsy.data.repository.DoseRepository
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.MedicationRepository
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.calc.InteractionAlert
import cz.medsy.domain.calc.InteractionEngine
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.domain.bodymap.BodyMap
import cz.medsy.domain.calc.SiteRotation
import cz.medsy.domain.schedule.ScheduleEngine
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.schedule.Occurrence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val medicationRepository: MedicationRepository,
    private val doseRepository: DoseRepository,
    private val inventoryRepository: InventoryRepository,
    private val settingsRepository: SettingsRepository,
    private val uvRepository: cz.medsy.data.uv.UvRepository,
    private val prescriptionRepository: cz.medsy.data.repository.PrescriptionRepository,
) : ViewModel() {

    /** Kept in a field so the UV lookup does not have to re-read DataStore per refresh. */
    private var uvCity: String? = null
    private var uvEnabled: Boolean = true

    private val ticker = MutableStateFlow(Instant.now())
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var schedulesById: Map<Long, cz.medsy.domain.model.Schedule> = emptyMap()

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
                uvCity = settings.uvCity
                uvEnabled = settings.uvWarningsEnabled
                _uiState.update { it.copy(discreetMode = settings.discreetMode) }
            }
        }
    }

    private suspend fun refresh(medications: List<Medication>, schedules: List<Schedule>) {
        schedulesById = schedules.associateBy { it.id }
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()

        val medsById = medications.associateBy { it.id }
        val allOccurrences = schedules.flatMap { schedule ->
            ScheduleEngine.occurrences(schedule, dayStart, dayEnd, zone)
        }.sortedBy { it.dueAt }

        val logged = runCatching { doseRepository.observeBetween(dayStart, dayEnd).first() }
            .getOrDefault(emptyList())
        val logsByKey = logged.associateBy { it.medicationId to it.scheduledTimestamp }

        val doses = allOccurrences.map { occurrence ->
            val medication = medsById[occurrence.medicationId]
            val log = logsByKey[occurrence.medicationId to occurrence.dueAt]
            DashboardDose(
                occurrence = occurrence,
                medicationName = medication?.name ?: "Medication",
                form = medication?.form ?: cz.medsy.domain.model.MedicationForm.TABLET,
                status = log?.status,
                isDueNow = !occurrence.dueAt.isAfter(now) && log == null &&
                    Duration.between(occurrence.dueAt, now) < Duration.ofHours(4),
                healthcareNote = null,
            )
        }

        val next = doses.firstOrNull { it.status == null && it.occurrence.dueAt.isAfter(now.minusSeconds(60)) }
        val timeUntil = next?.let { Duration.between(now, it.occurrence.dueAt) }

        val adherence = runCatching {
            val from = now.minus(Duration.ofDays(30))
            val expected = doseRepository.expectedDoses(from, now, zone)
            doseRepository.adherence(from, now, expected)
        }.getOrNull()

        val stock = runCatching { inventoryRepository.stockLines(today) }.getOrDefault(emptyList())
            .filter { it.isLow || it.beyondUsePassed.isNotEmpty() || it.expiringBatches.isNotEmpty() }

        // A medication counts if it has a substance name OR an ATC code. Filtering on
        // the name alone silently skipped every SÚKL row that carries only an ATC code.
        val alerts = InteractionEngine.evaluate(
            medications.map { med ->
                InteractionEngine.ActiveSubstance(
                    name = med.activeSubstance.orEmpty(),
                    atcCode = med.atcCode,
                    medicationId = med.id,
                )
            },
        )

        val patches = medications.filter { it.form == cz.medsy.domain.model.MedicationForm.PATCH }
            .mapNotNull { medication ->
                runCatching { doseRepository.currentPatch(medication.id) }.getOrNull()
                    ?.let { patch -> medication to patch }
            }
        val activePatch = patches.mapNotNull { (medication, patch) ->
            val wornUntil = patch.wornUntil ?: return@mapNotNull null
            ActivePatch(
                doseLogId = patch.id,
                medicationName = medication.name,
                state = cz.medsy.domain.patch.PatchLifecycle.removalState(wornUntil, now),
                wornUntil = wornUntil,
                description = cz.medsy.domain.patch.PatchLifecycle.describe(
                    cz.medsy.domain.patch.PatchLifecycle.removalState(wornUntil, now),
                    wornUntil,
                    now,
                ),
                askForRemoval = cz.medsy.domain.patch.PatchLifecycle.shouldAskForRemoval(wornUntil, now),
            )
        }.firstOrNull()

        // One entry per rotation-tracked medication, so the picker always shows the site
        // list of the dose being logged rather than of whichever medication came first.
        val siteChoices = schedules.filter { it.requireSiteRotation }
            .distinctBy { it.medicationId }
            .mapNotNull { schedule ->
                val med = medsById[schedule.medicationId] ?: return@mapNotNull null
                val history = runCatching { doseRepository.recentSiteHistory(med.id) }
                    .getOrDefault(emptyList())
                SiteChoices(
                    medicationId = med.id,
                    form = med.form,
                    recommended = SiteRotation.recommend(med.form, history, now)?.site,
                    states = BodyMap.states(med.form, history, now),
                )
            }
        val site = siteChoices.firstOrNull()?.recommended

        val expiring = runCatching { prescriptionRepository.active() }.getOrDefault(emptyList())
            .filter { it.isExpiringSoon(now) }

        // Separate coroutine on purpose: a slow or dead network must never delay the dose
        // list, and a failed lookup simply leaves the previous card (or none) in place.
        viewModelScope.launch {
            val city = uvCity
            val warning = if (!uvEnabled || city.isNullOrBlank()) {
                null
            } else {
                runCatching { uvRepository.current(city) }.getOrNull()
                    ?.let { reading -> cz.medsy.domain.calc.Photosensitivity.uvWarning(reading.uvIndex, medications) }
            }
            _uiState.update { it.copy(uvWarning = warning) }
        }

        _uiState.update {
            it.copy(
                greeting = greetingFor(now),
                todayLabel = today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d. MMMM")),
                now = now.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                nextDose = next,
                timeUntilNext = timeUntil,
                dosesToday = doses,
                adherencePercent = adherence?.percent?.toInt() ?: 0,
                adherenceTaken = adherence?.taken ?: 0,
                adherenceTotal = adherence?.total ?: 0,
                stockAlerts = stock,
                interactionAlerts = alerts,
                recommendedSite = site,
                siteChoices = siteChoices,
                activePatch = activePatch,
                expiringPrescriptions = expiring,
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

