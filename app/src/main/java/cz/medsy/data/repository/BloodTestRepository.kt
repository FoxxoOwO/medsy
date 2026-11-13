package cz.medsy.data.repository

import cz.medsy.data.local.dao.BloodTestDao
import cz.medsy.data.local.dao.MedicationDao
import cz.medsy.data.local.dao.MilestoneDao
import cz.medsy.data.local.dao.ScheduleDao
import cz.medsy.data.local.dao.SymptomDao
import cz.medsy.data.mapper.toDomain
import cz.medsy.data.mapper.toEntity
import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.BloodworkCalculator
import cz.medsy.domain.schedule.ScheduleEngine
import cz.medsy.domain.calc.ReferenceRanges
import cz.medsy.domain.calc.UnitConverter
import cz.medsy.domain.calc.VialCalculator
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.MedicationMilestone
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import cz.medsy.domain.model.Symbology
import cz.medsy.domain.model.TroughAssessment
import cz.medsy.domain.model.SymptomLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BloodTestRepository @Inject constructor(
    private val bloodTestDao: BloodTestDao,
    private val scheduleDao: ScheduleDao,
    private val doseRepository: DoseRepository,
    private val medicationDao: MedicationDao,
) {

    fun observeSessions(): Flow<List<BloodTestSession>> =
        bloodTestDao.observeSessions().map { list -> list.map { it.toDomain() } }

    fun observeValues(code: String): Flow<List<BiomarkerValue>> =
        bloodTestDao.observeValuesForBiomarker(code).map { list -> list.map { it.toDomain() } }

    fun observeRanges(): Flow<List<ReferenceRangeProfile>> =
        bloodTestDao.observeRanges().map { list -> list.map { it.toDomain() } }

    suspend fun sessionAt(timestamp: Instant): BloodTestSession? =
        bloodTestDao.findSessionAt(timestamp.toEpochMilli())?.toDomain()

    suspend fun saveSession(
        session: BloodTestSession,
        values: List<BiomarkerValue>,
    ): Long = bloodTestDao.saveSessionWithValues(
        session = session.toEntity(),
        values = values.map { it.toEntity() },
    )

    suspend fun valuesForSessions(ids: List<Long>): List<BiomarkerValue> =
        bloodTestDao.valuesForSessions(ids).map { it.toDomain() }

    suspend fun recentSessions(limit: Int = 12): List<BloodTestSession> =
        bloodTestDao.recentSessions(limit).map { it.toDomain() }

    suspend fun deleteSession(id: Long) = bloodTestDao.deleteSession(id)

    suspend fun saveRange(range: ReferenceRangeProfile): Long =
        bloodTestDao.insertRange(range.toEntity())

    /** Removes a stored range, e.g. when the user deletes their custom doctor target. */
    suspend fun deleteRange(code: String, type: ProfileType) =
        bloodTestDao.deleteRange(code, type.name)

    suspend fun rangeFor(code: String, profile: ProfileType, custom: List<ReferenceRangeProfile> = emptyList()): ReferenceRangeProfile? =
        ReferenceRanges.forBiomarker(code, profile, custom)

    /**
     * Normalizes a raw value from a lab report into SI, tolerating the units Czech
     * labs actually print (pg/mL, ng/dL, mIU/L, µkat/L, U/L, %).
     */
    fun normalize(code: String, rawValue: BigDecimal, rawUnit: String): Triple<BigDecimal, String, String>? {
        val spec = Biomarkers.find(code) ?: return null
        val unit = rawUnit.trim()
        val siValue = runCatching { UnitConverter.convert(spec, rawValue, unit) }.getOrNull() ?: return null
        return Triple(siValue, spec.siUnit, unit)
    }

    suspend fun assessTrough(drawTime: Instant, zone: ZoneId = ZoneId.systemDefault()): TroughAssessment {
        val schedules = scheduleDao.getActive().map { it.toDomain() }
        val hrtSchedules = schedules.filter { schedule ->
            val medication = medicationDao.getById(schedule.medicationId)?.toDomain()
            medication?.isHrt == true
        }
        val intervalHours = BloodworkCalculator.nominalIntervalHours(hrtSchedules)
        val doseRepo = doseRepository
        val lastDose = hrtSchedules.mapNotNull { schedule ->
            doseRepo.lastTakenForSchedule(schedule.medicationId, schedule.id)
        }.maxByOrNull { it.actualTimestamp ?: it.scheduledTimestamp }
        val nextDose = hrtSchedules.mapNotNull { schedule ->
            ScheduleEngine.nextOccurrence(schedule, drawTime, zone)
        }.minByOrNull { it.dueAt }

        return BloodworkCalculator.assess(
            drawTime = drawTime,
            lastDoseTime = lastDose?.actualTimestamp ?: lastDose?.scheduledTimestamp,
            nextDoseTime = nextDose?.dueAt,
            nominalIntervalHours = intervalHours,
        )
    }

    suspend fun preTestWarnings(drawHour: Int = 8): List<BloodworkCalculator.PreTestWarning> {
        val schedules = scheduleDao.getActive().map { it.toDomain() }
        return BloodworkCalculator.preTestWarnings(schedules, drawHour)
    }

    suspend fun seedDefaultRangesIfEmpty() {
        val existing = bloodTestDao.getRanges().map { it.toDomain() }
        val known = existing.map { it.biomarkerCode to it.profileType }.toSet()
        ReferenceRanges.defaults
            .filter { (it.biomarkerCode to it.profileType) !in known }
            .forEach { bloodTestDao.insertRange(it.toEntity()) }
    }
}

@Singleton
class MilestoneRepository @Inject constructor(
    private val milestoneDao: MilestoneDao,
) {
    fun observeAll(): Flow<List<MedicationMilestone>> =
        milestoneDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(milestone: MedicationMilestone): Long = milestoneDao.insert(milestone.toEntity())

    suspend fun all(): List<MedicationMilestone> = milestoneDao.getAll().map { it.toDomain() }
}

@Singleton
class SymptomRepository @Inject constructor(
    private val symptomDao: SymptomDao,
) {
    fun observeBetween(from: Instant, to: Instant): Flow<List<SymptomLog>> =
        symptomDao.observeBetween(from.toEpochMilli(), to.toEpochMilli())
            .map { list -> list.map { it.toDomain() } }

    suspend fun recent(limit: Int = 200): List<SymptomLog> =
        symptomDao.recent(limit).map { it.toDomain() }

    suspend fun add(log: SymptomLog): Long = symptomDao.insert(log.toEntity())

    suspend fun delete(id: Long) = symptomDao.delete(id)
}
