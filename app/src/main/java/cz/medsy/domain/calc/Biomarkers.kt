package cz.medsy.domain.calc

import cz.medsy.domain.message.MessageKey
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class Dimension { MASS_CONCENTRATION, MOLAR_CONCENTRATION, RATIO_PERCENT, COUNT, ENZYME }

data class BiomarkerSpec(
    val code: String,
    /** Stable, language-independent name used for parsing and search. Never shown as-is. */
    val displayName: String,
    /** Resource-backed label the UI and the PDF report render instead of [displayName]. */
    val displayNameKey: MessageKey,
    val siUnit: String,
    val conventionalUnit: String?,
    val dimension: Dimension,
    val molarMassGPerMol: BigDecimal?,
    val conventionalToSiFactor: BigDecimal?,
    val decimals: Int = 1,
)

private val MC = MathContext(20, RoundingMode.HALF_UP)

internal fun molarFactor(molarMass: BigDecimal): BigDecimal {
    // 1 ng/mL * (1000 / MW) = nmol/L  ->  conventional -> SI for mass/molar pairs
    return BigDecimal(1000).divide(molarMass, MC)
}

object Biomarkers {

    private val ESTRADIOL_MW = BigDecimal("272.38")
    private val TESTOSTERONE_MW = BigDecimal("288.42")
    private val PROGESTERONE_MW = BigDecimal("314.46")
    private val PROLACTIN_MW = BigDecimal("23000")
    private val SHBG_MW = BigDecimal("44000")

    val E2 = BiomarkerSpec(
        code = "E2",
        displayName = "Estradiol",
        displayNameKey = MessageKey.BIOMARKER_E2,
        siUnit = "pmol/L",
        conventionalUnit = "pg/mL",
        dimension = Dimension.MASS_CONCENTRATION,
        molarMassGPerMol = ESTRADIOL_MW,
        conventionalToSiFactor = BigDecimal("3.671"),
        decimals = 0,
    )

    val TEST_TOTAL = BiomarkerSpec(
        code = "TEST_TOTAL",
        displayName = "Testosterone (total)",
        displayNameKey = MessageKey.BIOMARKER_TEST_TOTAL,
        siUnit = "nmol/L",
        conventionalUnit = "ng/dL",
        dimension = Dimension.MASS_CONCENTRATION,
        molarMassGPerMol = TESTOSTERONE_MW,
        conventionalToSiFactor = BigDecimal("0.0347"),
        decimals = 2,
    )

    val PRL = BiomarkerSpec(
        code = "PRL",
        displayName = "Prolactin",
        displayNameKey = MessageKey.BIOMARKER_PRL,
        siUnit = "mIU/L",
        conventionalUnit = "ng/mL",
        dimension = Dimension.MASS_CONCENTRATION,
        molarMassGPerMol = PROLACTIN_MW,
        conventionalToSiFactor = BigDecimal("21.2"),
        decimals = 0,
    )

    val P4 = BiomarkerSpec(
        code = "P4",
        displayName = "Progesterone",
        displayNameKey = MessageKey.BIOMARKER_P4,
        siUnit = "nmol/L",
        conventionalUnit = "ng/mL",
        dimension = Dimension.MASS_CONCENTRATION,
        molarMassGPerMol = PROGESTERONE_MW,
        conventionalToSiFactor = BigDecimal("3.18"),
        decimals = 2,
    )

    val SHBG = BiomarkerSpec(
        code = "SHBG",
        displayName = "SHBG",
        displayNameKey = MessageKey.BIOMARKER_SHBG,
        siUnit = "nmol/L",
        conventionalUnit = null,
        dimension = Dimension.MOLAR_CONCENTRATION,
        molarMassGPerMol = SHBG_MW,
        conventionalToSiFactor = BigDecimal.ONE,
        decimals = 1,
    )

    val LH = BiomarkerSpec("LH", "LH", MessageKey.BIOMARKER_LH, "IU/L", null, Dimension.MOLAR_CONCENTRATION, null, BigDecimal.ONE, 2)
    val FSH = BiomarkerSpec("FSH", "FSH", MessageKey.BIOMARKER_FSH, "IU/L", null, Dimension.MOLAR_CONCENTRATION, null, BigDecimal.ONE, 2)
    val HCT = BiomarkerSpec("HCT", "Hematocrit", MessageKey.BIOMARKER_HCT, "%", null, Dimension.RATIO_PERCENT, null, BigDecimal.ONE, 1)
    val HGB = BiomarkerSpec("HGB", "Hemoglobin", MessageKey.BIOMARKER_HGB, "g/L", "g/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("10"), 0)
    val ALT = BiomarkerSpec("ALT", "ALT", MessageKey.BIOMARKER_ALT, "µkat/L", "U/L", Dimension.ENZYME, null, BigDecimal("0.0167"), 2)
    val AST = BiomarkerSpec("AST", "AST", MessageKey.BIOMARKER_AST, "µkat/L", "U/L", Dimension.ENZYME, null, BigDecimal("0.0167"), 2)
    val GGT = BiomarkerSpec("GGT", "GGT", MessageKey.BIOMARKER_GGT, "µkat/L", "U/L", Dimension.ENZYME, null, BigDecimal("0.0167"), 2)
    val ALP = BiomarkerSpec("ALP", "ALP", MessageKey.BIOMARKER_ALP, "µkat/L", "U/L", Dimension.ENZYME, null, BigDecimal("0.0167"), 2)
    val BILI = BiomarkerSpec("BILI", "Bilirubin", MessageKey.BIOMARKER_BILI, "µmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("17.1"), 1)
    val CREA = BiomarkerSpec("CREA", "Creatinine", MessageKey.BIOMARKER_CREA, "µmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("88.4"), 0)
    val GLU = BiomarkerSpec("GLU", "Glucose", MessageKey.BIOMARKER_GLU, "mmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("0.0555"), 2)
    val CHOL = BiomarkerSpec("CHOL", "Total cholesterol", MessageKey.BIOMARKER_CHOL, "mmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("0.0259"), 2)
    val LDL = BiomarkerSpec("LDL", "LDL cholesterol", MessageKey.BIOMARKER_LDL, "mmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("0.0259"), 2)
    val HDL = BiomarkerSpec("HDL", "HDL cholesterol", MessageKey.BIOMARKER_HDL, "mmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("0.0259"), 2)
    val TAG = BiomarkerSpec("TAG", "Triglycerides", MessageKey.BIOMARKER_TAG, "mmol/L", "mg/dL", Dimension.MASS_CONCENTRATION, null, BigDecimal("0.0113"), 2)
    val K = BiomarkerSpec("K", "Potassium", MessageKey.BIOMARKER_K, "mmol/L", null, Dimension.MOLAR_CONCENTRATION, null, BigDecimal.ONE, 2)
    val NA = BiomarkerSpec("NA", "Sodium", MessageKey.BIOMARKER_NA, "mmol/L", null, Dimension.MOLAR_CONCENTRATION, null, BigDecimal.ONE, 0)
    val FERR = BiomarkerSpec("FERR", "Ferritin", MessageKey.BIOMARKER_FERR, "µg/L", "ng/mL", Dimension.MASS_CONCENTRATION, null, BigDecimal.ONE, 0)
    val TSH = BiomarkerSpec("TSH", "TSH", MessageKey.BIOMARKER_TSH, "mIU/L", null, Dimension.MOLAR_CONCENTRATION, null, BigDecimal.ONE, 3)

    val all: List<BiomarkerSpec> = listOf(
        E2, TEST_TOTAL, PRL, P4, SHBG, LH, FSH, HCT, HGB, ALT, AST, GGT, ALP, BILI,
        CREA, GLU, CHOL, LDL, HDL, TAG, K, NA, FERR, TSH,
    )

    private val byCode = all.associateBy { it.code }

    fun find(code: String): BiomarkerSpec? = byCode[code.uppercase()]

    fun findOrThrow(code: String): BiomarkerSpec =
        find(code) ?: error("Unknown biomarker code '$code'")

    fun search(prefix: String): List<BiomarkerSpec> {
        val q = prefix.trim().uppercase()
        if (q.isEmpty()) return all
        return all.filter { it.code.contains(q) || it.displayName.uppercase().contains(q) }
    }
}

object UnitConverter {

    private val MC = MathContext(20, RoundingMode.HALF_UP)

    fun conventionalToSi(spec: BiomarkerSpec, value: BigDecimal): BigDecimal {
        val factor = spec.conventionalToSiFactor
            ?: error("${spec.code} has no conventional unit")
        return (value * factor).round(spec.decimals)
    }

    fun siToConventional(spec: BiomarkerSpec, value: BigDecimal): BigDecimal {
        val factor = spec.conventionalToSiFactor
            ?: error("${spec.code} has no conventional unit")
        if (factor.signum() == 0) error("zero conversion factor for ${spec.code}")
        return (value.divide(factor, MC)).round(spec.decimals)
    }

    fun convert(
        spec: BiomarkerSpec,
        value: BigDecimal,
        fromUnit: String,
    ): BigDecimal {
        val u = fromUnit.trim().lowercase()
        val si = spec.siUnit.lowercase()
        val conv = spec.conventionalUnit?.lowercase()
        return when {
            u == si -> value
            conv != null && u == conv -> conventionalToSi(spec, value)
            else -> error("Unsupported unit '$fromUnit' for ${spec.code} (SI is ${spec.siUnit})")
        }
    }

    fun toSiUnit(spec: BiomarkerSpec, value: BigDecimal, fromUnit: String): BigDecimal =
        convert(spec, value, fromUnit)

    fun normalizeUnit(spec: BiomarkerSpec, unit: String): String {
        val u = unit.trim().lowercase()
        val conv = spec.conventionalUnit?.lowercase()
        return if (conv != null && u == conv) spec.siUnit else spec.siUnit
    }
}

fun BigDecimal.round(decimals: Int): BigDecimal =
    setScale(decimals, RoundingMode.HALF_UP)

fun BigDecimal.isZeroOrPositive(): Boolean = signum() >= 0
