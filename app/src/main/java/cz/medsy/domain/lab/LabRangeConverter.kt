package cz.medsy.domain.lab

import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.UnitConverter
import java.math.BigDecimal

/**
 * Lab reports print the reference range in the same unit as the value ("100 - 200
 * pg/mL"). Stored values are normalised to SI, so the printed bounds must be
 * converted too — otherwise a 610 pmol/L result is compared against a ceiling that
 * was really 200 pg/mL and every result looks normal.
 *
 * An unrecognised unit keeps the original numbers: a range in the wrong scale is
 * less harmful than silently dropping it.
 */
object LabRangeConverter {

    fun toSi(
        code: String,
        min: BigDecimal?,
        max: BigDecimal?,
        unit: String,
    ): Pair<BigDecimal?, BigDecimal?> {
        val spec = Biomarkers.find(code) ?: return min to max
        return convert(spec, min, unit) to convert(spec, max, unit)
    }

    private fun convert(
        spec: cz.medsy.domain.calc.BiomarkerSpec,
        value: BigDecimal?,
        unit: String,
    ): BigDecimal? {
        if (value == null) return null
        return runCatching { UnitConverter.convert(spec, value, unit) }.getOrDefault(value)
    }
}
