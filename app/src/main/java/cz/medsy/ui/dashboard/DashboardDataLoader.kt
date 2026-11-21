package cz.medsy.ui.dashboard

import android.content.Context
import cz.medsy.data.repository.DoseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import cz.medsy.data.repository.InventoryRepository
import cz.medsy.data.repository.PrescriptionRepository
import cz.medsy.domain.bodymap.BodyMap
import cz.medsy.domain.calc.SiteRotation
import cz.medsy.domain.calc.InteractionAlert
import cz.medsy.domain.calc.InteractionEngine
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.ui.message.asText
import cz.medsy.domain.model.AdherenceSummary
import cz.medsy.domain.model.DoseLog
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.patch.PatchLifecycle
import cz.medsy.domain.prescription.QueuedPrescription
import cz.medsy.domain.schedule.ScheduleEngine
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the dashboard shows that is derived from the database. Kept out of the ViewModel
 * so `refresh()` is just "load a snapshot, publish it" and each calculation can be tested
 * without a Hilt graph or a 30-second ticker.
 */
@Singleton
class DashboardDataLoader @Inject constructor(
    private val doseRepository: DoseRepository,
    private val inventoryRepository: InventoryRepository,
    private val prescriptionRepository: PrescriptionRepository,
    @ApplicationContext private val context: Context,
) {

    data class Snapshot(
        val doses: List<DashboardDose>,
        val next: DashboardDose?,
        val timeUntil: Duration?,
        val adherence: AdherenceSummary?,
        val stock: List<InventoryCalculator.StockLine>,
        val alerts: List<InteractionAlert>,
        val activePatch: ActivePatch?,
        val siteChoices: List<SiteChoices>,
        val recommendedSite: cz.medsy.domain.model.ApplicationSite?,
        val expiring: List<QueuedPrescription>,
    )

    suspend fun load(
        medications: List<Medication>,
        schedules: List<Schedule>,
        now: Instant,
        zone: ZoneId,
        today: LocalDate,
        dayStart: Instant,
        dayEnd: Instant,
    ): Snapshot {
        val medsById = medications.associateBy { it.id }
        val allOccurrences = schedules.flatMap { schedule ->
            ScheduleEngine.occurrences(schedule, dayStart, dayEnd, zone)
        }.sortedBy { it.dueAt }

        val logged = runCatching { doseRepository.observeBetween(dayStart, dayEnd).first() }
            .getOrDefault(emptyList())
        val logsByKey = logged.associateBy { it.medicationId to it.scheduledTimestamp }

        val doses = allOccurrences.map { occurrence -> occurrence.toDose(medsById, logsByKey, now) }
        val next = doses.firstOrNull { dose ->
            dose.status == null && dose.occurrence.dueAt.isAfter(now.minusSeconds(60))
        }
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

        val activePatch = activePatch(medications, now)
        val siteChoices = siteChoices(schedules, medsById, now)
        val expiring = runCatching { prescriptionRepository.active() }.getOrDefault(emptyList())
            .filter { it.isExpiringSoon(now) }

        return Snapshot(
            doses = doses,
            next = next,
            timeUntil = timeUntil,
            adherence = adherence,
            stock = stock,
            alerts = alerts,
            activePatch = activePatch,
            siteChoices = siteChoices,
            recommendedSite = siteChoices.firstOrNull()?.recommended,
            expiring = expiring,
        )
    }

    private fun cz.medsy.domain.schedule.Occurrence.toDose(
        medsById: Map<Long, Medication>,
        logsByKey: Map<Pair<Long, Instant>, DoseLog>,
        now: Instant,
    ): DashboardDose {
        val medication = medsById[medicationId]
        val log = logsByKey[medicationId to dueAt]
        return DashboardDose(
            occurrence = this,
            medicationName = medication?.name
                ?: Message(MessageKey.DISCREET_MEDICATION).asText(context),
            form = medication?.form ?: MedicationForm.TABLET,
            status = log?.status,
            isDueNow = !dueAt.isAfter(now) && log == null &&
                Duration.between(dueAt, now) < Duration.ofHours(4),
            healthcareNote = null,
        )
    }

    private suspend fun activePatch(medications: List<Medication>, now: Instant): ActivePatch? =
        medications.filter { it.form == MedicationForm.PATCH }
            .mapNotNull { medication ->
                runCatching { doseRepository.currentPatch(medication.id) }.getOrNull()
                    ?.let { patch -> medication to patch }
            }
            .mapNotNull { (medication, patch) ->
                val wornUntil = patch.wornUntil ?: return@mapNotNull null
                val state = PatchLifecycle.removalState(wornUntil, now)
                ActivePatch(
                    doseLogId = patch.id,
                    medicationName = medication.name,
                    state = state,
                    wornUntil = wornUntil,
                    description = PatchLifecycle.describe(state, wornUntil, now),
                    askForRemoval = PatchLifecycle.shouldAskForRemoval(wornUntil, now),
                )
            }
            .firstOrNull()

    private suspend fun siteChoices(
        schedules: List<Schedule>,
        medsById: Map<Long, Medication>,
        now: Instant,
    ): List<SiteChoices> =
        // One entry per rotation-tracked medication, so the picker always shows the site
        // list of the dose being logged rather than of whichever medication came first.
        schedules.filter { it.requireSiteRotation }
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
}
