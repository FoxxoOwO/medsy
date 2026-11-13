package cz.medsy.data.remote

import cz.medsy.domain.model.SuklMedication

/**
 * SÚKL open-data endpoints. SÚKL migrated from `www.sukl.cz` to `sukl.gov.cz` and
 * publishes the registered-medicines database as CSV on `opendata.sukl.gov.cz`.
 * The exact file name changes with each release, so it is injected rather than
 * hard-coded — see [SuklConfig.medicinesCsvUrl].
 */
object SuklConfig {
    const val BASE_URL = "https://opendata.sukl.gov.cz"
    const val MEDICINES_CSV_URL = "$BASE_URL/api/1.0/csv/dlp_lecivepripravky.csv"
    const val DOC_BASE = "https://sukl.gov.cz/download"

    fun pilUrl(suklCode: String): String = "$DOC_BASE/pil/PIL${pad(suklCode)}.pdf"
    fun spcUrl(suklCode: String): String = "$DOC_BASE/spc/SPC${pad(suklCode)}.pdf"

    private fun pad(code: String): String = code.padStart(7, '0')
}

/**
 * The SÚKL CSV is semicolon-separated, UTF-8, with a header row whose column names
 * are Czech. Column names vary slightly between releases, so lookup is done by a
 * set of accepted aliases rather than a fixed index.
 */
object SuklCsvParser {

    private val CODE_ALIASES = listOf("KOD_SUKL", "KOD", "SUKL_KOD", "KOD_SUKL_7")
    private val NAME_ALIASES = listOf("NAZEV", "NAZEV_LP", "NAZEV_PRIPRAVKU", "NAZEV_LECIVEHO_PRIPRAVKU")
    private val SUBSTANCE_ALIASES = listOf("LATKA", "LECIVA_LATKA", "UCINNA_LATKA", "NAZEV_LATKY")
    private val ATC_ALIASES = listOf("ATC", "ATC_KOD", "KOD_ATC")
    private val STRENGTH_ALIASES = listOf("SILA", "SILA_LP", "SILA_PRIPRAVKU")
    private val FORM_ALIASES = listOf("FORMA", "LECIVA_FORMA", "FORMA_LP")
    private val HOLDER_ALIASES = listOf("DRZITEL", "DRZITEL_ROZHODNUTI", "ZADATEL")
    private val GTIN_ALIASES = listOf("GTIN", "EAN", "EAN_KOD", "KOD_EAN", "CAROVY_KOD", "GTIN_KOD")

    fun parse(csv: String): List<SuklMedication> {
        val lines = csv.lineSequence()
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        val header = splitLine(lines.first())
            .mapIndexed { index, name -> index to normalize(name) }
            .toMap()

        val codeIndex = findColumn(header, CODE_ALIASES) ?: return emptyList()
        val nameIndex = findColumn(header, NAME_ALIASES) ?: return emptyList()
        val substanceIndex = findColumn(header, SUBSTANCE_ALIASES)
        val atcIndex = findColumn(header, ATC_ALIASES)
        val strengthIndex = findColumn(header, STRENGTH_ALIASES)
        val formIndex = findColumn(header, FORM_ALIASES)
        val holderIndex = findColumn(header, HOLDER_ALIASES)
        val gtinIndex = findColumn(header, GTIN_ALIASES)

        return lines.drop(1).mapNotNull { line ->
            val cols = splitLine(line)
            val code = cols.getOrNull(codeIndex)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = cols.getOrNull(nameIndex)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SuklMedication(
                suklCode = code.padStart(7, '0'),
                name = name,
                gtin = gtinIndex?.let { cols.getOrNull(it) }
                    ?.filter(Char::isDigit)
                    ?.takeIf { it.length in 8..14 }
                    ?.padStart(14, '0'),
                activeSubstance = substanceIndex?.let { cols.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
                atcCode = atcIndex?.let { cols.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
                strength = strengthIndex?.let { cols.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
                formName = formIndex?.let { cols.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
                holder = holderIndex?.let { cols.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun findColumn(header: Map<Int, String>, aliases: List<String>): Int? =
        header.entries.firstOrNull { (_, name) -> aliases.any { name.contains(it) } }?.key

    private fun normalize(value: String): String =
        value.removeSurrounding("\"")
            .uppercase()
            .replace(' ', '_')

    /** Minimal RFC-4180-aware splitter; handles quoted fields containing the delimiter. */
    internal fun splitLine(line: String, delimiter: Char = ';'): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val c = line[index]
            when {
                c == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                c == '"' -> inQuotes = !inQuotes
                c == delimiter && !inQuotes -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            index++
        }
        out += current.toString()
        return out
    }
}
