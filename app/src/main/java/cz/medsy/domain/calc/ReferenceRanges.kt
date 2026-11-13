package cz.medsy.domain.calc

import cz.medsy.domain.model.ProfileType
import cz.medsy.domain.model.ReferenceRangeProfile
import java.math.BigDecimal

object ReferenceRanges {

    private fun p(
        code: String,
        type: ProfileType,
        min: String?,
        max: String?,
        optimalMin: String? = null,
        optimalMax: String? = null,
        warningLow: String? = null,
        warningHigh: String? = null,
    ) = ReferenceRangeProfile(
        biomarkerCode = code,
        profileType = type,
        targetMinSi = min?.let(::BigDecimal),
        targetMaxSi = max?.let(::BigDecimal),
        optimalMinSi = optimalMin?.let(::BigDecimal),
        optimalMaxSi = optimalMax?.let(::BigDecimal),
        warningLowSi = warningLow?.let(::BigDecimal),
        warningHighSi = warningHigh?.let(::BigDecimal),
    )

    /**
     * Values are in SI units (pmol/L, nmol/L, %...), matching [Biomarkers].
     * HRT targets follow WPATH SOC8 / Endocrine Society guidance:
     *  - Estradiol 100–200 pg/mL = 367–734 pmol/L (some clinics accept up to 1000)
     *  - Total testosterone < 50 ng/dL = < 1.7 nmol/L for feminizing therapy
     *  - Masculinizing testosterone 300–1000 ng/dL = 10.4–34.7 nmol/L
     */
    val defaults: List<ReferenceRangeProfile> = listOf(
        p("E2", ProfileType.HRT_FEM, "367", "734", "367", "550", warningLow = "184", warningHigh = "1100"),
        p("TEST_TOTAL", ProfileType.HRT_FEM, "0", "1.7", warningHigh = "2.5"),
        p("PRL", ProfileType.HRT_FEM, "0", "635", warningHigh = "1060"),
        p("K", ProfileType.HRT_FEM, "3.5", "5.1", warningHigh = "5.5"),

        p("TEST_TOTAL", ProfileType.HRT_MASC, "10.4", "34.7", "17.3", "27.7", warningLow = "8.0", warningHigh = "43.0"),
        p("HCT", ProfileType.HRT_MASC, "40", "50", warningHigh = "54"),
        p("E2", ProfileType.HRT_MASC, "0", "200", warningHigh = "367"),

        p("E2", ProfileType.CIS_F, "55", "1100", warningHigh = "1800"),
        p("TEST_TOTAL", ProfileType.CIS_F, "0.5", "2.4"),
        p("PRL", ProfileType.CIS_F, "0", "635"),
        p("P4", ProfileType.CIS_F, "0.3", "76"),

        p("E2", ProfileType.CIS_M, "41", "159"),
        p("TEST_TOTAL", ProfileType.CIS_M, "8.6", "29.0"),
        p("HCT", ProfileType.CIS_M, "40", "52"),

        p("ALT", ProfileType.CIS_F, "0.17", "0.58"),
        p("ALT", ProfileType.CIS_M, "0.17", "0.83"),
        p("AST", ProfileType.CIS_F, "0.17", "0.58"),
        p("AST", ProfileType.CIS_M, "0.17", "0.83"),
        p("CREA", ProfileType.CIS_F, "44", "97"),
        p("CREA", ProfileType.CIS_M, "62", "106"),
        p("GLU", ProfileType.CIS_F, "3.9", "5.6"),
        p("GLU", ProfileType.CIS_M, "3.9", "5.6"),
    )

    fun forBiomarker(code: String, type: ProfileType, custom: List<ReferenceRangeProfile> = emptyList()): ReferenceRangeProfile? {
        custom.firstOrNull { it.biomarkerCode == code && it.profileType == type }?.let { return it }
        return defaults.firstOrNull { it.biomarkerCode == code && it.profileType == type }
    }

    /**
     * The range printed on a specific lab report. These bounds live on the value
     * itself (per session), never in [defaults], so a null return here is expected
     * for LAB_PRINTED and the caller must fall back to the per-value bounds.
     */
    fun printedRange(code: String, minSi: java.math.BigDecimal?, maxSi: java.math.BigDecimal?): ReferenceRangeProfile? {
        if (minSi == null && maxSi == null) return null
        return ReferenceRangeProfile(
            biomarkerCode = code,
            profileType = ProfileType.LAB_PRINTED,
            targetMinSi = minSi,
            targetMaxSi = maxSi,
        )
    }

    fun allFor(code: String, custom: List<ReferenceRangeProfile> = emptyList()): List<ReferenceRangeProfile> {
        val customForCode = custom.filter { it.biomarkerCode == code }
        val known = defaults.filter { it.biomarkerCode == code }
        return (customForCode + known).distinctBy { it.profileType }
    }
}

enum class RangeStatus { LOW, IN_RANGE, HIGH, CRITICAL_LOW, CRITICAL_HIGH, NO_RANGE }

data class RangeEvaluation(
    val status: RangeStatus,
    val range: ReferenceRangeProfile?,
    val valueSi: BigDecimal,
    val deltaToMin: BigDecimal?,
    val deltaToMax: BigDecimal?,
)

object RangeEvaluator {

    fun evaluate(valueSi: BigDecimal, range: ReferenceRangeProfile?): RangeEvaluation {
        if (range == null) {
            return RangeEvaluation(RangeStatus.NO_RANGE, null, valueSi, null, null)
        }
        val min = range.targetMinSi
        val max = range.targetMaxSi
        val warnLow = range.warningLowSi
        val warnHigh = range.warningHighSi

        val status = when {
            warnLow != null && valueSi < warnLow -> RangeStatus.CRITICAL_LOW
            warnHigh != null && valueSi > warnHigh -> RangeStatus.CRITICAL_HIGH
            min != null && valueSi < min -> RangeStatus.LOW
            max != null && valueSi > max -> RangeStatus.HIGH
            else -> RangeStatus.IN_RANGE
        }

        return RangeEvaluation(
            status = status,
            range = range,
            valueSi = valueSi,
            deltaToMin = min?.let { valueSi.subtract(it) },
            deltaToMax = max?.let { valueSi.subtract(it) },
        )
    }
}
