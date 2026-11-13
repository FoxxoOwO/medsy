package cz.medsy.domain.util

import cz.medsy.domain.model.ParsedBarcode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Decoder for the payloads ML Kit hands back for EAN-13 boxes and EU FMD DataMatrix codes.
 *
 * Two encodings have to be understood because both are common in Czech pharmacies:
 *
 *  - **EAN-13** — 13 digits, no application identifiers at all.
 *  - **GS1 DataMatrix (ISO/IEC 15434)** — a stream of *application identifiers* (AI),
 *    e.g. `01085950000001261726093010LOT123`. AIs come in two flavours: fixed length
 *    (the value runs for a known number of characters, e.g. (01) is always 14) and
 *    variable length (the value runs until the FNC1 group separator `0x1D` or the end of
 *    the payload, e.g. (10) batch). Printing scanners may emit the same data with
 *    brackets — `(01)08595000000126(17)260930` — which is human-readable GS1 notation.
 *
 * The parser is intentionally strict about lengths and dates and forgiving about
 * separators, because a mis-parsed expiry date silently creates an inventory batch that
 * claims to be valid for decades.
 */
object Gs1BarcodeParser {

    private const val FNC1 = '\u001D'

    /** Value length per AI; negative/absent means "variable, terminated by FNC1". */
    private val AI_LENGTHS: Map<String, Int> = mapOf(
        "00" to 18,
        "01" to 14,
        "02" to 14,
        "10" to -1,
        "11" to 6,
        "13" to 6,
        "15" to 6,
        "17" to 6,
        "20" to 2,
        "21" to -1,
        "30" to -1,
        "37" to -1,
        "240" to -1,
        "400" to -1,
        "403" to -1,
        "410" to 13,
        "414" to 13,
        "417" to 13,
    )

    /**
     * Upper bound for variable-length AIs. GS1 caps a lot or serial at 20 characters, and
     * dropping the excess matters: a 26-character lot number would otherwise be stored in full
     * and never match the label the pharmacist reads.
     */
    private val AI_MAX_LENGTHS: Map<String, Int> = mapOf(
        "10" to 20,
        "21" to 20,
        "30" to 8,
        "37" to 8,
        "240" to 30,
        "400" to 30,
        "403" to 30,
    )

    /** Symbology prefixes some scanners keep in the raw value (`]d2` = DataMatrix). */
    private val SYMBOLOGY_PREFIXES = listOf("]d2", "]C1", "]Q3", "]e0")

    private val BRACKETED_AI = Regex("""\((?:01|02|10|11|13|15|17|20|21|30|37|240|400|403|410|414|417)\)""")

    private val DIGITS = Regex("""\d+""")

    /**
     * Parses [raw] into a [ParsedBarcode], or returns null when the payload carries no
     * GS1 field worth looking up (a random QR code, a URL, text).
     */
    fun parse(raw: String): ParsedBarcode? {
        if (raw.isBlank()) return null
        val cleaned = stripAimPrefix(raw)
        val fields = if (cleaned.contains('(')) parseBracketed(cleaned) else parseFields(cleaned)

        val gtin = (fields["01"] ?: fields["02"])?.let(::normalizeGtin)
        val expiry = fields["17"]?.let(::parseExpiration)
        val batch = fields["10"]?.trim()?.takeIf { it.isNotEmpty() }
        val serial = fields["21"]?.trim()?.takeIf { it.isNotEmpty() }
        val quantity = fields["30"]?.trim()?.takeIf { it.isNotEmpty() }

        if (gtin == null && batch == null && serial == null && expiry == null) {
            // A plain EAN-13 from a linear scanner carries no AIs at all. Falling back here means
            // callers do not have to know which symbology produced the string.
            return parseEan(cleaned)
        }
        return ParsedBarcode(
            rawValue = raw,
            gtin14 = gtin,
            ean13 = gtin?.let(ParsedBarcode::ean13Of),
            expirationDate = expiry,
            batchNumber = batch,
            serialNumber = serial,
            packageQuantity = quantity,
        )
    }

    /**
     * Parses a bare EAN-13/GTIN string (no AIs). Returns null when the payload is not
     * 13 or 14 digits — a shortened/typo'd code must not be looked up.
     */
    fun parseEan(raw: String): ParsedBarcode? {
        val digits = DIGITS.find(raw)?.value ?: return null
        if (digits.length !in 8..14) return null
        val gtin14 = when (digits.length) {
            14 -> digits
            13 -> ParsedBarcode.gtin14Of(digits)
            else -> null
        } ?: return null
        return ParsedBarcode(
            rawValue = raw,
            gtin14 = gtin14,
            ean13 = ParsedBarcode.ean13Of(gtin14),
        )
    }

    /**
     * Extracts AI -> value from a bracketed-free payload, honouring FNC1 separators and
     * the fixed length of each AI. Unknown AIs stop parsing: guessing past them would
     * reinterpret the batch number as a date.
     */
    fun parseFields(raw: String): Map<String, String> {
        val payload = stripAimPrefix(raw)
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < payload.length) {
            if (payload[index] == FNC1) {
                index++
                continue
            }
            val ai = matchAi(payload, index) ?: break
            index += ai.length
            val length = AI_LENGTHS[ai] ?: 0
            if (length > 0) {
                val end = minOf(index + length, payload.length)
                result[ai] = payload.substring(index, end)
                index = end
            } else {
                val separator = payload.indexOf(FNC1, index)
                val rawEnd = if (separator >= 0) separator else payload.length
                val cap = AI_MAX_LENGTHS[ai] ?: Int.MAX_VALUE
                result[ai] = payload.substring(index, rawEnd).trim().take(cap)
                index = if (separator >= 0) separator + 1 else payload.length
            }
        }
        return result
    }

    /** Scanners that keep the ISO/IEC 15434 symbology identifier must not poison AI matching. */
    private fun stripAimPrefix(raw: String): String {
        val trimmed = raw.trim()
        val prefix = SYMBOLOGY_PREFIXES.firstOrNull { trimmed.startsWith(it) } ?: return trimmed
        return trimmed.removePrefix(prefix)
    }

    /** Extracts AI -> value from human-readable `(01)…(17)…` notation. */
    fun parseBracketed(raw: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val matches = BRACKETED_AI.findAll(raw).toList()
        matches.forEachIndexed { position, match ->
            val ai = match.value.trim('(', ')')
            val valueStart = match.range.last + 1
            val valueEnd = matches.getOrNull(position + 1)?.range?.first ?: raw.length
            if (valueStart > valueEnd) return@forEachIndexed
            val value = raw.substring(valueStart, valueEnd).trim().trim(FNC1)
            val length = AI_LENGTHS[ai] ?: 0
            result[ai] = if (length > 0) {
                value.take(length)
            } else {
                value.take(AI_MAX_LENGTHS[ai] ?: Int.MAX_VALUE)
            }
        }
        return result
    }

    /**
     * Interprets an AI (17) value. GS1 has two "partial date" conventions that matter in
     * practice: day `00` means *the last day of that month* (expiry is inclusive), and
     * month `00` means December. A date that cannot exist yields null rather than a
     * guessed one.
     */
    fun parseExpiration(value: String): LocalDate? {
        val digits = value.filter(Char::isDigit)
        if (digits.length != 6) return null
        val yy = digits.substring(0, 2).toIntOrNull() ?: return null
        val mm = digits.substring(2, 4).toIntOrNull() ?: return null
        val dd = digits.substring(4, 6).toIntOrNull() ?: return null
        val year = if (yy >= 50) 1900 + yy else 2000 + yy
        val month = if (mm == 0) 12 else mm
        if (month !in 1..12) return null
        val monthLength = runCatching { YearMonth.of(year, month).lengthOfMonth() }.getOrNull()
            ?: return null
        val day = if (dd == 0) monthLength else dd
        if (day !in 1..monthLength) return null
        return LocalDate.of(year, month, day)
    }

    /** AI (11) production date; same YYMMDD encoding as (17). */
    fun parseProductionDate(value: String): LocalDate? = parseExpiration(value)

    /**
     * Longest AI first, and a fixed-length AI is only accepted when its value fits and
     * has the shape the AI requires. Without the shape check, the payload
     * `0108595…` inside a batch number could be read as a GTIN.
     */
    private fun matchAi(input: String, index: Int): String? {
        for (length in intArrayOf(4, 3, 2)) {
            val end = index + length
            if (end > input.length) continue
            val candidate = input.substring(index, end)
            val valueLength = AI_LENGTHS[candidate] ?: continue
            if (isPlausible(candidate, valueLength, input, end)) return candidate
        }
        return null
    }

    private fun isPlausible(ai: String, valueLength: Int, input: String, valueStart: Int): Boolean {
        if (valueLength <= 0) return true
        val valueEnd = valueStart + valueLength
        if (valueEnd > input.length) return false
        val value = input.substring(valueStart, valueEnd)
        return when (ai) {
            "01", "02", "11", "13", "15", "17" -> value.all(Char::isDigit)
            else -> true
        }
    }

    /**
     * AI (01) is defined as 14 digits, but plenty of producers print 13-digit EANs there.
     * Both are normalised to 14 so a lookup needs one comparison, not two.
     */
    private fun normalizeGtin(value: String): String? {
        val digits = value.filter(Char::isDigit)
        return when (digits.length) {
            14 -> digits
            13 -> ParsedBarcode.gtin14Of(digits)
            else -> null
        }
    }
}
