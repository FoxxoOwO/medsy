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

    /**
     * The old `api/1.0/csv/dlp_lecivepripravky.csv` endpoint is gone (HTTP 404). DLP is now
     * published as a **monthly zip** whose URL carries the release date, listed on this page, so
     * the address cannot be a constant any more.
     */
    const val DLP_CATALOG_URL = "$BASE_URL/katalog-otevrenych-dat/databaze-lecivych-pripravku-dlp"
    const val DLP_CSV_ENTRY = "dlp_lecivepripravky.csv"
    const val DOC_BASE = "https://sukl.gov.cz/download"

    fun pilUrl(suklCode: String): String = "$DOC_BASE/pil/PIL${pad(suklCode)}.pdf"
    fun spcUrl(suklCode: String): String = "$DOC_BASE/spc/SPC${pad(suklCode)}.pdf"

    private fun pad(code: String): String = code.padStart(7, '0')
}

/**
 * Finds the current DLP archive and pulls the medicines CSV out of it.
 *
 * SÚKL renames the archive on every monthly release (`DLP20261027.zip`), and the download is a
 * zip of several CSVs — not one CSV as it used to be. Both steps are pure functions so they can
 * be tested without the network.
 */
object SuklDlpDataset {

    private val ZIP_LINK =
        Regex("""https?://opendata\.sukl\.gov\.cz/soubory/[^"'\s<>]+\.zip""")

    /**
     * The CSV inside the archive is Czech Windows-1250, not UTF-8: decoding it as UTF-8 throws on
     * names such as "HYDROCHLOROTHIAZID LÉČIVA". ISO-8859-2 is the same for the letters used here
     * and exists on every runtime, so it is the fallback.
     */
    private val SUKL_CHARSET: java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName("windows-1250") }
            .getOrElse { java.nio.charset.Charset.forName("ISO-8859-2") }

    /** The newest DLP zip advertised on the catalog page, or null when the page changed shape. */
    fun zipUrl(catalogHtml: String): String? =
        ZIP_LINK.findAll(catalogHtml)
            .map { it.value.replace("&amp;", "&") }
            .lastOrNull { it.substringAfterLast('/').startsWith("DLP", ignoreCase = true) }

    /** The medicines table from the archive; null when the entry is missing or not a zip. */
    fun medicinesCsv(archive: ByteArray): String? {
        if (archive.size < 4 || archive[0] != 'P'.code.toByte() || archive[1] != 'K'.code.toByte()) {
            return null
        }
        return runCatching {
            java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name.equals(SuklConfig.DLP_CSV_ENTRY, ignoreCase = true)) {
                        return@use zip.readBytes().toString(SUKL_CHARSET)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }.getOrNull()
    }
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
