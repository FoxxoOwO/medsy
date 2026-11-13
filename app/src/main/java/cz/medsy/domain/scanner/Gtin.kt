package cz.medsy.domain.scanner

/**
 * GTIN normalisation shared by both lookup paths — the SÚKL dataset and the user's own list.
 *
 * The same product legitimately carries several GTINs: the packaging indicator (first digit,
 * 0 = single unit, 1–8 = multipack) changes per box, and an EAN-13 is the GTIN-14 with a leading
 * zero. Looking up only the exact scanned string therefore missed boxes the user already had.
 */
object Gtin {

    /** 14 digits, or null when the payload is not a GTIN at all. */
    fun normalize(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        if (digits.length !in 8..14) return null
        return digits.padStart(14, '0')
    }

    fun packagingVariants(gtin14: String): List<String> =
        (0..8).map { indicator -> "$indicator${gtin14.drop(1)}" }

    fun variantsOf(raw: String): List<String> =
        normalize(raw)?.let(::packagingVariants).orEmpty()
}
