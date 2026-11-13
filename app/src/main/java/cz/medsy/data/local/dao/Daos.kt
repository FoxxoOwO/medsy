package cz.medsy.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cz.medsy.data.local.entity.BiomarkerValueEntity
import cz.medsy.data.local.entity.BloodTestSessionEntity
import cz.medsy.data.local.entity.ConsumableEntity
import cz.medsy.data.local.entity.DoseLogEntity
import cz.medsy.data.local.entity.InventoryBatchEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.MedicationMilestoneEntity
import cz.medsy.data.local.entity.MedicationTranslationEntity
import cz.medsy.data.local.entity.PrescriptionEntity
import cz.medsy.data.local.entity.PrescriptionItemEntity
import cz.medsy.data.local.entity.ReferenceRangeProfileEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.data.local.entity.SuklMedicationEntity
import cz.medsy.data.local.entity.SymptomLogEntity
import androidx.room.Embedded
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {

    @Query("SELECT * FROM medications WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE id = :id")
    fun observeById(id: Long): Flow<MedicationEntity?>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun getById(id: Long): MedicationEntity?

    @Query("SELECT * FROM medications WHERE gtin = :gtin LIMIT 1")
    suspend fun findByGtin(gtin: String): MedicationEntity?

    @Query("SELECT * FROM medications WHERE gtin IN (:gtins) ORDER BY gtin LIMIT 1")
    suspend fun findByAnyGtin(gtins: List<String>): MedicationEntity?

    @Query("SELECT * FROM medications WHERE suklCode = :code LIMIT 1")
    suspend fun findBySuklCode(code: String): MedicationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MedicationEntity): Long

    @Update
    suspend fun update(entity: MedicationEntity)

    @Query("UPDATE medications SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules WHERE isActive = 1")
    fun observeActive(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE isActive = 1")
    suspend fun getActive(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId")
    fun observeForMedication(medicationId: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE medicationId = :medicationId AND isActive = 1")
    suspend fun getForMedication(medicationId: Long): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: Long): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ScheduleEntity): Long

    @Update
    suspend fun update(entity: ScheduleEntity)

    @Query("UPDATE schedules SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DoseLogDao {

    @Query("SELECT * FROM dose_logs WHERE scheduledTimestamp BETWEEN :from AND :to ORDER BY scheduledTimestamp")
    fun observeBetween(from: Long, to: Long): Flow<List<DoseLogEntity>>

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medicationId ORDER BY scheduledTimestamp DESC LIMIT :limit")
    fun observeRecentForMedication(medicationId: Long, limit: Int = 50): Flow<List<DoseLogEntity>>

    @Query("SELECT * FROM dose_logs WHERE scheduledTimestamp >= :from ORDER BY scheduledTimestamp")
    suspend fun getFrom(from: Long): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE status = 'TAKEN' ORDER BY actualTimestamp DESC LIMIT 1")
    suspend fun lastTaken(): DoseLogEntity?

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medicationId AND status = 'TAKEN' AND actualTimestamp IS NOT NULL ORDER BY actualTimestamp DESC LIMIT 1")
    suspend fun lastTakenForMedication(medicationId: Long): DoseLogEntity?

    @Query("SELECT * FROM dose_logs WHERE scheduleId = :scheduleId AND status IN ('TAKEN','LATE') AND actualTimestamp IS NOT NULL ORDER BY actualTimestamp DESC LIMIT 1")
    suspend fun lastTakenForScheduleId(scheduleId: Long): DoseLogEntity?

    @Query("SELECT * FROM dose_logs WHERE applicationSite IS NOT NULL ORDER BY scheduledTimestamp DESC LIMIT :limit")
    suspend fun recentSites(limit: Int = 100): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE applicationSite IS NOT NULL AND medicationId = :medicationId ORDER BY scheduledTimestamp DESC LIMIT :limit")
    suspend fun recentSitesForMedication(medicationId: Long, limit: Int = 50): List<DoseLogEntity>

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medicationId AND scheduledTimestamp = :scheduled LIMIT 1")
    suspend fun findAt(medicationId: Long, scheduled: Long): DoseLogEntity?

    @Query("SELECT * FROM dose_logs WHERE id = :id")
    suspend fun getById(id: Long): DoseLogEntity?

    /** The most recent patch for a medication, so the app can ask about removal. */
    @Query(
        "SELECT * FROM dose_logs WHERE medicationId = :medicationId AND patchState IS NOT NULL " +
            "ORDER BY actualTimestamp DESC LIMIT 1",
    )
    suspend fun latestPatch(medicationId: Long): DoseLogEntity?

    /** Doses of one schedule whose actual (or scheduled) time falls in the window. */
    @Query(
        "SELECT * FROM dose_logs WHERE scheduleId = :scheduleId " +
            "AND COALESCE(actualTimestamp, scheduledTimestamp) BETWEEN :from AND :to",
    )
    suspend fun forScheduleBetween(scheduleId: Long, from: Long, to: Long): List<DoseLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DoseLogEntity): Long

    @Update
    suspend fun update(entity: DoseLogEntity)

    @Query("DELETE FROM dose_logs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM dose_logs WHERE status = 'TAKEN' AND scheduledTimestamp BETWEEN :from AND :to")
    suspend fun countTaken(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM dose_logs WHERE status = 'SKIPPED' AND scheduledTimestamp BETWEEN :from AND :to")
    suspend fun countSkipped(from: Long, to: Long): Int
}

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory_batches WHERE medicationId = :medicationId ORDER BY expirationDateEpochDay IS NULL, expirationDateEpochDay")
    fun observeForMedication(medicationId: Long): Flow<List<InventoryBatchEntity>>

    @Query("SELECT * FROM inventory_batches ORDER BY expirationDateEpochDay IS NULL, expirationDateEpochDay")
    fun observeAll(): Flow<List<InventoryBatchEntity>>

    /**
     * Dispensing order: already-punctured vials first (use them before opening a new
     * one — otherwise the open vial silently expires), then by earliest expiry, with
     * batches that have no expiry date last.
     */
    @Query(
        "SELECT * FROM inventory_batches WHERE medicationId = :medicationId AND remainingQuantity > 0 " +
            "ORDER BY firstPuncturedDateEpochDay IS NULL ASC, expirationDateEpochDay IS NULL ASC, expirationDateEpochDay ASC",
    )
    suspend fun dispensibleBatches(medicationId: Long): List<InventoryBatchEntity>

    @Query("SELECT * FROM inventory_batches WHERE id = :id")
    suspend fun getById(id: Long): InventoryBatchEntity?

    @Insert
    suspend fun insert(entity: InventoryBatchEntity): Long

    @Update
    suspend fun update(entity: InventoryBatchEntity)

    @Query("UPDATE inventory_batches SET remainingQuantity = :remaining, firstPuncturedDateEpochDay = COALESCE(firstPuncturedDateEpochDay, :punctured) WHERE id = :id")
    suspend fun setRemaining(id: Long, remaining: Double, punctured: Long?)

    @Query("DELETE FROM inventory_batches WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun deduct(medicationId: Long, amount: Double, puncturedEpochDay: Long?): Long? {
        var remainingToDeduct = amount
        val batches = dispensibleBatches(medicationId)
        if (batches.isEmpty()) return null
        var lastTouched: Long? = null
        for (batch in batches) {
            if (remainingToDeduct <= 0.0) break
            val available = batch.remainingQuantity
            if (available <= 0.0) continue
            val take = minOf(available, remainingToDeduct)
            val newRemaining = available - take
            setRemaining(batch.id, newRemaining, puncturedEpochDay)
            remainingToDeduct -= take
            lastTouched = batch.id
        }
        return lastTouched
    }
}

@Dao
interface ConsumableDao {

    @Query("SELECT * FROM consumables ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ConsumableEntity>>

    @Query("SELECT * FROM consumables")
    suspend fun getAll(): List<ConsumableEntity>

    @Query("SELECT * FROM consumables WHERE deductPerMedicationId = :medicationId")
    suspend fun getLinkedTo(medicationId: Long): List<ConsumableEntity>

    @Insert
    suspend fun insert(entity: ConsumableEntity): Long

    @Update
    suspend fun update(entity: ConsumableEntity)

    @Query("UPDATE consumables SET remainingCount = MAX(0, remainingCount - :count) WHERE id = :id")
    suspend fun deduct(id: Long, count: Int)

    @Query("DELETE FROM consumables WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BloodTestDao {

    @Query("SELECT * FROM blood_test_sessions ORDER BY timestamp DESC")
    fun observeSessions(): Flow<List<BloodTestSessionEntity>>

    @Query("SELECT * FROM blood_test_sessions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int = 12): List<BloodTestSessionEntity>

    @Query("SELECT * FROM blood_test_sessions WHERE id = :id")
    suspend fun getSession(id: Long): BloodTestSessionEntity?

    @Query("SELECT * FROM blood_test_sessions WHERE timestamp = :timestamp LIMIT 1")
    suspend fun findSessionAt(timestamp: Long): BloodTestSessionEntity?

    @Insert
    suspend fun insertSession(entity: BloodTestSessionEntity): Long

    @Update
    suspend fun updateSession(entity: BloodTestSessionEntity)

    @Query("DELETE FROM blood_test_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM biomarker_values WHERE biomarkerCode = :code ORDER BY sessionId")
    fun observeValuesForBiomarker(code: String): Flow<List<BiomarkerValueEntity>>

    @Query("SELECT * FROM biomarker_values WHERE sessionId IN (:sessionIds)")
    suspend fun valuesForSessions(sessionIds: List<Long>): List<BiomarkerValueEntity>

    @Query("SELECT * FROM biomarker_values")
    suspend fun allValues(): List<BiomarkerValueEntity>

    @Query("SELECT * FROM biomarker_values WHERE sessionId = :sessionId")
    suspend fun valuesForSession(sessionId: Long): List<BiomarkerValueEntity>

    @Insert
    suspend fun insertValue(entity: BiomarkerValueEntity): Long

    @Insert
    suspend fun insertValues(entities: List<BiomarkerValueEntity>): List<Long>

    @Query("DELETE FROM biomarker_values WHERE sessionId = :sessionId AND biomarkerCode = :code")
    suspend fun deleteValue(sessionId: Long, code: String)

    @Transaction
    suspend fun saveSessionWithValues(
        session: BloodTestSessionEntity,
        values: List<BiomarkerValueEntity>,
    ): Long {
        val sessionId = if (session.id == 0L) insertSession(session) else {
            updateSession(session)
            session.id
        }
        insertValues(values.map { it.copy(sessionId = sessionId) })
        return sessionId
    }

    @Query("SELECT * FROM reference_ranges")
    fun observeRanges(): Flow<List<ReferenceRangeProfileEntity>>

    @Query("SELECT * FROM reference_ranges")
    suspend fun getRanges(): List<ReferenceRangeProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRange(entity: ReferenceRangeProfileEntity): Long

    @Query("DELETE FROM reference_ranges WHERE biomarkerCode = :code AND profileType = :type")
    suspend fun deleteRange(code: String, type: String)
}

@Dao
interface MilestoneDao {

    @Query("SELECT * FROM medication_milestones ORDER BY timestamp")
    fun observeAll(): Flow<List<MedicationMilestoneEntity>>

    @Query("SELECT * FROM medication_milestones ORDER BY timestamp")
    suspend fun getAll(): List<MedicationMilestoneEntity>

    @Insert
    suspend fun insert(entity: MedicationMilestoneEntity): Long

    @Query("DELETE FROM medication_milestones WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SymptomDao {

    @Query("SELECT * FROM symptom_logs WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC")
    fun observeBetween(from: Long, to: Long): Flow<List<SymptomLogEntity>>

    @Query("SELECT * FROM symptom_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<SymptomLogEntity>

    @Insert
    suspend fun insert(entity: SymptomLogEntity): Long

    @Query("DELETE FROM symptom_logs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TranslationDao {

    @Query("SELECT * FROM medication_translations WHERE medicationId = :medicationId ORDER BY language")
    fun observeFor(medicationId: Long): Flow<List<MedicationTranslationEntity>>

    @Query(
        "SELECT * FROM medication_translations " +
            "WHERE medicationId = :medicationId AND language = :language LIMIT 1",
    )
    suspend fun find(medicationId: Long, language: String): MedicationTranslationEntity?

    /** The unique index on (medicationId, language) turns a re-translation into a replace. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicationTranslationEntity): Long

    @Query("DELETE FROM medication_translations WHERE medicationId = :medicationId AND language = :language")
    suspend fun delete(medicationId: Long, language: String)
}

@Dao
interface SuklDao {

    @Query("SELECT * FROM sukl_medications WHERE suklCode = :code LIMIT 1")
    suspend fun findByCode(code: String): SuklMedicationEntity?

    @Query("SELECT * FROM sukl_medications WHERE gtin = :gtin LIMIT 1")
    suspend fun findByGtin(gtin: String): SuklMedicationEntity?

    @Query(
        "SELECT * FROM sukl_medications WHERE gtin IN (:gtins) ORDER BY gtin LIMIT 1",
    )
    suspend fun findByAnyGtin(gtins: List<String>): SuklMedicationEntity?

    @Query("SELECT * FROM sukl_medications WHERE name LIKE '%' || :query || '%' OR activeSubstance LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    suspend fun search(query: String, limit: Int = 40): List<SuklMedicationEntity>

    /**
     * Catalog search for the lookup waterfall: the SÚKL code, the name and the substance are
     * all searchable, and an exact code or a name prefix outranks a mid-word substance hit so
     * typing "0012345" or "Estro" does not bury the intended product.
     */
    @Query(
        """
        SELECT * FROM sukl_medications
        WHERE suklCode LIKE :like OR name LIKE :like OR activeSubstance LIKE :like
        ORDER BY
            CASE
                WHEN suklCode = :exact THEN 0
                WHEN name LIKE :prefix THEN 1
                WHEN name LIKE :like THEN 2
                ELSE 3
            END,
            name
        LIMIT :limit
        """,
    )
    suspend fun searchCatalog(
        like: String,
        prefix: String,
        exact: String,
        limit: Int,
    ): List<SuklMedicationEntity>

    @Query("SELECT COUNT(*) FROM sukl_medications")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SuklMedicationEntity>)

    @Query("DELETE FROM sukl_medications")
    suspend fun clear()
}

/** A prescription header with its items, fetched in one transaction. */
data class PrescriptionWithItems(
    @Embedded val prescription: PrescriptionEntity,
    @Relation(parentColumn = "id", entityColumn = "prescriptionId")
    val items: List<PrescriptionItemEntity>,
)

@Dao
interface PrescriptionDao {

    @Transaction
    @Query("SELECT * FROM prescriptions ORDER BY validUntil ASC, id ASC")
    fun observeWithItems(): Flow<List<PrescriptionWithItems>>

    @Transaction
    @Query("SELECT * FROM prescriptions ORDER BY validUntil ASC, id ASC")
    suspend fun allWithItems(): List<PrescriptionWithItems>

    @Query("SELECT * FROM prescriptions WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): PrescriptionEntity?

    @Query("SELECT COUNT(*) FROM prescriptions")
    suspend fun count(): Int

    @Insert
    suspend fun insertHeader(header: PrescriptionEntity): Long

    @Insert
    suspend fun insertItems(items: List<PrescriptionItemEntity>)

    /**
     * Uniqueness is enforced with a lookup instead of a unique index: the table already
     * exists in schema v2 and adding an index would force a migration for a check that the
     * transaction can do just as well.
     */
    @Transaction
    suspend fun insertPrescription(
        header: PrescriptionEntity,
        items: List<PrescriptionItemEntity>,
    ): Long? {
        if (getByCode(header.code) != null) return null
        val id = insertHeader(header)
        if (items.isNotEmpty()) {
            insertItems(items.map { it.copy(prescriptionId = id) })
        }
        return id
    }

    @Query("DELETE FROM prescriptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE prescriptions SET dispensed = 1 WHERE id = :id")
    suspend fun markDispensed(id: Long)
}
