package cz.medsy.domain.model

/**
 * A machine translation of a medication's text into one target language.
 *
 * [sourceHash] fingerprints the text that was translated. When the user edits the name or the
 * notes the hash changes and the cached row is no longer served, so a stale translation can never
 * sit next to edited text pretending to belong to it.
 */
data class MedicationTranslation(
    val medicationId: Long,
    val language: String,
    val name: String,
    val activeSubstance: String? = null,
    val notes: String? = null,
    val engine: String = "mymemory",
    val translatedAt: Long = System.currentTimeMillis(),
)
