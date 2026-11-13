package cz.medsy.domain.scanner

import cz.medsy.domain.model.ScannedPackage
import cz.medsy.domain.model.Symbology
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parser for GS1 application identifiers as encoded in EU FMD DataMatrix codes.
 *
 * Supported AIs (fixed length unless FNC1-terminated):
 *  - (01) GTIN-14          — fixed 14
 *  - (17) expiration YYMMDD — fixed 6
 *  - (10) batch/lot        — variable
 *  - (21) serial           — variable
 *  - (11) production date  — fixed 6
 *
 * DataMatrix has no group separators, so variable-length AIs are terminated by FNC1
 * (0x1D in the raw payload). Parsers must therefore read fixed-length AIs first and
 * only treat the remainder as variable.
 */
object Gs1Parser {

    private const val FNC1 = '\u001D'
    private val AI_LENGTHS = mapOf(
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
        "240" to -1,
        "30" to -1,
        "37" to -1,
        "400" to -1,
        "403" to -1,
        "410" to 13,
        "414" to 13,
        "417" to 13,
    )

    fun parse(raw: String): ScannedPackage? {
        if (raw.isBlank()) return null
        val fields = parseFields(raw)
        val gtin = fields["01"] ?: fields["02"]
        val expiry = fields["17"]?.let(::parseGs1Date)
        val batch = fields["10"]
        val serial = fields["21"]
        val quantity = fields["30"]

        if (gtin == null && batch == null && serial == null && expiry == null) return null

        return ScannedPackage(
            gtin = gtin,
            expirationDate = expiry,
            batchNumber = batch,
            serialNumber = serial,
            packageQuantity = quantity,
            raw = raw,
            symbology = Symbology.GS1_DATAMATRIX,
        )
    }

    /** Extracts AI -> value map, handling FNC1 separators and fixed-length AIs. */
    fun parseFields(raw: String): Map<String, String> {
        val cleaned = raw
            .removePrefix("]d2")
            .removePrefix("]C1")
            .removePrefix("]Q3")
            .trim()

        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < cleaned.length) {
            if (cleaned[index] == FNC1) {
                index++
                continue
            }
            val (ai, aiLength) = matchAi(cleaned, index) ?: break
            index += aiLength
            val length = AI_LENGTHS[ai] ?: 0
            if (length > 0) {
                val end = minOf(index + length, cleaned.length)
                result[ai] = cleaned.substring(index, end)
                index = end
            } else {
                val separator = cleaned.indexOf(FNC1, index)
                val end = if (separator >= 0) separator else cleaned.length
                result[ai] = cleaned.substring(index, end).trim()
                index = end + 1
            }
        }
        return result
    }

    /**
     * AI matching tries the longest known AI first ("01" over "0108" is impossible
     * because "0108" is not an AI), and rejects a candidate whose fixed-length value
     * would not fit in the remaining payload. Variable-length AIs may appear without
     * an FNC1 terminator only at the very end of the payload.
     */
    private fun matchAi(input: String, index: Int): Pair<String, Int>? {
        // Longest AI first: "01" must win over "0108", and "010" over "01".
        val candidates = ArrayList<Pair<String, Int>>()
        for (len in intArrayOf(4, 3, 2)) {
            if (index + len > input.length) continue
            val candidate = input.substring(index, index + len)
            if (AI_LENGTHS.containsKey(candidate)) candidates += candidate to len
        }
        return candidates.firstOrNull { (ai, _) -> isPlausible(ai, input, index) }
    }

    private fun isPlausible(ai: String, input: String, index: Int): Boolean {
        val length = AI_LENGTHS[ai] ?: return true
        if (length <= 0) return true
        val valueStart = index + ai.length
        val valueEnd = valueStart + length
        if (valueEnd > input.length) return false
        val value = input.substring(valueStart, valueEnd)
        return when (ai) {
            "01", "02" -> value.all(Char::isDigit)
            "11", "13", "15", "17" -> value.all(Char::isDigit)
            else -> true
        }
    }

    fun parseGs1Date(value: String): LocalDate? {
        if (value.length != 6) return null
        val digits = value.filter(Char::isDigit)
        if (digits.length != 6) return null
        val yy = digits.substring(0, 2).toIntOrNull() ?: return null
        val mm = digits.substring(2, 4).toIntOrNull() ?: return null
        val dd = digits.substring(4, 6).toIntOrNull() ?: return null
        val month = if (mm == 0) 12 else mm
        val day = normalizeDay(yy, month, dd)
        val year = if (yy >= 50) 1900 + yy else 2000 + yy
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /**
     * GS1 allows day 00 meaning "last day of month", and expiry is inclusive,
     * so a code reading 2026-09-00 means the product expires at the *end* of Sept 2026.
     */
    private fun normalizeDay(yy: Int, month: Int, dd: Int): Int {
        if (dd != 0) return dd
        val year = if (yy >= 50) 1900 + yy else 2000 + yy
        return runCatching { LocalDate.of(year, month, 1).lengthOfMonth() }.getOrDefault(28)
    }

    fun toGtin13(gtin14: String): String? {
        if (gtin14.length != 14) return null
        return gtin14.substring(1)
    }

    fun formatExpiry(value: LocalDate?): String? =
        value?.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun checksumDigitValid(ean13: String): Boolean = Ean13.isValid(ean13)

    @Suppress("unused")
    private fun parseSafe(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }
}

object Ean13 {

    fun isValid(code: String): Boolean {
        val digits = code.filter(Char::isDigit)
        if (digits.length != 13) return false
        val check = digits.last().digitToInt()
        val sum = digits.dropLast(1).mapIndexed { index, c ->
            val value = c.digitToInt()
            if (index % 2 == 0) value else value * 3
        }.sum()
        return (10 - (sum % 10)) % 10 == check
    }

    fun normalize(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        return when {
            digits.length == 13 && isValid(digits) -> digits
            digits.length == 14 && digits.startsWith("0") && isValid(digits.drop(1)) -> digits.drop(1)
            else -> null
        }
    }

    fun toGtin14(ean13: String): String = "0$ean13"

    fun checkDigit(prefix12: String): Int {
        val sum = prefix12.mapIndexed { index, c ->
            val value = c.digitToInt()
            if (index % 2 == 0) value else value * 3
        }.sum()
        return (10 - (sum % 10)) % 10
    }
}
