package cz.medsy.data.mapper

import cz.medsy.domain.message.MessageCodec

import cz.medsy.data.local.entity.BiomarkerValueEntity
import cz.medsy.data.local.entity.BloodTestSessionEntity
import cz.medsy.data.local.entity.ConsumableEntity
import cz.medsy.data.local.entity.DoseLogEntity
import cz.medsy.data.local.entity.InventoryBatchEntity
import cz.medsy.data.local.entity.MedicationEntity
import cz.medsy.data.local.entity.MedicationMilestoneEntity
import cz.medsy.data.local.entity.ReferenceRangeProfileEntity
import cz.medsy.data.local.entity.ScheduleEntity
import cz.medsy.data.local.entity.SymptomLogEntity
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.ChangeType
import cz.medsy.domain.model.Consumable
import cz.medsy.domain.model.DoseLog
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.LabImportSource
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.MedicationMilestone
import cz.medsy.domain.model.PatchState
import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.SymptomLog
import cz.medsy.domain.model.TaperStep
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val json = Json { ignoreUnknownKeys = true }

fun MedicationEntity.toDomain() = Medication(
    id = id,
    suklCode = suklCode,
    gtin = gtin,
    name = name,
    activeSubstance = activeSubstance,
    atcCode = atcCode,
    form = runCatching { MedicationForm.valueOf(form) }.getOrDefault(MedicationForm.TABLET),
    strengthValue = strengthValue?.let(::BigDecimal),
    strengthUnit = strengthUnit,
    concentrationMgPerMl = concentrationMgPerMl?.let(::BigDecimal),
    pilUrl = pilUrl,
    spcUrl = spcUrl,
    notes = notes,
    colorHex = colorHex,
    shapeType = shapeType,
    isHrt = isHrt,
    isArchived = isArchived,
)

fun Medication.toEntity() = MedicationEntity(
    id = id,
    suklCode = suklCode,
    gtin = gtin,
    name = name,
    activeSubstance = activeSubstance,
    atcCode = atcCode,
    form = form.name,
    strengthValue = strengthValue?.toDouble(),
    strengthUnit = strengthUnit,
    concentrationMgPerMl = concentrationMgPerMl?.toDouble(),
    pilUrl = pilUrl,
    spcUrl = spcUrl,
    notes = notes,
    colorHex = colorHex,
    shapeType = shapeType,
    isHrt = isHrt,
    isArchived = isArchived,
)

fun ScheduleEntity.toDomain() = Schedule(
    id = id,
    medicationId = medicationId,
    scheduleType = runCatching { ScheduleType.valueOf(scheduleType) }.getOrDefault(ScheduleType.FIXED_DAILY),
    intervalValue = intervalValue?.let(::BigDecimal),
    daysOfWeekBitmask = daysOfWeekBitmask,
    timesOfDay = runCatching { json.decodeFromString<List<String>>(timesOfDayJson) }
        .getOrDefault(emptyList())
        .mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() },
    doseAmount = BigDecimal.valueOf(doseAmount),
    doseUnit = doseUnit,
    mealRelation = runCatching { MealRelation.valueOf(mealRelation) }.getOrDefault(MealRelation.NONE),
    offsetMinutes = offsetMinutes,
    requireSiteRotation = requireSiteRotation,
    isActive = isActive,
    startDate = LocalDate.ofEpochDay(startDateEpochDay),
    taperSteps = taperStepsJson?.let {
        runCatching { json.decodeFromString<List<TaperStepDto>>(it) }
            .getOrDefault(emptyList())
            .map { dto -> TaperStep(BigDecimal.valueOf(dto.dose), dto.durationDays) }
    } ?: emptyList(),
    maxDailyDose = maxDailyDose?.let(::BigDecimal),
    note = note,
    actuationsPerDose = actuationsPerDose,
    holdMinutes = holdMinutes,
)

fun Schedule.toEntity() = ScheduleEntity(
    id = id,
    medicationId = medicationId,
    scheduleType = scheduleType.name,
    intervalValue = intervalValue?.toDouble(),
    daysOfWeekBitmask = daysOfWeekBitmask,
    timesOfDayJson = json.encodeToString(timesOfDay.map { it.toString() }),
    doseAmount = doseAmount.toDouble(),
    doseUnit = doseUnit,
    mealRelation = mealRelation.name,
    offsetMinutes = offsetMinutes,
    requireSiteRotation = requireSiteRotation,
    isActive = isActive,
    startDateEpochDay = startDate.toEpochDay(),
    taperStepsJson = if (taperSteps.isEmpty()) null else json.encodeToString(
        taperSteps.map { TaperStepDto(it.doseAmount.toDouble(), it.durationDays) },
    ),
    maxDailyDose = maxDailyDose?.toDouble(),
    note = note,
    actuationsPerDose = actuationsPerDose,
    holdMinutes = holdMinutes,
)

private @kotlinx.serialization.Serializable data class TaperStepDto(val dose: Double, val durationDays: Int)

fun DoseLogEntity.toDomain() = DoseLog(
    id = id,
    medicationId = medicationId,
    scheduleId = scheduleId,
    scheduledTimestamp = Instant.ofEpochMilli(scheduledTimestamp),
    actualTimestamp = actualTimestamp?.let(Instant::ofEpochMilli),
    status = runCatching { DoseStatus.valueOf(status) }.getOrDefault(DoseStatus.TAKEN),
    actualDoseAmount = actualDoseAmount?.let(::BigDecimal),
    actualVolumeMl = actualVolumeMl?.let(::BigDecimal),
    applicationSite = applicationSite?.let { runCatching { ApplicationSite.valueOf(it) }.getOrNull() },
    notes = notes,
    batchId = batchId,
    isPrn = isPrn,
    patchState = patchState?.let { runCatching { PatchState.valueOf(it) }.getOrNull() },
    wornUntil = wornUntil?.let(Instant::ofEpochMilli),
)

fun DoseLog.toEntity() = DoseLogEntity(
    id = id,
    medicationId = medicationId,
    scheduleId = scheduleId,
    scheduledTimestamp = scheduledTimestamp.toEpochMilli(),
    actualTimestamp = actualTimestamp?.toEpochMilli(),
    status = status.name,
    actualDoseAmount = actualDoseAmount?.toDouble(),
    actualVolumeMl = actualVolumeMl?.toDouble(),
    applicationSite = applicationSite?.name,
    notes = notes,
    batchId = batchId,
    isPrn = isPrn,
    patchState = patchState?.name,
    wornUntil = wornUntil?.toEpochMilli(),
)

fun InventoryBatchEntity.toDomain() = InventoryBatch(
    id = id,
    medicationId = medicationId,
    batchNumber = batchNumber,
    expirationDate = expirationDateEpochDay?.let(LocalDate::ofEpochDay),
    firstPuncturedDate = firstPuncturedDateEpochDay?.let(LocalDate::ofEpochDay),
    beyondUseDaysLimit = beyondUseDaysLimit,
    initialQuantity = BigDecimal.valueOf(initialQuantity),
    remainingQuantity = BigDecimal.valueOf(remainingQuantity),
    unit = unit,
    deadSpaceMlPerDose = deadSpaceMlPerDose?.let(::BigDecimal),
    lowStockThresholdDays = lowStockThresholdDays,
)

fun InventoryBatch.toEntity() = InventoryBatchEntity(
    id = id,
    medicationId = medicationId,
    batchNumber = batchNumber,
    expirationDateEpochDay = expirationDate?.toEpochDay(),
    firstPuncturedDateEpochDay = firstPuncturedDate?.toEpochDay(),
    beyondUseDaysLimit = beyondUseDaysLimit,
    initialQuantity = initialQuantity.toDouble(),
    remainingQuantity = remainingQuantity.toDouble(),
    unit = unit,
    deadSpaceMlPerDose = deadSpaceMlPerDose?.toDouble(),
    lowStockThresholdDays = lowStockThresholdDays,
)

fun ConsumableEntity.toDomain() = Consumable(
    id = id,
    name = name,
    remainingCount = remainingCount,
    lowStockAlertCount = lowStockAlertCount,
    deductPerMedicationId = deductPerMedicationId,
    deductPerDose = deductPerDose,
)

fun BloodTestSessionEntity.toDomain() = BloodTestSession(
    id = id,
    timestamp = Instant.ofEpochMilli(timestamp),
    labName = labName,
    fastingStatus = fastingStatus,
    calculatedHoursSinceLastHrtDose = calculatedHoursSinceLastHrtDose,
    isTroughLevel = isTroughLevel,
    notes = notes,
    attachmentUri = attachmentUri,
    source = runCatching { LabImportSource.valueOf(source) }.getOrDefault(LabImportSource.MANUAL),
)

fun BloodTestSession.toEntity() = BloodTestSessionEntity(
    id = id,
    timestamp = timestamp.toEpochMilli(),
    labName = labName,
    fastingStatus = fastingStatus,
    calculatedHoursSinceLastHrtDose = calculatedHoursSinceLastHrtDose,
    isTroughLevel = isTroughLevel,
    notes = notes,
    attachmentUri = attachmentUri,
    source = source.name,
)

fun BiomarkerValueEntity.toDomain() = BiomarkerValue(
    id = id,
    sessionId = sessionId,
    biomarkerCode = biomarkerCode,
    rawValue = BigDecimal.valueOf(rawValue),
    rawUnit = rawUnit,
    normalizedSiValue = BigDecimal.valueOf(normalizedSiValue),
    normalizedSiUnit = normalizedSiUnit,
    labMin = labMin?.let(::BigDecimal),
    labMax = labMax?.let(::BigDecimal),
)

fun BiomarkerValue.toEntity() = BiomarkerValueEntity(
    id = id,
    sessionId = sessionId,
    biomarkerCode = biomarkerCode,
    rawValue = rawValue.toDouble(),
    rawUnit = rawUnit,
    normalizedSiValue = normalizedSiValue.toDouble(),
    normalizedSiUnit = normalizedSiUnit,
    labMin = labMin?.toDouble(),
    labMax = labMax?.toDouble(),
)

fun ReferenceRangeProfileEntity.toDomain() = ReferenceRangeProfile(
    id = id,
    biomarkerCode = biomarkerCode,
    profileType = runCatching { ProfileType.valueOf(profileType) }.getOrDefault(ProfileType.CUSTOM),
    targetMinSi = targetMinSi?.let(::BigDecimal),
    targetMaxSi = targetMaxSi?.let(::BigDecimal),
    optimalMinSi = optimalMinSi?.let(::BigDecimal),
    optimalMaxSi = optimalMaxSi?.let(::BigDecimal),
    warningHighSi = warningHighSi?.let(::BigDecimal),
    warningLowSi = warningLowSi?.let(::BigDecimal),
)

fun ReferenceRangeProfile.toEntity() = ReferenceRangeProfileEntity(
    id = id,
    biomarkerCode = biomarkerCode,
    profileType = profileType.name,
    targetMinSi = targetMinSi?.toDouble(),
    targetMaxSi = targetMaxSi?.toDouble(),
    optimalMinSi = optimalMinSi?.toDouble(),
    optimalMaxSi = optimalMaxSi?.toDouble(),
    warningHighSi = warningHighSi?.toDouble(),
    warningLowSi = warningLowSi?.toDouble(),
)

fun MedicationMilestoneEntity.toDomain() = MedicationMilestone(
    id = id,
    medicationId = medicationId,
    timestamp = Instant.ofEpochMilli(timestamp),
    changeType = runCatching { ChangeType.valueOf(changeType) }.getOrDefault(ChangeType.STARTED),
    description = description,
    message = MessageCodec.decode(message),
)

fun MedicationMilestone.toEntity() = MedicationMilestoneEntity(
    id = id,
    medicationId = medicationId,
    timestamp = timestamp.toEpochMilli(),
    changeType = changeType.name,
    description = description,
    message = message?.let(MessageCodec::encode),
)

fun SymptomLogEntity.toDomain() = SymptomLog(
    id = id,
    timestamp = Instant.ofEpochMilli(timestamp),
    symptomCode = symptomCode,
    severity = severity,
    note = note,
    systolic = systolic,
    diastolic = diastolic,
    heartRate = heartRate,
)

fun SymptomLog.toEntity() = SymptomLogEntity(
    id = id,
    timestamp = timestamp.toEpochMilli(),
    symptomCode = symptomCode,
    severity = severity,
    note = note,
    systolic = systolic,
    diastolic = diastolic,
    heartRate = heartRate,
)
