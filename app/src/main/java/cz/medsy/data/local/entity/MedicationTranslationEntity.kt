package cz.medsy.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One cached translation of a medication's text for one target language.
 *
 * The row is a cache, not a record: it is deleted along with the medication, overwritten when
 * the language is translated again, and thrown away when [sourceHash] no longer matches what the
 * user has typed. Keeping it in the encrypted database (rather than only in memory) means a
 * translation opened once stays readable offline — which is the whole point of the app.
 */
@Entity(
    tableName = "medication_translations",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // One row per language per medication: translating twice replaces instead of piling up.
    indices = [Index(value = ["medicationId", "language"], unique = true)],
)
data class MedicationTranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    /** BCP-47 language subtag of the *target*: `en`, `de`, `sk`. */
    val language: String,
    /** Fingerprint of the text that was translated, so an edit invalidates the cache. */
    val sourceHash: String,
    val name: String,
    val activeSubstance: String? = null,
    val notes: String? = null,
    /** Which service produced this, for honesty in the UI when a fallback engine is used. */
    val engine: String = "mymemory",
    val translatedAt: Long = System.currentTimeMillis(),
)
