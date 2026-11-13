package cz.medsy.ui.schedule

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import cz.medsy.domain.model.TaperStep
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Editor state as plain values, so it can be driven by text fields and validated
 * without Compose. The editor must never persist something the scheduler cannot read:
 * an interval schedule without an interval, a daily schedule without a time, or a taper
 * without steps would silently produce no doses.
 */
data class ScheduleDraft(
    val id: Long = 0,
    val medicationId: Long,
    val type: ScheduleType = ScheduleType.FIXED_DAILY,
    val intervalValue: String = "",
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val doseAmount: String = "1",
    val doseUnit: String = "tbl",
    val mealRelation: MealRelation = MealRelation.NONE,
    val offsetMinutes: Int = 0,
    val requireSiteRotation: Boolean = false,
    val isActive: Boolean = true,
    val startDate: LocalDate = LocalDate.now(),
    val taperSteps: List<TaperDraft> = emptyList(),
    val maxDailyDose: String = "",
    val note: String = "",
    /** Gels/sprays: pumps consumed by one dose. */
    val actuationsPerDose: String = "",
    /** Sublingual/buccal: minutes the dose must be held. */
    val holdMinutes: String = "",
) {
    data class TaperDraft(val dose: String, val durationDays: String)
}

sealed interface ValidationResult {
    data class Valid(val schedule: Schedule) : ValidationResult
    data class Invalid(val errors: List<Message>) : ValidationResult
}

object ScheduleValidator {

    /** Upper bound so a typo like "3500" days cannot produce an absurd interval. */
    private val MAX_INTERVAL_DAYS = BigDecimal("365")
    private val MAX_INTERVAL_HOURS = BigDecimal("8760")

    fun validate(draft: ScheduleDraft): ValidationResult {
        val errors = ArrayList<Message>()

        val dose = draft.doseAmount.trim().toBigDecimalOrNull()
        if (dose == null || dose.signum() <= 0) {
            errors += message(MessageKey.SCHEDULE_DOSE_POSITIVE)
        }
        if (draft.doseUnit.isBlank()) {
            errors += message(MessageKey.SCHEDULE_DOSE_UNIT_REQUIRED)
        }

        val interval = draft.intervalValue.trim().toBigDecimalOrNull()
        var intervalValid = true
        when (draft.type) {
            ScheduleType.INTERVAL_DAYS, ScheduleType.CYCLICAL -> {
                intervalValid = interval != null && interval.signum() > 0
                if (interval == null || interval.signum() <= 0) {
                    errors += message(MessageKey.SCHEDULE_INTERVAL_POSITIVE)
                } else if (draft.type == ScheduleType.INTERVAL_DAYS && interval > MAX_INTERVAL_DAYS) {
                    errors += message(
                        MessageKey.SCHEDULE_INTERVAL_MAX_DAYS,
                        MAX_INTERVAL_DAYS.toPlainString(),
                    )
                } else if (draft.type == ScheduleType.CYCLICAL &&
                    (interval < BigDecimal("1") || interval > BigDecimal("365"))
                ) {
                    errors += message(MessageKey.SCHEDULE_CYCLICAL_RANGE)
                }
            }
            ScheduleType.INTERVAL_HOURS -> {
                intervalValid = interval != null && interval.signum() > 0
                if (interval == null || interval.signum() <= 0) {
                    errors += message(MessageKey.SCHEDULE_INTERVAL_POSITIVE)
                } else if (interval > MAX_INTERVAL_HOURS) {
                    errors += message(
                        MessageKey.SCHEDULE_INTERVAL_MAX_HOURS,
                        MAX_INTERVAL_HOURS.toPlainString(),
                    )
                }
            }
            else -> Unit
        }

        // Wall-clock times only matter for schedules that are not clock-interval driven.
        if (draft.type in setOf(
                ScheduleType.FIXED_DAILY,
                ScheduleType.CYCLICAL,
                ScheduleType.TAPERING,
            ) && draft.times.isEmpty()
        ) {
            errors += message(MessageKey.SCHEDULE_NO_TIME)
        }

        if (draft.times.size > 12) {
            errors += message(MessageKey.SCHEDULE_TOO_MANY_TIMES)
        }

        val taper = ArrayList<TaperStep>()
        if (draft.type == ScheduleType.TAPERING) {
            if (draft.taperSteps.isEmpty()) {
                errors += message(MessageKey.SCHEDULE_TAPER_EMPTY)
            }
            draft.taperSteps.forEachIndexed { index, step ->
                val stepDose = step.dose.trim().toBigDecimalOrNull()
                val duration = step.durationDays.trim().toIntOrNull()
                if (stepDose == null || stepDose.signum() <= 0) {
                    errors += message(
                        MessageKey.SCHEDULE_TAPER_DOSE,
                        index + 1,
                    )
                }
                if (duration == null || duration <= 0) {
                    errors += message(
                        MessageKey.SCHEDULE_TAPER_DAYS,
                        index + 1,
                    )
                }
                if (stepDose != null && duration != null && stepDose.signum() > 0 && duration > 0) {
                    taper += TaperStep(stepDose, duration)
                }
            }
        }

        val actuations = draft.actuationsPerDose.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (draft.actuationsPerDose.isNotBlank() && (actuations == null || actuations <= 0)) {
            errors += message(MessageKey.SCHEDULE_ACTUATIONS)
        }

        val hold = draft.holdMinutes.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (draft.holdMinutes.isNotBlank() && (hold == null || hold <= 0 || hold > 60)) {
            errors += message(MessageKey.SCHEDULE_HOLD_RANGE)
        }

        val maxDaily = draft.maxDailyDose.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        if (draft.maxDailyDose.isNotBlank() && maxDaily == null) {
            errors += message(MessageKey.SCHEDULE_MAX_DAILY_NUMBER)
        }

        if (dose != null && maxDaily != null && draft.type == ScheduleType.PRN && maxDaily <= dose) {
            errors += message(MessageKey.SCHEDULE_MAX_DAILY_ABOVE_DOSE)
        }

        // A missing interval must not silently turn into a different schedule type.
        if (!intervalValid && draft.type in setOf(
                ScheduleType.INTERVAL_DAYS,
                ScheduleType.INTERVAL_HOURS,
                ScheduleType.CYCLICAL,
            )
        ) {
            return ValidationResult.Invalid(errors)
        }

        if (errors.isNotEmpty()) return ValidationResult.Invalid(errors)

        return ValidationResult.Valid(
            Schedule(
                id = draft.id,
                medicationId = draft.medicationId,
                scheduleType = draft.type,
                intervalValue = when (draft.type) {
                    ScheduleType.INTERVAL_DAYS,
                    ScheduleType.INTERVAL_HOURS,
                    ScheduleType.CYCLICAL,
                    -> interval
                    else -> null
                },
                timesOfDay = if (draft.type == ScheduleType.INTERVAL_HOURS ||
                    draft.type == ScheduleType.INTERVAL_DAYS
                ) {
                    // The engine anchors on startDate + first time; keep it for a stable anchor.
                    draft.times.take(1)
                } else {
                    draft.times.sorted()
                },
                doseAmount = dose!!,
                doseUnit = draft.doseUnit.trim(),
                mealRelation = draft.mealRelation,
                offsetMinutes = draft.offsetMinutes,
                requireSiteRotation = draft.requireSiteRotation,
                isActive = draft.isActive,
                startDate = draft.startDate,
                taperSteps = taper,
                maxDailyDose = maxDaily,
                actuationsPerDose = actuations,
                holdMinutes = hold,
                note = draft.note.ifBlank { null },
            ),
        )
    }

    fun toDraft(schedule: Schedule): ScheduleDraft = ScheduleDraft(
        id = schedule.id,
        medicationId = schedule.medicationId,
        type = schedule.scheduleType,
        intervalValue = schedule.intervalValue?.stripTrailingZeros()?.toPlainString().orEmpty(),
        times = schedule.timesOfDay.ifEmpty { listOf(LocalTime.of(8, 0)) },
        doseAmount = schedule.doseAmount.stripTrailingZeros().toPlainString(),
        doseUnit = schedule.doseUnit,
        mealRelation = schedule.mealRelation,
        offsetMinutes = schedule.offsetMinutes,
        requireSiteRotation = schedule.requireSiteRotation,
        isActive = schedule.isActive,
        startDate = schedule.startDate,
        taperSteps = schedule.taperSteps.map {
            ScheduleDraft.TaperDraft(
                dose = it.doseAmount.stripTrailingZeros().toPlainString(),
                durationDays = it.durationDays.toString(),
            )
        },
        maxDailyDose = schedule.maxDailyDose?.stripTrailingZeros()?.toPlainString().orEmpty(),
        actuationsPerDose = schedule.actuationsPerDose?.toString().orEmpty(),
        holdMinutes = schedule.holdMinutes?.toString().orEmpty(),
        note = schedule.note.orEmpty(),
    )
}
