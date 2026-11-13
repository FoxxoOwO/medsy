package cz.medsy.domain.scanner

import cz.medsy.domain.model.Prescription
import cz.medsy.domain.model.PrescriptionItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Czech eRecept dispensing codes appear in two shapes:
 *  1. A QR payload containing the 12-character prescription code (often as a URL
 *     such as https://www.epreskripce.cz/...?code=XXXX), and
 *  2. Human-readable codes printed as "ABCD1234EFGH" (12 chars, case-insensitive).
 *
 * Prescriptions are valid 14 days from issue unless the prescriber specifies
 * otherwise, which is the default applied here.
 */
object EReceptParser {

    private val CODE_REGEX = Regex("\\b([A-Za-z0-9]{12})\\b")
    private val URL_CODE_PARAM = Regex("[?&](?:code|kod|recept|prescription)=([^&#]+)", RegexOption.IGNORE_CASE)
    private val ISSUED_DATE = Regex("(?:vystaveno|issued)[:= ]*(\\d{4}-\\d{2}-\\d{2})", RegexOption.IGNORE_CASE)
    private val VALID_UNTIL = Regex("(?:platnost|valid)[:= ]*(\\d{4}-\\d{2}-\\d{2})", RegexOption.IGNORE_CASE)

    const val DEFAULT_VALIDITY_DAYS = 14L

    fun parse(raw: String, now: Instant = Instant.now()): Prescription? {
        val jsonCode = if (raw.trim().startsWith("{")) extractJsonCode(raw) else null
        val code = jsonCode ?: extractCode(raw) ?: return null
        val issued = extractDate(ISSUED_DATE, raw)
        val validityEnd = extractDate(VALID_UNTIL, raw)
        val validUntil = validityEnd ?: issued?.plusDays(DEFAULT_VALIDITY_DAYS)

        val zonedIssued = issued?.atStartOfDay(ZoneId.systemDefault())?.toInstant()
        val zonedValid = validUntil?.atTime(23, 59, 59)?.atZone(ZoneId.systemDefault())?.toInstant()

        return Prescription(
            code = code,
            issuedAt = zonedIssued,
            validUntil = zonedValid ?: zonedIssued?.plusSeconds(DEFAULT_VALIDITY_DAYS * 86_400),
            medications = parseItems(raw),
        )
    }

    fun extractCode(raw: String): String? {
        val trimmed = raw.trim()
        URL_CODE_PARAM.find(trimmed)?.let { match ->
            val decoded = runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrDefault(match.groupValues[1])
            normalizeCode(decoded)?.let { return it }
        }
        if (trimmed.length == 12 && trimmed.all(Char::isLetterOrDigit)) return trimmed.uppercase()
        return CODE_REGEX.findAll(trimmed)
            .map { it.groupValues[1] }
            .firstOrNull(::looksLikeCode)
            ?.uppercase()
    }

    private fun normalizeCode(candidate: String): String? {
        val cleaned = candidate.filter(Char::isLetterOrDigit)
        return if (cleaned.length in 8..16) cleaned.uppercase() else null
    }

    private fun looksLikeCode(candidate: String): Boolean {
        if (candidate.length != 12) return false
        if (candidate.all(Char::isLetterOrDigit).not()) return false
        val hasLetter = candidate.any(Char::isLetter)
        val hasDigit = candidate.any(Char::isDigit)
        return hasLetter || hasDigit
    }

    fun isExpired(prescription: Prescription, now: Instant = Instant.now()): Boolean =
        prescription.validUntil?.let { it.isBefore(now) } ?: false

    fun daysRemaining(prescription: Prescription, now: Instant = Instant.now()): Long? =
        prescription.validUntil?.let { ((it.toEpochMilli() - now.toEpochMilli()) / 86_400_000L).coerceAtLeast(0) }

    /**
     * Structured payloads (Czech eRecept apps emit JSON) are parsed when present;
     * otherwise a plain-text medication list of "Name 2x" lines is accepted.
     */
    internal fun parseItems(raw: String): List<PrescriptionItem> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            runCatching {
                val root = Json.parseToJsonElement(trimmed).jsonObject
                val items = root["items"] ?: root["medications"] ?: root["leky"]
                items?.jsonArray?.mapNotNull { element ->
                    element as? JsonObject ?: return@mapNotNull null
                    PrescriptionItem(
                        suklCode = element["suklCode"]?.jsonPrimitive?.contentOrNull,
                        name = element["name"]?.jsonPrimitive?.contentOrNull
                            ?: element["nazev"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null,
                        quantity = (element["quantity"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                        dosageInstruction = element["dosage"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        }

        val lineItems = trimmed.lines()
            .map(String::trim)
            .filter { it.length > 2 && !it.startsWith("http", ignoreCase = true) }
            .mapNotNull { line ->
                val quantityMatch = Regex("(\\d+)\\s*(?:x|ks|tbl|bal)", RegexOption.IGNORE_CASE).find(line)
                val name = line.substringBefore(quantityMatch?.value.orEmpty()).trim().trimEnd(',', ';', '-')
                if (name.isBlank()) return@mapNotNull null
                PrescriptionItem(
                    suklCode = Regex("\\b\\d{7}\\b").find(line)?.value,
                    name = name,
                    quantity = quantityMatch?.groupValues?.get(1)?.toIntOrNull(),
                    dosageInstruction = null,
                )
            }
        return lineItems
    }

    private fun extractJsonCode(raw: String): String? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        sequenceOf("code", "kod", "recept", "prescriptionCode")
            .mapNotNull { key -> (root[key] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull()
            ?.filter(Char::isLetterOrDigit)
            ?.uppercase()
            ?.takeIf { it.length >= 8 }
    }.getOrNull()

    private fun extractDate(regex: Regex, raw: String): LocalDate? =
        regex.find(raw)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
