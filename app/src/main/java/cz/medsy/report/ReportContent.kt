package cz.medsy.report

import cz.medsy.domain.model.AdherenceSummary
import cz.medsy.domain.model.BloodTestSession
import cz.medsy.domain.model.BiomarkerValue
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationMilestone
import cz.medsy.domain.model.Schedule
import cz.medsy.domain.model.ScheduleType
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the PDF prints, computed without touching a Canvas.
 *
 * `PdfDocument` cannot be exercised under Robolectric ("document is closed!"), so the
 * *content* is built here as plain data and unit-tested; [DoctorReportGenerator] only
 * renders it. This keeps deltas, adherence and truncation verifiable.
 */
object ReportContent {

    data class LabRow(
        val name: String,
        val value: String,
        val unit: String,
        val flag: String,
        /** Signed difference to the previous draw, null when there is no prior value. */
        val delta: String?,
    )

    data class LabBlock(
        val dateLabel: String,
        val isTrough: Boolean,
        val rows: List<LabRow>,
    )

    data class MedicationLine(
        val name: String,
        val schedule: String,
    )

    data class AdherenceLine(
        val days: Int,
        val taken: Int,
        val total: Int,
        val percent: Int,
    )

    data class MilestoneLine(
        val dateLabel: String,
        val text: String,
    )

    data class Content(
        val medications: List<MedicationLine>,
        val adherence: List<AdherenceLine>,
        val milestones: List<MilestoneLine>,
        val labs: List<LabBlock>,
        val truncated: Boolean,
    )

    /** A4-safe caps so the single page never overflows silently. */
    const val MAX_MEDICATIONS = 10
    const val MAX_SESSIONS = 3
    const val MAX_ROWS_PER_SESSION = 10
    const val MAX_MILESTONES = 6

    fun build(
        medications: List<Medication>,
        schedules: List<Schedule>,
        adherence30: AdherenceSummary,
        adherence90: AdherenceSummary,
        milestones: List<MedicationMilestone>,
        sessions: List<BloodTestSession>,
        values: List<BiomarkerValue>,
        flagFor: (BiomarkerValue) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Content {
        val bySession = values.groupBy { it.sessionId }
        val orderedSessions = sessions.sortedByDescending { it.timestamp }

        val blocks = orderedSessions.take(MAX_SESSIONS).map { session ->
            val previous = orderedSessions
                .filter { it.timestamp.isBefore(session.timestamp) }
                .minByOrNull { it.timestamp }
            val previousValues = previous
                ?.let { bySession[it.id].orEmpty().associateBy { value -> value.biomarkerCode } }
                .orEmpty()

            val rows = bySession[session.id].orEmpty()
                .sortedBy { it.biomarkerCode }
                .take(MAX_ROWS_PER_SESSION)
                .map { value ->
                    LabRow(
                        name = value.biomarkerCode,
                        value = value.normalizedSiValue.stripTrailingZeros().toPlainString(),
                        unit = value.normalizedSiUnit,
                        flag = flagFor(value),
                        delta = deltaTo(previousValues[value.biomarkerCode]?.normalizedSiValue, value.normalizedSiValue),
                    )
                }

            LabBlock(
                dateLabel = session.timestamp.atZone(zone)
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH)),
                isTrough = session.isTroughLevel,
                rows = rows,
            )
        }

        val medicationLines = medications.take(MAX_MEDICATIONS).map { medication ->
            val own = schedules.filter { it.medicationId == medication.id }
            MedicationLine(
                name = listOfNotNull(medication.name, medication.displayStrength)
                    .joinToString(" ")
                    .trim(),
                schedule = if (own.isEmpty()) {
                    "no active schedule"
                } else {
                    own.joinToString("; ") { describe(it) }
                },
            )
        }

        return Content(
            medications = medicationLines,
            adherence = listOf(
                AdherenceLine(30, adherence30.taken, adherence30.total, adherence30.percent.toInt()),
                AdherenceLine(90, adherence90.taken, adherence90.total, adherence90.percent.toInt()),
            ),
            milestones = milestones.sortedByDescending { it.timestamp }
                .take(MAX_MILESTONES)
                .map { milestone ->
                    MilestoneLine(
                        dateLabel = milestone.timestamp.atZone(zone)
                            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)),
                        text = "${milestone.changeType.name.lowercase(Locale.ENGLISH)}: ${milestone.description}",
                    )
                },
            labs = blocks,
            truncated = medications.size > MAX_MEDICATIONS ||
                orderedSessions.size > MAX_SESSIONS,
        )
    }

    fun deltaTo(previous: BigDecimal?, current: BigDecimal): String? {
        if (previous == null) return null
        val diff = current.subtract(previous)
        if (diff.signum() == 0) return "0"
        val sign = if (diff.signum() > 0) "+" else ""
        return "$sign${diff.stripTrailingZeros().toPlainString()}"
    }

    fun describe(schedule: Schedule): String {
        val interval = when (schedule.scheduleType) {
            ScheduleType.FIXED_DAILY -> "daily"
            ScheduleType.INTERVAL_DAYS ->
                "every ${schedule.intervalValue?.stripTrailingZeros()?.toPlainString()} d"
            ScheduleType.INTERVAL_HOURS ->
                "every ${schedule.intervalValue?.stripTrailingZeros()?.toPlainString()} h"
            ScheduleType.CYCLICAL -> "cyclical"
            ScheduleType.TAPERING -> "tapering"
            ScheduleType.PRN -> "PRN"
        }
        val times = schedule.timesOfDay.joinToString("/") { it.toString() }
        return "$interval, ${schedule.doseAmount.stripTrailingZeros().toPlainString()} " +
            "${schedule.doseUnit} @ $times"
    }

    @Suppress("unused")
    private fun unusedInstant(): Instant = Instant.EPOCH
}
