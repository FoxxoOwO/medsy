package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.RecommendedSite
import cz.medsy.domain.model.VialDose
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

object VialCalculator {

    private val MC = MathContext(20, RoundingMode.HALF_UP)
    private val THREE = MathContext(3, RoundingMode.HALF_UP)

    val STANDARD_LUER_DEAD_SPACE: BigDecimal = BigDecimal("0.05")
    val LOW_DEAD_SPACE_INSULIN: BigDecimal = BigDecimal("0.005")
    val NO_DEAD_SPACE: BigDecimal = BigDecimal.ZERO

    /**
     * Converts a dose in mg to the volume that must be drawn for a vial of
     * [concentrationMgPerMl]. Returns a value rounded to 3 decimals (0.001 mL,
     * i.e. the smallest graduation of a 1 mL insulin syringe) — never a Double.
     */
    fun doseToVolumeMl(doseMg: BigDecimal, concentrationMgPerMl: BigDecimal): BigDecimal {
        require(doseMg.signum() > 0) { "dose must be > 0, was $doseMg" }
        require(concentrationMgPerMl.signum() > 0) {
            "concentration must be > 0, was $concentrationMgPerMl"
        }
        return doseMg.divide(concentrationMgPerMl, MC).setScale(3, RoundingMode.HALF_UP)
    }

    /** Volume in mL -> delivered dose in mg at the given concentration. */
    fun volumeToDoseMg(volumeMl: BigDecimal, concentrationMgPerMl: BigDecimal): BigDecimal {
        require(volumeMl.signum() >= 0) { "volume must be >= 0" }
        require(concentrationMgPerMl.signum() > 0) { "concentration must be > 0" }
        return volumeMl.multiply(concentrationMgPerMl, MC).setScale(3, RoundingMode.HALF_UP)
    }

    fun unitsToVolumeMl(units: BigDecimal, unitsPerMl: BigDecimal = BigDecimal("100")): BigDecimal {
        require(unitsPerMl.signum() > 0) { "unitsPerMl must be > 0" }
        return units.divide(unitsPerMl, MC).setScale(3, RoundingMode.HALF_UP)
    }

    /**
     * Full calculation for one injection: the syringe is filled with
     * `dose volume + dead space`, so the physical amount leaving the vial is the
     * fill volume. The dead space stays in the hub/needle and is *not* delivered.
     */
    fun calculate(
        doseMg: BigDecimal,
        concentrationMgPerMl: BigDecimal,
        deadSpaceMl: BigDecimal,
    ): VialDose {
        require(deadSpaceMl.signum() >= 0) { "dead space must be >= 0" }
        val volume = doseToVolumeMl(doseMg, concentrationMgPerMl)
        val deduct = volume.add(deadSpaceMl, MC).setScale(3, RoundingMode.HALF_UP)
        return VialDose(
            volumeMl = volume,
            deductVolumeMl = deduct,
            deadSpaceMl = deadSpaceMl,
            concentrationMgPerMl = concentrationMgPerMl,
        )
    }

    /**
     * Number of whole doses still obtainable from a vial. Accounts for dead space
     * left in the syringe on each draw; floor() so we never claim a partial dose.
     */
    fun remainingDoses(
        remainingMl: BigDecimal,
        doseMg: BigDecimal,
        concentrationMgPerMl: BigDecimal,
        deadSpaceMl: BigDecimal,
    ): Int {
        if (remainingMl.signum() <= 0) return 0
        val perDose = calculate(doseMg, concentrationMgPerMl, deadSpaceMl).deductVolumeMl
        if (perDose.signum() <= 0) return 0
        return remainingMl.divide(perDose, THREE).toInt()
    }

    fun monthsUntilExpiry(expiration: java.time.LocalDate, today: java.time.LocalDate): Long =
        java.time.temporal.ChronoUnit.MONTHS.between(today, expiration)
}

object SiteRotation {

    /**
     * Picks the least-recently-used site for the given form. Never-used sites win
     * over used ones. Adjacent usage within 24h is penalised but still allowed if
     * that is genuinely the least-recently-used option, because skipping a dose is
     * worse than a suboptimal site.
     */
    fun recommend(
        form: MedicationForm,
        history: List<Pair<ApplicationSite, Instant>>,
        now: Instant = Instant.now(),
    ): RecommendedSite? {
        val candidates = ApplicationSite.forForm(form)
        if (candidates.isEmpty()) return null

        val lastUsed = mutableMapOf<ApplicationSite, Instant>()
        for ((site, at) in history) {
            if (site !in candidates) continue
            val previous = lastUsed[site]
            if (previous == null || at.isAfter(previous)) lastUsed[site] = at
        }

        val neverUsed = candidates.filter { it !in lastUsed }
        if (neverUsed.isNotEmpty()) {
            return RecommendedSite(
                site = neverUsed.first(),
                lastUsed = null,
                reason = message(MessageKey.VIAL_NEVER_USED),
            )
        }

        val sorted = candidates.sortedBy { lastUsed.getValue(it) }
        val oldest = sorted.first()
        val mostRecent = candidates.maxOf { lastUsed.getValue(it) }
        val hoursSinceAny = Duration.between(mostRecent, now).toMinutes() / 60.0
        val hoursForOldest = Duration.between(lastUsed.getValue(oldest), now).toMinutes() / 60.0

        val reason = if (hoursSinceAny < 24.0) {
            message(MessageKey.VIAL_ROTATE_FURTHER)
        } else {
            message(MessageKey.VIAL_LEAST_RECENT, formatHours(hoursForOldest))
        }
        return RecommendedSite(site = oldest, lastUsed = lastUsed[oldest], reason = reason)
    }

    private fun formatHours(hours: Double): String {
        val totalMinutes = (hours * 60).toLong()
        val days = totalMinutes / (60 * 24)
        val h = (totalMinutes % (60 * 24)) / 60
        return if (days > 0) "${days}d ${h}h" else "${h}h"
    }
}
