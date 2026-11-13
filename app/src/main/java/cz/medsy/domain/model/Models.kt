package cz.medsy.domain.model

import cz.medsy.domain.message.Message

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class Medication(
    val id: Long = 0,
    val suklCode: String? = null,
    val gtin: String? = null,
    val name: String,
    val activeSubstance: String? = null,
    val atcCode: String? = null,
    val form: MedicationForm,
    val strengthValue: BigDecimal? = null,
    val strengthUnit: String? = null,
    val concentrationMgPerMl: BigDecimal? = null,
    val pilUrl: String? = null,
    val spcUrl: String? = null,
    val notes: String? = null,
    val colorHex: String? = null,
    val shapeType: String? = null,
    val isHrt: Boolean = false,
    val isArchived: Boolean = false,
) {
    val displayStrength: String?
        get() = strengthValue?.let { v -> "${v.stripTrailingZeros().toPlainString()} ${strengthUnit.orEmpty()}".trim() }
}

data class Schedule(
    val id: Long = 0,
    val medicationId: Long,
    val scheduleType: ScheduleType,
    val intervalValue: BigDecimal? = null,
    val daysOfWeekBitmask: Int? = null,
    val timesOfDay: List<LocalTime> = emptyList(),
    val doseAmount: BigDecimal,
    val doseUnit: String,
    val mealRelation: MealRelation = MealRelation.NONE,
    val offsetMinutes: Int = 0,
    val requireSiteRotation: Boolean = false,
    val isActive: Boolean = true,
    val startDate: LocalDate,
    val taperSteps: List<TaperStep> = emptyList(),
    val maxDailyDose: BigDecimal? = null,
    val note: String? = null,
    /** Gels/sprays: actuations (pumps, sprays) consumed by one dose. */
    val actuationsPerDose: Int? = null,
    /** Sublingual/buccal: minutes the dose must be held. */
    val holdMinutes: Int? = null,
)

data class TaperStep(
    val doseAmount: BigDecimal,
    val durationDays: Int,
)

data class DoseLog(
    val id: Long = 0,
    val medicationId: Long,
    val scheduleId: Long,
    val scheduledTimestamp: Instant,
    val actualTimestamp: Instant?,
    val status: DoseStatus,
    val actualDoseAmount: BigDecimal?,
    val actualVolumeMl: BigDecimal?,
    val applicationSite: ApplicationSite?,
    val notes: String? = null,
    val batchId: Long? = null,
    val isPrn: Boolean = false,
    /** Patches only. */
    val patchState: PatchState? = null,
    val wornUntil: Instant? = null,
)

data class InventoryBatch(
    val id: Long = 0,
    val medicationId: Long,
    val batchNumber: String? = null,
    val expirationDate: LocalDate? = null,
    val firstPuncturedDate: LocalDate? = null,
    val beyondUseDaysLimit: Int? = null,
    val initialQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val unit: String,
    val deadSpaceMlPerDose: BigDecimal? = null,
    val lowStockThresholdDays: Int = 7,
) {
    fun beyondUseDate(): LocalDate? {
        val punctured = firstPuncturedDate ?: return null
        val limit = beyondUseDaysLimit ?: return null
        return punctured.plusDays(limit.toLong())
    }
}

data class Consumable(
    val id: Long = 0,
    val name: String,
    val remainingCount: Int,
    val lowStockAlertCount: Int,
    val deductPerMedicationId: Long? = null,
    val deductPerDose: Int = 1,
)

data class BloodTestSession(
    val id: Long = 0,
    val timestamp: Instant,
    val labName: String? = null,
    val fastingStatus: Boolean = true,
    val calculatedHoursSinceLastHrtDose: Double? = null,
    val isTroughLevel: Boolean = false,
    val notes: String? = null,
    val attachmentUri: String? = null,
    val source: LabImportSource = LabImportSource.MANUAL,
)

data class BiomarkerValue(
    val id: Long = 0,
    val sessionId: Long,
    val biomarkerCode: String,
    val rawValue: BigDecimal,
    val rawUnit: String,
    val normalizedSiValue: BigDecimal,
    val normalizedSiUnit: String,
    val labMin: BigDecimal? = null,
    val labMax: BigDecimal? = null,
)

data class ReferenceRangeProfile(
    val id: Long = 0,
    val biomarkerCode: String,
    val profileType: ProfileType,
    val targetMinSi: BigDecimal?,
    val targetMaxSi: BigDecimal?,
    val optimalMinSi: BigDecimal? = null,
    val optimalMaxSi: BigDecimal? = null,
    val warningHighSi: BigDecimal? = null,
    val warningLowSi: BigDecimal? = null,
)

data class MedicationMilestone(
    val id: Long = 0,
    val medicationId: Long,
    val timestamp: Instant,
    val changeType: ChangeType,
    /** Sentence written before the message layer existed; shown when [message] is null. */
    val description: String,
    /** Localisable sentence: rendered with the user's resources. */
    val message: Message? = null,
)

data class SymptomLog(
    val id: Long = 0,
    val timestamp: Instant,
    val symptomCode: String,
    val severity: Int,
    val note: String? = null,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val heartRate: Int? = null,
)

data class SuklMedication(
    val suklCode: String,
    val name: String,
    val gtin: String? = null,
    val activeSubstance: String?,
    val atcCode: String?,
    val strength: String?,
    val formName: String?,
    val holder: String?,
) {
    val pilUrl: String
        get() = "https://sukl.gov.cz/download/pil/PIL${suklCode.padStart(7, '0')}.pdf"

    val spcUrl: String
        get() = "https://sukl.gov.cz/download/spc/SPC${suklCode.padStart(7, '0')}.pdf"
}

data class ScannedPackage(
    val gtin: String?,
    val expirationDate: LocalDate?,
    val batchNumber: String?,
    val serialNumber: String?,
    /** GS1 AI (30): variable count of units inside the package, when printed. */
    val packageQuantity: String? = null,
    val raw: String,
    val symbology: Symbology,
)

enum class Symbology { GS1_DATAMATRIX, EAN_13, E_RECEPT_QR }

data class Prescription(
    val code: String,
    val issuedAt: Instant?,
    val validUntil: Instant?,
    val medications: List<PrescriptionItem>,
)

data class PrescriptionItem(
    val suklCode: String?,
    val name: String,
    val quantity: Int?,
    val dosageInstruction: String?,
)

data class RecommendedSite(
    val site: ApplicationSite,
    val lastUsed: Instant?,
    val reason: Message,
)

data class VialDose(
    val volumeMl: BigDecimal,
    val deductVolumeMl: BigDecimal,
    val deadSpaceMl: BigDecimal,
    val concentrationMgPerMl: BigDecimal,
)

data class AdherenceSummary(
    val taken: Int,
    val skipped: Int,
    val missed: Int,
) {
    val total: Int get() = taken + skipped + missed
    val percent: Double get() = if (total == 0) 0.0 else taken * 100.0 / total
}

data class TroughAssessment(
    val hoursSinceLastDose: Double?,
    val isTrough: Boolean,
    val intervalHours: Double?,
    val label: cz.medsy.domain.message.Message,
)
