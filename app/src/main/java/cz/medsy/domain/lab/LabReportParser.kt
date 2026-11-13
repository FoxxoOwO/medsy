package cz.medsy.domain.lab

import cz.medsy.domain.calc.Biomarkers
import cz.medsy.domain.calc.BiomarkerSpec
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * Parses free text extracted from a Czech lab report (Synlab, AeskuLab, Spadia, EUC).
 *
 * The hard part is not the numbers but the labels: labs use Czech names ("Estradiol",
 * "Testosteron celkový", "Krevní obraz – hematokrit") and sometimes different units.
 * Matching is therefore done on a normalized, diacritics-stripped alias set, and the
 * unit printed on the report always wins over the default unit.
 */
object LabReportParser {

    data class ParsedRow(
        val biomarkerCode: String,
        val displayName: String,
        val rawValue: BigDecimal,
        val rawUnit: String,
        val labMin: BigDecimal?,
        val labMax: BigDecimal?,
        val line: String,
    )

    private data class Alias(val code: String, val patterns: List<String>)

    private val aliases = listOf(
        Alias("E2", listOf("estradiol", "estradiol (e2)", "e2 ", "17-beta estradiol")),
        Alias("TEST_TOTAL", listOf("testosteron celkovy", "testosteron", "testosterone", "testosteron total")),
        Alias("PRL", listOf("prolaktin", "prolactin", "prl")),
        Alias("P4", listOf("progesteron", "progesterone")),
        Alias("SHBG", listOf("shbg", "globulin vazajici pohlavni hormony")),
        Alias("LH", listOf("lutropin", "luteinizacni hormon", "lh ") ),
        Alias("FSH", listOf("folitropin", "folikulostimulacni hormon", "fsh")),
        Alias("HCT", listOf("hematokrit", "hematocrit", "hct")),
        Alias("HGB", listOf("hemoglobin", "hgb", "hb ")),
        Alias("ALT", listOf("alt", "alaninaminotransferaza", "alanin aminotransferaza")),
        Alias("AST", listOf("ast", "aspartataminotransferaza", "aspartat aminotransferaza")),
        Alias("GGT", listOf("ggt", "gama-glutamyltransferaza", "gama glutamyltransferaza")),
        Alias("ALP", listOf("alp", "alkalicka fosfataza")),
        Alias("BILI", listOf("bilirubin", "bilirubin celkovy")),
        Alias("CREA", listOf("kreatinin", "creatinine", "crea")),
        Alias("GLU", listOf("glukoza", "glukosa", "glucose", "glykemie")),
        Alias("CHOL", listOf("cholesterol celkovy", "celkovy cholesterol", "cholesterol")),
        Alias("LDL", listOf("ldl cholesterol", "ldl")),
        Alias("HDL", listOf("hdl cholesterol", "hdl")),
        Alias("TAG", listOf("triacylglyceroly", "triglyceridy", "triglycerides", "tag")),
        Alias("K", listOf("kalium", "draslik", "potassium")),
        Alias("NA", listOf("natrium", "sodik", "sodium")),
        Alias("FERR", listOf("ferritin", "ferritin")),
        Alias("TSH", listOf("tsh", "tyreotropin", "thyreotropin")),
    )

    private val unitPattern = Regex(
        "(pmol/l|nmol/l|pg/ml|ng/ml|ng/dl|miu/l|iu/l|µkat/l|ukat/l|umol/l|µmol/l|mmol/l|mg/dl|g/l|g/dl|%|ug/l|µg/l)",
        RegexOption.IGNORE_CASE,
    )

    private val numberPattern = Regex("[-+]?\\d+[.,]?\\d*")

    fun isRecognized(text: String): Boolean = parse(text).isNotEmpty()

    fun parse(text: String): List<ParsedRow> {
        val rows = ArrayList<ParsedRow>()
        val seen = mutableSetOf<String>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.length < 4) continue

            val normalizedLine = normalize(line)
            val alias = aliases.firstOrNull { candidate ->
                candidate.patterns.any { pattern ->
                    normalizedLine.contains(normalize(pattern).trim()) ||
                        normalizedLine.startsWith(normalize(pattern).trim())
                }
            } ?: continue

            if (alias.code in seen) continue

            val unitMatch = unitPattern.find(line) ?: continue
            val unit = unitMatch.value.lowercase(Locale.ROOT).replace("ukat", "µkat").replace("umol", "µmol")
                .replace("ug/l", "µg/l")

            val numbers = numberPattern.findAll(line)
                .mapNotNull { token -> token.value.toBigDecimalOrNull() }
                .toList()
            if (numbers.isEmpty()) continue

            val value = numbers.firstOrNull { it.signum() >= 0 } ?: continue
            val (min, max) = extractLabRange(line)

            rows += ParsedRow(
                biomarkerCode = alias.code,
                displayName = Biomarkers.find(alias.code)?.displayName ?: alias.code,
                rawValue = value,
                rawUnit = unit,
                labMin = min,
                labMax = max,
                line = line,
            )
            seen += alias.code
        }
        return rows
    }

    /**
     * Lab ranges appear as "3.9 - 5.6" or "(3.9–5.6)" after the value. We only accept
     * a pair of numbers that are clearly delimited by a dash, to avoid treating two
     * unrelated numbers on the line as a range.
     */
    internal fun extractLabRange(line: String): Pair<BigDecimal?, BigDecimal?> {
        val rangePattern = Regex("(\\d+[.,]?\\d*)\\s*[-–]\\s*(\\d+[.,]?\\d*)")
        val match = rangePattern.find(line) ?: return null to null
        val min = match.groupValues[1].replace(',', '.').toBigDecimalOrNull()
        val max = match.groupValues[2].replace(',', '.').toBigDecimalOrNull()
        return min to max
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(this.replace(',', '.')) }.getOrNull()

    internal fun normalize(value: String): String {
        val stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return stripped.replace(Regex("\\s+"), " ")
    }

    fun specFor(code: String): BiomarkerSpec? = Biomarkers.find(code)
}
