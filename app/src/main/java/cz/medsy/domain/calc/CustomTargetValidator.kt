package cz.medsy.domain.calc

import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import java.math.BigDecimal

/**
 * Validation for a user-defined therapeutic window ("doctor target").
 *
 * The realistic failure is a unit mix-up: typing 100 meaning pg/mL into a field that is
 * stored in pmol/L. That produces a window roughly 3.7x too low (or 27x too high for
 * testosterone), which the chart then draws without complaint. Plausibility warnings
 * are therefore based on the envelope the app already knows for that biomarker.
 */
object CustomTargetValidator {

    sealed interface Result {
        data class Valid(
            val range: ReferenceRangeProfile,
            val warnings: List<String>,
        ) : Result

        data class Invalid(val errors: List<String>) : Result
    }

    /**
     * Bounds used to decide whether a number "makes sense" as SI. The union of every
     * built-in profile is deliberately wide: a legitimately unusual but real window must
     * not be nagged about.
     */

    fun validate(
        code: String,
        minText: String,
        maxText: String,
        knownRanges: List<ReferenceRangeProfile> = ReferenceRanges.defaults,
    ): Result {
        val spec = Biomarkers.find(code)
            ?: return Result.Invalid(listOf("Unknown biomarker '$code'"))

        val min = minText.trim().takeIf { it.isNotEmpty() }?.let(::parseAmount)
        val max = maxText.trim().takeIf { it.isNotEmpty() }?.let(::parseAmount)

        if (minText.isNotBlank() && min == null) {
            return Result.Invalid(listOf("Minimum must be a number"))
        }
        if (maxText.isNotBlank() && max == null) {
            return Result.Invalid(listOf("Maximum must be a number"))
        }
        if (min == null && max == null) {
            return Result.Invalid(listOf("Enter at least one bound"))
        }

        val errors = ArrayList<String>()
        if (min != null && min.signum() <= 0) errors += "Minimum must be greater than zero"
        if (max != null && max.signum() <= 0) errors += "Maximum must be greater than zero"
        if (min != null && max != null && min >= max) {
            errors += "Minimum must be lower than maximum"
        }
        if (errors.isNotEmpty()) return Result.Invalid(errors)

        val warnings = ArrayList<String>()
        val envelope = envelopeFor(code, knownRanges)
        if (envelope != null) {
            listOfNotNull(min, max).forEach { value ->
                if (value > envelope.second || value < envelope.first) {
                    val hint = unitMixUpHint(spec, value, envelope, knownRanges, code)
                    if (hint != null) warnings += hint
                }
            }
        }

        return Result.Valid(
            range = ReferenceRangeProfile(
                biomarkerCode = code,
                profileType = ProfileType.CUSTOM,
                targetMinSi = min,
                targetMaxSi = max,
            ),
            warnings = warnings.distinct(),
        )
    }

    /**
     * Plausible envelope built from the *warning* bounds only.
     *
     * Target windows differ legitimately by patient group (a masculinising testosterone
     * target starts at 10.4 while a feminising one ends at 1.7), so including them would
     * swallow every odd number. The warning bounds exist precisely to answer "outside
     * this is concerning", which is the question being asked here.
     */
    private fun envelopeFor(
        code: String,
        knownRanges: List<ReferenceRangeProfile>,
    ): Pair<BigDecimal, BigDecimal>? {
        val matching = knownRanges.filter { it.biomarkerCode == code }
        val lows = matching.mapNotNull { it.warningLowSi }
        val highs = matching.mapNotNull { it.warningHighSi }
        if (lows.isEmpty() && highs.isEmpty()) return null
        val floor = lows.minOrNull() ?: BigDecimal.ZERO
        val ceiling = highs.maxOrNull() ?: BigDecimal("1000000")
        return floor to ceiling
    }

    /**
     * Warns only when the number is *outside* the plausible envelope as SI but *inside*
     * it once interpreted in the conventional unit. That is the signature of a unit
     * mix-up, and it avoids nagging about an unusual but genuine target.
     */
    /**
     * Warns only when the number is *outside* the plausible envelope as SI but *inside*
     * it once read in the conventional unit — the signature of a unit mix-up. The typed
     * number is therefore interpreted as the conventional unit and converted to SI
     * before comparing; doing it the other way round compares the wrong quantity.
     */
    private fun unitMixUpHint(
        spec: BiomarkerSpec,
        value: BigDecimal,
        envelope: Pair<BigDecimal, BigDecimal>,
        knownRanges: List<ReferenceRangeProfile>,
        code: String,
    ): String? {
        val conventional = spec.conventionalUnit ?: return null
        val asSi = runCatching { UnitConverter.convert(spec, value, conventional) }.getOrNull()
            ?: return null
        val insideEnvelope = asSi >= envelope.first && asSi <= envelope.second
        if (!insideEnvelope) return null
        return "Value $value ${spec.siUnit} is unusual for $code, but $value $conventional " +
            "equals $asSi ${spec.siUnit}, which is plausible. Did you mean $conventional?"
    }

    /** Converts a user-typed value from a conventional unit into SI, for the unit picker. */
    fun toSi(code: String, value: BigDecimal, unit: String): BigDecimal? {
        val spec = Biomarkers.find(code) ?: return null
        return runCatching { UnitConverter.convert(spec, value, unit) }.getOrNull()
    }

    private fun parseAmount(raw: String): BigDecimal? =
        runCatching { BigDecimal(raw.trim().replace(',', '.')) }.getOrNull()
}
