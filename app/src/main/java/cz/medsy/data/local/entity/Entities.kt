package cz.medsy.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "medications",
    indices = [
        Index(value = ["suklCode"], unique = false),
        Index(value = ["gtin"], unique = false),
        Index(value = ["atcCode"]),
    ],
)
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val suklCode: String? = null,
    val gtin: String? = null,
    val name: String,
    val activeSubstance: String? = null,
    val atcCode: String? = null,
    val form: String,
    val strengthValue: Double? = null,
    val strengthUnit: String? = null,
    val concentrationMgPerMl: Double? = null,
    val pilUrl: String? = null,
    val spcUrl: String? = null,
    val notes: String? = null,
    val colorHex: String? = null,
    val shapeType: String? = null,
    val isHrt: Boolean = false,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val scheduleType: String,
    val intervalValue: Double? = null,
    val daysOfWeekBitmask: Int? = null,
    val timesOfDayJson: String,
    val doseAmount: Double,
    val doseUnit: String,
    val mealRelation: String = "NONE",
    val offsetMinutes: Int = 0,
    val requireSiteRotation: Boolean = false,
    val isActive: Boolean = true,
    val startDateEpochDay: Long,
    val taperStepsJson: String? = null,
    val maxDailyDose: Double? = null,
    val note: String? = null,
    /** Gels/sprays: actuations (pumps, sprays) consumed by one dose. */
    val actuationsPerDose: Int? = null,
    /** Sublingual/buccal: minutes the dose must be held under the tongue. */
    val holdMinutes: Int? = null,
)

@Entity(
    tableName = "dose_logs",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("medicationId"),
        Index("scheduleId"),
        Index(value = ["scheduledTimestamp"]),
        Index(value = ["medicationId", "scheduledTimestamp"], unique = false),
    ],
)
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val scheduleId: Long,
    val scheduledTimestamp: Long,
    val actualTimestamp: Long? = null,
    val status: String,
    val actualDoseAmount: Double? = null,
    val actualVolumeMl: Double? = null,
    val applicationSite: String? = null,
    val notes: String? = null,
    val batchId: Long? = null,
    val isPrn: Boolean = false,
    /** Patches only: APPLIED / WORN / REMOVAL_DUE / REMOVED. */
    val patchState: String? = null,
    /** Patches only: when the patch must come off. */
    val wornUntil: Long? = null,
)

@Entity(
    tableName = "inventory_batches",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class InventoryBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val batchNumber: String? = null,
    val expirationDateEpochDay: Long? = null,
    val firstPuncturedDateEpochDay: Long? = null,
    val beyondUseDaysLimit: Int? = null,
    val initialQuantity: Double,
    val remainingQuantity: Double,
    val unit: String,
    val deadSpaceMlPerDose: Double? = null,
    val lowStockThresholdDays: Int = 7,
)

@Entity(tableName = "consumables")
data class ConsumableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val remainingCount: Int,
    val lowStockAlertCount: Int = 5,
    val deductPerMedicationId: Long? = null,
    val deductPerDose: Int = 1,
)

@Entity(
    tableName = "prescriptions",
    indices = [Index(value = ["code"], unique = true)],
)
data class PrescriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val issuedAt: Long? = null,
    val validUntil: Long? = null,
    val dispensed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "prescription_items",
    foreignKeys = [
        ForeignKey(
            entity = PrescriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["prescriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("prescriptionId")],
)
data class PrescriptionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prescriptionId: Long,
    val suklCode: String? = null,
    val name: String,
    val quantity: Int? = null,
    val dosageInstruction: String? = null,
)

@Entity(tableName = "blood_test_sessions", indices = [Index("timestamp")])
data class BloodTestSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val labName: String? = null,
    val fastingStatus: Boolean = true,
    val calculatedHoursSinceLastHrtDose: Double? = null,
    val isTroughLevel: Boolean = false,
    val notes: String? = null,
    val attachmentUri: String? = null,
    val source: String = "MANUAL",
)

@Entity(
    tableName = "biomarker_values",
    foreignKeys = [
        ForeignKey(
            entity = BloodTestSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("biomarkerCode")],
)
data class BiomarkerValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val biomarkerCode: String,
    val rawValue: Double,
    val rawUnit: String,
    val normalizedSiValue: Double,
    val normalizedSiUnit: String,
    val labMin: Double? = null,
    val labMax: Double? = null,
)

@Entity(
    tableName = "reference_ranges",
    indices = [Index(value = ["biomarkerCode", "profileType"], unique = true)],
)
data class ReferenceRangeProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val biomarkerCode: String,
    val profileType: String,
    val targetMinSi: Double? = null,
    val targetMaxSi: Double? = null,
    val optimalMinSi: Double? = null,
    val optimalMaxSi: Double? = null,
    val warningHighSi: Double? = null,
    val warningLowSi: Double? = null,
)

@Entity(
    tableName = "medication_milestones",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId"), Index("timestamp")],
)
data class MedicationMilestoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val timestamp: Long,
    val changeType: String,
    /** Legacy human sentence; empty for rows written from v3 on. */
    val description: String,
    /** The localisable sentence, encoded JSON. Null on pre-v3 rows. */
    val message: String? = null,
)

@Entity(tableName = "symptom_logs", indices = [Index("timestamp"), Index("symptomCode")])
data class SymptomLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val symptomCode: String,
    val severity: Int,
    val note: String? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val heartRate: Int? = null,
)

@Entity(
    tableName = "sukl_medications",
    indices = [Index(value = ["suklCode"], unique = true), Index(value = ["gtin"])],
)
data class SuklMedicationEntity(
    @PrimaryKey val suklCode: String,
    /** GS1 GTIN-14 from the SÚKL dataset. Not derivable from [suklCode]. */
    val gtin: String? = null,
    val name: String,
    val activeSubstance: String? = null,
    val atcCode: String? = null,
    val strength: String? = null,
    val formName: String? = null,
    val holder: String? = null,
    val syncedAt: Long = System.currentTimeMillis(),
)
