package cz.medsy.domain.scanner

import cz.medsy.domain.model.ScannedPackage
import cz.medsy.domain.model.Symbology
import cz.medsy.domain.util.Gs1BarcodeParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Scanner-facing adapter over [Gs1BarcodeParser].
 *
 * The AI state machine lives in one place ([Gs1BarcodeParser]) because both the scan screen
 * (through [ScannedPackage]) and the lookup waterfall (through `ParsedBarcode`) must decode a
 * payload identically — two decoders meant the UI could show one expiry while the resolver
 * stored another.
 */
object Gs1Parser {

    /** Decodes a DataMatrix payload into the inventory-facing package model. */
    fun parse(raw: String): ScannedPackage? =
        Gs1BarcodeParser.parse(raw)?.toScannedPackage(Symbology.GS1_DATAMATRIX)

    fun parseFields(raw: String): Map<String, String> = Gs1BarcodeParser.parseFields(raw)

    fun parseGs1Date(value: String): LocalDate? = Gs1BarcodeParser.parseExpiration(value)

    fun toGtin13(gtin14: String): String? =
        if (gtin14.length != 14) null else gtin14.substring(1)

    fun formatExpiry(value: LocalDate?): String? =
        value?.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun checksumDigitValid(ean13: String): Boolean = Ean13.isValid(ean13)
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
