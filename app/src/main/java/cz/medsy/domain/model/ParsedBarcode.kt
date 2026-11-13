package cz.medsy.domain.model

import java.time.LocalDate

/**
 * A scanner payload after GS1/Parsing, independent of the symbology ML Kit reported.
 *
 * [ParsedBarcode] is deliberately smaller than [ScannedPackage]: it is what a *lookup*
 * needs, so the waterfall can be driven without dragging UI-facing fields along.
 * [toScannedPackage] converts back for the existing scanner screen and inventory code.
 */
data class ParsedBarcode(
    /** Exactly the string the scanner produced, for diagnostics and re-parsing. */
    val rawValue: String,
    /** 14 digits, packaging indicator included. Null when the payload carried no (01)/(02). */
    val gtin14: String? = null,
    /** 13 digits without the leading packaging indicator; null when the GTIN is not EAN-prefixed. */
    val ean13: String? = null,
    val expirationDate: LocalDate? = null,
    val batchNumber: String? = null,
    val serialNumber: String? = null,
    /** GS1 AI (30), when the manufacturer printed the unit count on the box. */
    val packageQuantity: String? = null,
) {
    /** True when the payload carried nothing the lookup could work with. */
    val isEmpty: Boolean
        get() = gtin14 == null && batchNumber == null && serialNumber == null && expirationDate == null

    fun toScannedPackage(symbology: Symbology): ScannedPackage = ScannedPackage(
        gtin = gtin14,
        expirationDate = expirationDate,
        batchNumber = batchNumber,
        serialNumber = serialNumber,
        packageQuantity = packageQuantity,
        raw = rawValue,
        symbology = symbology,
    )

    companion object {
        /** GTIN-14 from an EAN-13 by prepending the "single unit" packaging indicator. */
        fun gtin14Of(ean13: String): String? {
            val digits = ean13.filter(Char::isDigit)
            if (digits.length != 13) return null
            return "0$digits"
        }

        /**
         * EAN-13 from a GTIN-14. Only GTINs whose packaging indicator is 0 are EAN-13
         * compatible: for a multipack (indicator 1–8) the remaining 13 digits include an
         * extra check digit position, so dropping the first digit would produce a code
         * that looks valid but belongs to a different product.
         */
        fun ean13Of(gtin14: String): String? {
            val digits = gtin14.filter(Char::isDigit)
            if (digits.length != 14 || digits[0] != '0') return null
            return digits.substring(1)
        }
    }
}
