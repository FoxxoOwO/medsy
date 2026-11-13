package cz.medsy.data.repository

import cz.medsy.data.local.dao.ConsumableDao
import cz.medsy.data.local.dao.DoseLogDao
import cz.medsy.data.local.dao.InventoryDao
import cz.medsy.data.local.dao.MedicationDao
import cz.medsy.data.local.dao.MilestoneDao
import cz.medsy.data.local.dao.ScheduleDao
import cz.medsy.data.mapper.toDomain
import cz.medsy.data.mapper.toEntity
import cz.medsy.domain.calc.AdherenceCalculator
import cz.medsy.domain.schedule.ScheduleEngine
import cz.medsy.domain.calc.VialCalculator
import cz.medsy.domain.model.AdherenceSummary
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.DoseLog
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.milestone.MilestoneDiff
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationMilestone
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.scanner.Gtin
import cz.medsy.domain.schedule.Occurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicationRepository @Inject constructor(
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val milestoneDao: MilestoneDao,
) {
    fun observeActive(): Flow<List<Medication>> =
        medicationDao.observeActive().map { list -> list.map { it.toDomain() } }

    fun observeAll(): Flow<List<Medication>> =
        medicationDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeById(id: Long): Flow<Medication?> =
        medicationDao.observeById(id).map { it?.toDomain() }

    suspend fun getById(id: Long): Medication? = medicationDao.getById(id)?.toDomain()

    /**
     * Looks the medication up by any packaging indicator of the same GTIN, not just the exact
     * scanned string. A single-unit box (indicator 0) and a multipack (indicator 1–8) are the same
     * product, and without this the user rescanned a box the app already knew.
     */
    suspend fun findByGtin(gtin: String): Medication? {
        val candidates = Gtin.variantsOf(gtin)
        if (candidates.isEmpty()) return null
        return medicationDao.findByAnyGtin(candidates)?.toDomain()
    }

    suspend fun save(medication: Medication): Long {
        val entity = medication.toEntity()
        return if (entity.id == 0L) medicationDao.insert(entity) else {
            medicationDao.update(entity)
            entity.id
        }
    }

    suspend fun archive(id: Long) = medicationDao.setArchived(id, true)

    suspend fun delete(id: Long) = medicationDao.delete(id)

    fun observeSchedules(medicationId: Long): Flow<List<Schedule>> =
        scheduleDao.observeForMedication(medicationId).map { list -> list.map { it.toDomain() } }

    /**
     * Saves a schedule and records a milestone for every clinical change.
     *
     * Milestones are the only way the bloodwork charts can explain a jump, so they are
     * written here rather than in the UI: every caller (editor, scanner, future imports)
     * gets them for free and none can forget.
     */
    suspend fun saveSchedule(schedule: Schedule): Long {
        val previous = schedule.id.takeIf { it > 0 }
            ?.let { scheduleDao.getById(it) }
            ?.toDomain()

        val entity = schedule.toEntity()
        val id = if (entity.id == 0L) {
            scheduleDao.insert(entity)
        } else {
            scheduleDao.update(entity)
            entity.id
        }

        val saved = if (id > 0 && id != entity.id) entity.copy(id = id) else entity
        recordMilestones(schedule.medicationId, MilestoneDiff.between(previous, saved.toDomain()))
        return id
    }

    suspend fun deleteSchedule(id: Long) {
        val existing = scheduleDao.getById(id)?.toDomain()
        scheduleDao.delete(id)
        if (existing != null) {
            recordMilestones(existing.medicationId, listOf(MilestoneDiff.stopped(existing)))
        }
    }

    suspend fun setScheduleActive(id: Long, active: Boolean) {
        val previous = scheduleDao.getById(id)?.toDomain()
        scheduleDao.setActive(id, active)
        val current = scheduleDao.getById(id)?.toDomain() ?: return
        if (previous != null) {
            recordMilestones(current.medicationId, MilestoneDiff.between(previous, current))
        }
    }

    private suspend fun recordMilestones(
        medicationId: Long,
        changes: List<MilestoneDiff.Change>,
    ) {
        changes.forEach { change ->
            milestoneDao.insert(
                MedicationMilestone(
                    medicationId = medicationId,
                    timestamp = Instant.now(),
                    changeType = change.changeType,
                    // New rows carry the key; the legacy column stays empty on purpose.
                    description = "",
                    message = change.message,
                ).toEntity(),
            )
        }
    }

    suspend fun activeSchedules(): List<Schedule> = scheduleDao.getActive().map { it.toDomain() }

    fun observeActiveSchedules(): Flow<List<Schedule>> =
        scheduleDao.observeActive().map { list -> list.map { it.toDomain() } }
}

/** Thrown when a PRN dose would exceed the schedule's daily maximum. */
class PrnLimitExceeded(val decision: cz.medsy.domain.calc.PrnLimit.Decision.WouldExceed) :
    IllegalStateException(decision.toString()) {

    /** Rendered by the caller, which has the resources. */
    val limitMessage: cz.medsy.domain.message.Message =
        cz.medsy.domain.calc.PrnLimit.describe(decision)
}

@Singleton
class DoseRepository @Inject constructor(
    private val doseLogDao: DoseLogDao,
    private val medicationDao: MedicationDao,
    private val scheduleDao: ScheduleDao,
    private val inventoryDao: InventoryDao,
    private val consumableDao: ConsumableDao,
) {

    fun observeBetween(from: Instant, to: Instant): Flow<List<DoseLog>> =
        doseLogDao.observeBetween(from.toEpochMilli(), to.toEpochMilli())
            .map { list -> list.map { it.toDomain() } }

    fun observeRecent(medicationId: Long): Flow<List<DoseLog>> =
        doseLogDao.observeRecentForMedication(medicationId).map { list -> list.map { it.toDomain() } }

    suspend fun lastTaken(): DoseLog? = doseLogDao.lastTaken()?.toDomain()

    /**
     * Last taken dose for one schedule. Scoped by schedule id in SQL: the previous
     * implementation fetched the medication's newest dose and then rejected it when
     * it belonged to a different schedule, so a medication with two schedules
     * (morning + evening) reported "no previous dose" and its trough was never
     * calculated.
     */
    suspend fun lastTakenForSchedule(medicationId: Long, scheduleId: Long): DoseLog? =
        doseLogDao.lastTakenForScheduleId(scheduleId)?.toDomain()

    suspend fun recentSiteHistory(medicationId: Long?): List<Pair<ApplicationSite, Instant>> {
        val rows = if (medicationId == null) doseLogDao.recentSites() else doseLogDao.recentSitesForMedication(medicationId)
        return rows.mapNotNull { row ->
            val site = row.applicationSite?.let { runCatching { ApplicationSite.valueOf(it) }.getOrNull() }
            val at = row.actualTimestamp ?: row.scheduledTimestamp
            site?.let { it to Instant.ofEpochMilli(at) }
        }
    }

    /**
     * Logs a dose and applies all side effects atomically from the caller's
     * perspective: inventory deduction (dose volume + dead space for injectables,
     * plain units otherwise), consumable deduction for injections, and a
     * first-puncture date stamp so the BUD countdown starts.
     *
     * When [volumeMl] is not supplied for an injectable (e.g. a notification action),
     * the volume is derived from the medication's concentration. Without this the vials
     * would be deducted by the dose in mg — a 50x inventory error for a 50 mg/mL vial.
     */
    suspend fun log(
        occurrence: Occurrence,
        status: DoseStatus,
        actualTime: Instant = Instant.now(),
        actualDoseAmount: BigDecimal? = null,
        volumeMl: BigDecimal? = null,
        site: ApplicationSite? = null,
        notes: String? = null,
        /** Set only after the user explicitly confirmed exceeding a PRN daily limit. */
        allowOverLimit: Boolean = false,
    ): Long {
        val medication = medicationDao.getById(occurrence.medicationId)
        val schedule = scheduleDao.getById(occurrence.scheduleId)
        val effectiveDose = actualDoseAmount ?: occurrence.doseAmount

        // A PRN ceiling only means something if it is enforced before the write.
        if (!allowOverLimit && (status == DoseStatus.TAKEN || status == DoseStatus.LATE)) {
            checkPrnLimit(occurrence.scheduleId, effectiveDose, actualTime)?.let { throw it }
        }
        val form = medication?.form?.let { runCatching { MedicationForm.valueOf(it) }.getOrNull() }
        val isPrn = schedule?.scheduleType == "PRN"
        val effectiveVolume = when {
            volumeMl != null -> volumeMl
            status == DoseStatus.TAKEN || status == DoseStatus.LATE ->
                deriveVolumeMl(medication?.toDomain(), effectiveDose)
            else -> null
        }

        // A patch is a state, not an event: only an actually applied one enters the
        // lifecycle, and it carries the time it must come off.
        val patch = if (form == MedicationForm.PATCH &&
            (status == DoseStatus.TAKEN || status == DoseStatus.LATE) &&
            schedule != null
        ) {
            cz.medsy.domain.patch.PatchLifecycle.onApplied(
                appliedAt = actualTime,
                schedule = schedule.toDomain(),
            )
        } else {
            null
        }

        val entity = DoseLog(
            medicationId = occurrence.medicationId,
            scheduleId = occurrence.scheduleId,
            scheduledTimestamp = occurrence.dueAt,
            actualTimestamp = if (status == DoseStatus.SKIPPED) null else actualTime,
            status = status,
            actualDoseAmount = effectiveDose,
            actualVolumeMl = effectiveVolume,
            applicationSite = site,
            notes = notes,
            isPrn = isPrn,
            patchState = patch?.state,
            wornUntil = patch?.wornUntil,
        ).toEntity()

        val id = doseLogDao.upsert(entity)

        if (status == DoseStatus.TAKEN || status == DoseStatus.LATE) {
            applyInventory(
                medicationId = occurrence.medicationId,
                form = form,
                dose = effectiveDose,
                volumeMl = effectiveVolume,
                at = actualTime,
                schedule = schedule?.toDomain(),
            )
        }
        return id
    }

    /**
     * Returns the violation when the dose would exceed the schedule's PRN daily maximum,
     * or null when it is fine (or the schedule sets no limit).
     */
    suspend fun checkPrnLimit(
        scheduleId: Long,
        dose: BigDecimal,
        at: Instant = Instant.now(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): PrnLimitExceeded? {
        val schedule = scheduleDao.getById(scheduleId)?.toDomain() ?: return null
        if (schedule.scheduleType != cz.medsy.domain.model.ScheduleType.PRN) return null
        val limit = schedule.maxDailyDose ?: return null

        val dayStart = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val dayEnd = at.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        val logs = doseLogDao
            .forScheduleBetween(scheduleId, dayStart.toEpochMilli(), dayEnd.toEpochMilli())
            .map { it.toDomain() }

        val decision = cz.medsy.domain.calc.PrnLimit.check(
            logs = logs,
            scheduleId = scheduleId,
            limit = limit,
            dose = dose,
            at = at,
            zone = zone,
        )
        return (decision as? cz.medsy.domain.calc.PrnLimit.Decision.WouldExceed)
            ?.let(::PrnLimitExceeded)
    }

    private suspend fun applyInventory(
        medicationId: Long,
        form: MedicationForm?,
        dose: BigDecimal,
        volumeMl: BigDecimal?,
        at: Instant,
        schedule: cz.medsy.domain.model.Schedule? = null,
    ) {
        val puncturedEpochDay = LocalDate.now().toEpochDay()
        when {
            form != null && form.isInjectable -> {
                val volume = volumeMl ?: dose
                val batches = inventoryDao.dispensibleBatches(medicationId)
                val deadSpace = batches.firstOrNull { it.firstPuncturedDateEpochDay != null }
                    ?.deadSpaceMlPerDose
                    ?: batches.firstOrNull()?.deadSpaceMlPerDose
                    ?: 0.0
                val deduct = volume.add(BigDecimal.valueOf(deadSpace)).toDouble()
                inventoryDao.deduct(medicationId, deduct, puncturedEpochDay)
                deductConsumables(medicationId)
            }
            // Gels and sprays are counted in actuations: a two-pump dose must remove two
            // units from the bottle, not one "dose".
            form != null && cz.medsy.domain.calc.MeteredDose.isMeteredDose(form) ->
                inventoryDao.deduct(
                    medicationId,
                    cz.medsy.domain.calc.MeteredDose.deductForDose(schedule).toDouble(),
                    puncturedEpochDay,
                )
            else -> inventoryDao.deduct(medicationId, dose.toDouble(), puncturedEpochDay)
        }
    }

    private suspend fun deductConsumables(medicationId: Long) {
        val linked = consumableDao.getLinkedTo(medicationId)
        if (linked.isEmpty()) return
        val counts = cz.medsy.domain.calc.InventoryCalculator.consumablesPerInjection()
        linked.forEach { consumable ->
            val explicit = consumable.deductPerDose.takeIf { it > 0 } ?: 1
            val byKind = cz.medsy.domain.calc.InventoryCalculator.consumableKind(consumable.name)
                ?.let { counts[it] } ?: explicit
            consumableDao.deduct(consumable.id, byKind)
        }
    }

    /**
     * Marks a patch as removed. The log keeps its state so the history shows that the
     * patch was taken off rather than silently still on the skin.
     */
    suspend fun markPatchRemoved(doseLogId: Long, removedAt: Instant = Instant.now()): Boolean {
        val existing = doseLogDao.getById(doseLogId) ?: return false
        val domain = existing.toDomain()
        if (domain.patchState == null) return false
        doseLogDao.update(
            domain.copy(patchState = cz.medsy.domain.model.PatchState.REMOVED).toEntity(),
        )
        return true
    }

    /** The patch currently on the skin for a medication, if any. */
    suspend fun currentPatch(medicationId: Long): DoseLog? =
        doseLogDao.latestPatch(medicationId)?.toDomain()?.takeIf {
            it.patchState == cz.medsy.domain.model.PatchState.APPLIED ||
                it.patchState == cz.medsy.domain.model.PatchState.WORN ||
                it.patchState == cz.medsy.domain.model.PatchState.REMOVAL_DUE
        }

    suspend fun snooze(medicationId: Long, scheduleId: Long, scheduled: Instant, until: Instant): Long {
        val existing = doseLogDao.findAt(medicationId, scheduled.toEpochMilli())
        val entity = (existing?.toDomain() ?: DoseLog(
            medicationId = medicationId,
            scheduleId = scheduleId,
            scheduledTimestamp = scheduled,
            actualTimestamp = null,
            status = DoseStatus.SNOOZED,
            actualDoseAmount = null,
            actualVolumeMl = null,
            applicationSite = null,
        )).copy(status = DoseStatus.SNOOZED).toEntity()
        return doseLogDao.upsert(entity)
    }

    suspend fun adherence(from: Instant, to: Instant, expected: Int): AdherenceSummary {
        val taken = doseLogDao.countTaken(from.toEpochMilli(), to.toEpochMilli())
        val skipped = doseLogDao.countSkipped(from.toEpochMilli(), to.toEpochMilli())
        return AdherenceCalculator.summarize(taken, skipped, expected)
    }

    suspend fun expectedDoses(from: Instant, to: Instant, zone: ZoneId): Int {
        val schedules = scheduleDao.getActive().map { it.toDomain() }
        return schedules.sumOf { schedule -> ScheduleEngine.occurrences(schedule, from, to, zone).size }
    }

    /**
     * Volume that must be drawn for [doseMg]; dead space is added later by
     * [applyInventory] so it is never counted twice.
     */
    suspend fun calculateVolume(medicationId: Long, doseMg: BigDecimal): BigDecimal? {
        val medication = medicationDao.getById(medicationId)?.toDomain() ?: return null
        return deriveVolumeMl(medication, doseMg)
    }

    private fun deriveVolumeMl(medication: Medication?, doseMg: BigDecimal): BigDecimal? {
        val concentration = medication?.concentrationMgPerMl ?: return null
        if (concentration.signum() <= 0 || doseMg.signum() <= 0) return null
        return VialCalculator.doseToVolumeMl(doseMg, concentration)
    }
}
