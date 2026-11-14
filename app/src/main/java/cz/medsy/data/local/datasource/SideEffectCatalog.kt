package cz.medsy.data.local.datasource

import android.content.Context
import android.util.Log
import cz.medsy.domain.model.SideEffect
import cz.medsy.domain.model.SideEffectFrequency
import cz.medsy.domain.model.SideEffectProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Common undesirable effects, looked up by ATC code, from an asset.
 *
 * Why an asset and not a SÚKL download: the open data publishes the leaflet as a PDF (`PIL…pdf`)
 * and nothing structured, so the only way to show side effects offline is to condense them once
 * and ship them. That also makes the card instantly available — the medication list opens on a
 * train with no signal, and a lookup that needed the network would be empty exactly then.
 *
 * The lookup walks from the exact substance to its pharmacological group (`G03CA03` → `G03CA` →
 * `G03`), because the app sees plenty of codes the asset does not name — a foreign product, a
 * compound, or simply a substance outside the curated set. A group row is still worth showing, and
 * the caller can tell the two apart through [SideEffectProfile.isGroupLevel] instead of pretending
 * the text describes the exact product.
 */
@Singleton
class SideEffectCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val entries: List<Entry> by lazy { load() }

    private var error: String? = null

    /** Non-null when the asset failed to load; triggers the lazy read first. */
    val loadError: String?
        get() {
            entries
            return error
        }

    fun size(): Int = entries.size

    /**
     * The effects for [atcCode], or null when neither the substance nor any of its groups is known.
     *
     * Runs on the IO dispatcher because the first call parses the asset; every later call is a
     * handful of string comparisons.
     */
    suspend fun profileFor(atcCode: String?): SideEffectProfile? = withContext(Dispatchers.IO) {
        val needle = normalise(atcCode) ?: return@withContext null
        val match = entries.firstOrNull { it.atc == needle }
            ?: GROUP_PREFIXES.firstNotNullOfOrNull { length ->
                if (needle.length <= length) return@firstNotNullOfOrNull null
                val prefix = needle.take(length)
                entries.firstOrNull { it.atc == prefix }
            }
            ?: return@withContext null
        match.toProfile()
    }

    private fun load(): List<Entry> = try {
        val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString<File>(raw).entries
    } catch (e: Exception) {
        error = e.message ?: e::class.java.simpleName
        Log.w(TAG, "side effects could not be loaded", e)
        emptyList()
    }

    @Serializable
    private data class File(val entries: List<Entry> = emptyList())

    @Serializable
    internal data class Entry(
        val atc: String,
        val substance: String,
        val effects: List<Effect> = emptyList(),
    ) {
        fun toProfile(): SideEffectProfile = SideEffectProfile(
            atcCode = atc,
            substance = substance,
            effects = effects.map { SideEffect(it.parsed, it.text) },
        )
    }

    @Serializable
    internal data class Effect(
        /** Serialised as the enum name; an unknown band is a data error, not a crash. */
        val frequency: String,
        val text: String,
    ) {
        val parsed: SideEffectFrequency
            get() = SideEffectFrequency.values()
                .firstOrNull { it.name == frequency.trim().uppercase() }
                ?: SideEffectFrequency.UNCOMMON
    }

    private companion object {
        const val TAG = "SideEffectCatalog"
        const val ASSET = "side_effects.json"

        /**
         * ATC group codes are three (`G03`), four (`M01A`) or five (`G03CA`) characters long; a
         * substance is seven. Longest first, so the narrowest group wins.
         */
        val GROUP_PREFIXES = listOf(5, 4, 3)

        /** Defensive: the dataset has come through with padded and lowercased codes before. */
        fun normalise(code: String?): String? = code
            ?.trim()
            ?.uppercase()
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.length >= 3 }
    }
}
