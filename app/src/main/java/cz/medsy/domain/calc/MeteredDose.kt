package cz.medsy.domain.calc

import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Metered-dose arithmetic for gels and sprays.
 *
 * A gel bottle is counted in *actuations* (pumps), not in doses. One prescribed dose may
 * be two pumps, so deducting "1" per dose silently doubles the time the bottle lasts.
 * The label usually states the total pumps ("60 dávok"), which is the number the
 * patient can actually read off the box.
 */
object MeteredDose {

    /** Forms whose package is measured in actuations. */
    fun isMeteredDose(form: MedicationForm): Boolean =
        form == MedicationForm.GEL || form == MedicationForm.SPRAY

    /** Pumps consumed by one prescribed dose. Defaults to 1 when unset. */
    fun pumpsPerDose(schedule: Schedule?): Int =
        schedule?.actuationsPerDose?.takeIf { it > 0 } ?: 1

    /** Amount to deduct from the bottle for one dose. */
    fun deductForDose(schedule: Schedule?): BigDecimal =
        BigDecimal(pumpsPerDose(schedule))

    data class Meter(
        val totalPumps: BigDecimal,
        val remainingPumps: BigDecimal,
        val pumpsPerDose: Int,
    ) {
        /** Whole doses left; a partial dose cannot be dispensed from a pump bottle. */
        val dosesLeft: Int
            get() = if (pumpsPerDose <= 0) {
                0
            } else {
                remainingPumps.divide(BigDecimal(pumpsPerDose), 0, RoundingMode.DOWN).toInt()
                    .coerceAtLeast(0)
            }

        val isEmpty: Boolean get() = remainingPumps.signum() <= 0

        /** Fraction of the bottle used, for a progress bar. */
        val usedFraction: Float
            get() {
                if (totalPumps.signum() <= 0) return 0f
                val used = totalPumps.subtract(remainingPumps)
                return (used.toDouble() / totalPumps.toDouble()).coerceIn(0.0, 1.0).toFloat()
            }
    }

    /**
     * Reads a bottle as a meter. When the batch records a pump count it is used; a batch
     * counted in something else (mL) cannot answer "how many pumps are left", so the
     * caller gets null instead of a misleading number.
     */
    fun meterFor(batch: InventoryBatch?, schedule: Schedule?): Meter? {
        if (batch == null) return null
        val unit = batch.unit.trim().lowercase()
        val countedInPumps = unit in setOf("dávka", "davka", "pump", "pumps", "spray", "actuation", "dose")
        if (!countedInPumps) return null
        return Meter(
            totalPumps = batch.initialQuantity,
            remainingPumps = batch.remainingQuantity,
            pumpsPerDose = pumpsPerDose(schedule),
        )
    }
}
