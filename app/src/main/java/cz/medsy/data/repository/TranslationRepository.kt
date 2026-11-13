package cz.medsy.data.repository

import cz.medsy.data.local.dao.TranslationDao
import cz.medsy.data.local.entity.MedicationTranslationEntity
import cz.medsy.data.remote.TranslationService
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationTranslation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cached translations of a medication's text.
 *
 * The database row is the product: a translation is fetched at most once per language and per
 * version of the text, then read from disk. That keeps the feature inside the app's offline
 * promise and stops a user browsing their list from burning the free translation quota.
 */
interface TranslationRepository {

    fun observeFor(medicationId: Long): Flow<List<MedicationTranslation>>

    /**
     * Returns the cached translation when it still matches the medication's current text, and
     * otherwise asks the translation service. [force] re-translates even when the cache is fresh.
     */
    suspend fun translate(
        medication: Medication,
        language: String,
        force: Boolean = false,
    ): Result<MedicationTranslation>

    suspend fun remove(medicationId: Long, language: String)
}

@Singleton
class TranslationRepositoryImpl @Inject constructor(
    private val dao: TranslationDao,
    private val api: TranslationService,
) : TranslationRepository {

    override fun observeFor(medicationId: Long): Flow<List<MedicationTranslation>> =
        dao.observeFor(medicationId).map { rows -> rows.map(MedicationTranslationEntity::toModel) }

    override suspend fun translate(
        medication: Medication,
        language: String,
        force: Boolean,
    ): Result<MedicationTranslation> {
        val hash = sourceHash(medication)
        if (!force) {
            val cached = dao.find(medication.id, language)
            if (cached != null && cached.sourceHash == hash) {
                return Result.success(cached.toModel())
            }
        }

        val name = api.translate(medication.name, language)
            ?: return Result.failure(IOException("name could not be translated"))
        // Secondary fields are optional: a substance name that the service cannot handle must
        // not lose the name translation the user is waiting for.
        val substance = medication.activeSubstance?.let { api.translate(it, language) }
        val notes = medication.notes?.takeIf { it.isNotBlank() }?.let { api.translate(it, language) }

        val row = MedicationTranslationEntity(
            medicationId = medication.id,
            language = language,
            sourceHash = hash,
            name = name,
            activeSubstance = substance,
            notes = notes,
            engine = ENGINE,
        )
        dao.upsert(row)
        return Result.success(row.toModel())
    }

    override suspend fun remove(medicationId: Long, language: String) {
        dao.delete(medicationId, language)
    }

    companion object {
        const val ENGINE = "mymemory"

        /**
         * Fingerprint of exactly the fields that get translated. Editing the name, the substance
         * or the notes therefore invalidates the cache; changing strength or form does not, because
         * numbers and form names are not translated.
         */
        fun sourceHash(medication: Medication): String {
            val payload = listOf(
                medication.name,
                medication.activeSubstance.orEmpty(),
                medication.notes.orEmpty(),
            ).joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun MedicationTranslationEntity.toModel(): MedicationTranslation = MedicationTranslation(
    medicationId = medicationId,
    language = language,
    name = name,
    activeSubstance = activeSubstance,
    notes = notes,
    engine = engine,
    translatedAt = translatedAt,
)
