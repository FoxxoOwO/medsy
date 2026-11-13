package cz.medsy.domain.util

import java.math.BigDecimal

/**
 * The strength field is free text ("2 mg", "50 mg/mL", "1,5 mg"), because that is what a box says
 * and what a user types. Both the scanner and the medication editor read it, so the parsing lives
 * here — two copies used to drift apart, and "1,5" was silently stored as 1 by the copy that only
 * matched digits.
 */
object MedicationStrength {

    private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")
    /** A unit token: "mg", "µg", "IU", and the compound "mg/mL" a concentration box prints. */
    private val UNIT = Regex("[a-zA-Zµ]+(?:/[a-zA-Z]+)?")

    /** First number in the text, decimal comma accepted; null when there is none. */
    fun valueOf(text: String?): BigDecimal? =
        text?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toBigDecimalOrNull() }

    /** First unit token in the text ("mg", "µg", "IU"); null when the text has none. */
    fun unitOf(text: String?): String? = text?.let { UNIT.find(it)?.value }

    /** "2" + "mg" → "2 mg"; a missing unit shows the bare number, a missing value nothing. */
    fun format(value: BigDecimal?, unit: String?): String {
        val amount = value ?: return ""
        val plain = amount.stripTrailingZeros().toPlainString()
        return if (unit.isNullOrBlank()) plain else "$plain $unit"
    }

    /**
     * G03 = sex hormones (estrogens, progestogens, androgens), G02 = other gynaecological hormones.
     * H02 is *corticosteroids*, which are not HRT — treating them as such skewed the trough
     * analysis for every prednisone prescription.
     */
    fun isHrtAtc(atc: String?): Boolean {
        val code = atc.orEmpty()
        return code.startsWith("G03") || code.startsWith("G02")
    }
}
