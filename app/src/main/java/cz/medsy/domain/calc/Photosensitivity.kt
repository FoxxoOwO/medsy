package cz.medsy.domain.calc

import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.domain.model.Medication

/**
 * Photosensitivity advice on the dashboard.
 *
 * Matching is on ATC code, not on the trade name: SÚKL rows frequently carry only an ATC
 * code, and brand names are localised and change. The list is deliberately short — only
 * groups with documented photosensitivity in their SPC — because a warning shown for every
 * drug is a warning nobody reads.
 */
data class UvWarning(
    val uvIndex: Double,
    /** Medications that make sun exposure riskier, by display name. */
    val medications: List<String>,
) {
    /**
     * The advice sentence as a localisable message. An empty [medications] list happens when
     * a warning was built from a cached UV reading only; then the generic sentence is used.
     */
    val message: Message
        get() {
            val uv = uvIndex.formatUv()
            return when (medications.size) {
                0 -> message(MessageKey.UV_EXPOSURE, uv)
                1 -> message(MessageKey.UV_EXPOSURE_ONE, uv, medications.single())
                2 -> message(MessageKey.UV_EXPOSURE_TWO, uv, medications[0], medications[1])
                else -> messagePlural(
                    MessageKey.UV_EXPOSURE_MANY,
                    medications.size,
                    uv,
                    medications.size.toString(),
                )
            }
        }

    private fun Double.formatUv(): String = java.lang.String.format(java.util.Locale.ROOT, "%.1f", this)
}

object Photosensitivity {

    /** Above this the warning is worth showing; 6 is "high" on the WHO scale. */
    const val UV_THRESHOLD: Double = 6.0

    /**
     * ATC prefixes with documented photosensitivity. Prefixes, not full codes, so that
     * every member of the group matches (e.g. all tetracyclines under J01A).
     */
    private val ATC_PREFIXES = listOf(
        "J01A", // tetracyclines — doxycycline, lymecycline
        "J01E", // sulfonamides
        "J01M", // fluoroquinolones — ciprofloxacin, ofloxacin
        "D10BA", // isotretinoin
        "D10AD", // topical retinoids — tretinoin, adapalene
        "N05AA", // chlorpromazine
        "N05AC", // thioridazine
        "C01BD", // amiodarone
        "C03A", // thiazides — hydrochlorothiazide
        "M01A", // NSAIDs — ibuprofen, naproxen
    )

    fun isPhotosensitising(atcCode: String?): Boolean {
        val code = atcCode?.trim()?.uppercase()?.replace(" ", "")
        if (code.isNullOrEmpty()) return false
        return ATC_PREFIXES.any { code.startsWith(it) }
    }

    /**
     * Returns null unless both conditions hold: a high enough UV index **and** at least one
     * active photosensitising medication. A null UV index (no city, offline, no cached
     * reading) is not a warning — silence is the correct fallback.
     */
    fun uvWarning(uvIndex: Double?, medications: List<Medication>): UvWarning? {
        val uv = uvIndex ?: return null
        if (uv <= UV_THRESHOLD) return null
        val names = medications.filter { isPhotosensitising(it.atcCode) }
            .map { it.name }
            .distinct()
        if (names.isEmpty()) return null
        return UvWarning(uvIndex = uv, medications = names)
    }
}
