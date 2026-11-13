package cz.medsy.domain.calc

import cz.medsy.domain.model.DoseLog
import cz.medsy.domain.model.DoseStatus
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** One symptom entry, reduced to what the correlation needs. */
data class SymptomPoint(
    val at: Instant,
    val symptomCode: String,
    val severity: Int,
)

/** One dose that actually went in. */
data class DoseEvent(val at: Instant)

/**
 * Skipped and snoozed entries never entered the body, so they cannot explain a symptom.
 * A taken dose is timed by when it was actually injected, falling back to the schedule.
 */
fun takenDoseEvents(logs: List<DoseLog>): List<DoseEvent> = logs.mapNotNull { log ->
    if (log.status != DoseStatus.TAKEN && log.status != DoseStatus.LATE) return@mapNotNull null
    DoseEvent(log.actualTimestamp ?: log.scheduledTimestamp)
}

/**
 * Mean severity of one symptom inside the window after a dose versus outside it.
 *
 * Severity is the only signal the app collects per symptom, so the comparison is a mean
 * and a ratio of the two means — deliberately not a claim about causation.
 */
data class SymptomCorrelation(
    val symptomCode: String,
    val window: Duration,
    val inWindowCount: Int,
    val outOfWindowCount: Int,
    val inWindowMean: Double,
    val outOfWindowMean: Double,
) {
    /** How many times worse the symptom is near a dose. `null` when the baseline is 0. */
    val ratio: Double?
        get() = if (outOfWindowMean > 0.0) inWindowMean / outOfWindowMean else null

    /**
     * A single record proves nothing. Both groups need [SymptomCorrelationCalculator.MIN_SAMPLES]
     * before the app is willing to call this a pattern.
     */
    val isReportable: Boolean
        get() = inWindowCount >= SymptomCorrelationCalculator.MIN_SAMPLES &&
            outOfWindowCount >= SymptomCorrelationCalculator.MIN_SAMPLES

    val isWorseNearDose: Boolean
        get() = (ratio ?: 1.0) >= WORSE_THRESHOLD

    val isMilderNearDose: Boolean
        get() = (ratio ?: 1.0) <= MILD_THRESHOLD

    companion object {
        /** Below this the difference is noise at a 1..5 severity scale. */
        const val WORSE_THRESHOLD = 1.15
        const val MILD_THRESHOLD = 0.87
    }
}

object SymptomCorrelationCalculator {

    /** Two hours covers the peak of the injectable HRT esters the app tracks. */
    val DEFAULT_WINDOW: Duration = Duration.ofHours(2)

    const val MIN_SAMPLES = 3

    /** Window after a dose in which a symptom is counted as "near a dose". Inclusive. */
    fun nearestDose(
        at: Instant,
        doses: List<DoseEvent>,
        window: Duration = DEFAULT_WINDOW,
    ): DoseEvent? {
        val limit = window.toMillis()
        var best: DoseEvent? = null
        var bestMillis = Long.MAX_VALUE
        doses.forEach { dose ->
            val delta = abs(Duration.between(at, dose.at).toMillis())
            if (delta <= limit && delta < bestMillis) {
                best = dose
                bestMillis = delta
            }
        }
        return best
    }

    fun analyze(
        points: List<SymptomPoint>,
        doses: List<DoseEvent>,
        window: Duration = DEFAULT_WINDOW,
    ): List<SymptomCorrelation> =
        points.groupBy { it.symptomCode }
            .map { (code, group) -> correlate(code, group, doses, window) }
            .sortedWith(
                compareByDescending<SymptomCorrelation> { it.isReportable }
                    .thenByDescending { it.ratio ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.symptomCode },
            )

    private fun correlate(
        code: String,
        points: List<SymptomPoint>,
        doses: List<DoseEvent>,
        window: Duration,
    ): SymptomCorrelation {
        val near = ArrayList<Int>(points.size)
        val far = ArrayList<Int>(points.size)
        points.forEach { point ->
            if (nearestDose(point.at, doses, window) != null) near += point.severity else far += point.severity
        }
        return SymptomCorrelation(
            symptomCode = code,
            window = window,
            inWindowCount = near.size,
            outOfWindowCount = far.size,
            inWindowMean = mean(near),
            outOfWindowMean = mean(far),
        )
    }

    /** Empty groups average to 0, so an empty group can never divide by zero. */
    private fun mean(values: List<Int>): Double = if (values.isEmpty()) 0.0 else values.average()
}
