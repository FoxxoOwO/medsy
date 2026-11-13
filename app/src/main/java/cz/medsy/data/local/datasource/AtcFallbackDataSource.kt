package cz.medsy.data.local.datasource

import android.content.Context
import android.util.Log
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 4 of the lookup waterfall: the offline WHO ATC dictionary.
 *
 * Used when the barcode is unknown everywhere else, and always available in the manual-entry
 * form so a foreign or compounded product can be linked to an ATC code in two taps. That link
 * is what keeps the interaction and absorption checks working for products the app has never
 * seen.
 *
 * The dictionary lives in an asset and is searched in memory. A Room table would be the
 * textbook answer for a *mutable* catalogue, but this data is immutable reference content that
 * only changes when the app is rebuilt; putting it in the patient database would mean a
 * migration for every ATC release and would copy the same rows into a SQLCipher file.
 */
@Singleton
class AtcFallbackDataSource @Inject constructor(
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

    fun byCode(code: String): Entry? {
        val needle = code.trim().uppercase()
        return entries.firstOrNull { it.code == needle }
    }

    /**
     * Ranked search over Latin, Czech and English names plus the code itself. Exact code,
     * exact name and prefix hits outrank a substring hit so "Ibu" lists Ibuprofen first.
     */
    fun suggest(query: String, limit: Int = 10): List<Entry> {
        val needle = fold(query.trim())
        if (needle.length < 2) return emptyList()
        val upper = query.trim().uppercase()
        return entries
            .map { entry -> entry to entry.score(needle, upper) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.code })
            .take(limit)
            .map { (entry, _) -> entry }
    }

    /**
     * The candidate returned when nothing matched: it carries the scanned metadata (batch,
     * expiry, GTIN) so the manual form is pre-filled and the user only types a name.
     */
    fun partial(
        parsed: ParsedBarcode? = null,
        suggestedAtc: Entry? = null,
        nameHint: String? = null,
    ): ResolvedMedicationCandidate {
        val displayName = nameHint?.takeIf { it.isNotBlank() }
            ?: parsed?.gtin14?.let { "GTIN $it" }
            ?: ""
        return ResolvedMedicationCandidate(
            origin = DataSourceOrigin.MANUAL_ATC_FALLBACK,
            name = displayName,
            subtitleOrSupplement = suggestedAtc?.let { "${it.code} · ${it.czech}" },
            activeSubstance = suggestedAtc?.latin,
            atcCode = suggestedAtc?.code,
            form = MedicationForm.TABLET,
            gtin14 = parsed?.gtin14,
            ean13 = parsed?.ean13,
            scannedBatchNumber = parsed?.batchNumber,
            scannedExpirationDate = parsed?.expirationDate,
            needsManualCompletion = true,
        )
    }

    private fun load(): List<Entry> {
        val raw = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { failure ->
            error = "ATC dictionary unreadable: ${failure.message}"
            Log.e(TAG, error!!, failure)
            return emptyList()
        }
        return runCatching { json.decodeFromString<DictionaryFile>(raw).entries }
            .getOrElse { failure ->
                error = "ATC dictionary malformed: ${failure.message}"
                Log.e(TAG, error!!, failure)
                emptyList()
            }
    }

    @Serializable
    private data class DictionaryFile(
        val version: Int = 1,
        val entries: List<Entry> = emptyList(),
    )

    @Serializable
    data class Entry(
        val code: String,
        val latin: String,
        val czech: String,
        val english: String = "",
        /**
         * Lay synonyms the INN does not contain: "zelezo", "vapnik", "cukrovka". Without them a
         * user typing the Czech everyday word finds nothing, because the dictionary stores
         * "Fumarát železnatý" and "Uhličitan vápenatý".
         */
        val keywords: List<String> = emptyList(),
        val note: String? = null,
    ) {
        fun score(needle: String, upperCode: String): Int {
            if (code == upperCode) return 10
            val latinFolded = fold(latin)
            val czechFolded = fold(czech)
            val englishFolded = fold(english)
            val keywordFolded = keywords.map(::fold)
            if (keywordFolded.any { it == needle }) return 9
            if (keywordFolded.any { it.startsWith(needle) }) return 7
            if (keywordFolded.any { it.contains(needle) }) return 5
            return when {
                latinFolded == needle || czechFolded == needle || englishFolded == needle -> 9
                latinFolded.startsWith(needle) || czechFolded.startsWith(needle) ||
                    englishFolded.startsWith(needle) -> 7
                latinFolded.contains(needle) || czechFolded.contains(needle) ||
                    englishFolded.contains(needle) -> 5
                code.startsWith(upperCode) -> 3
                else -> 0
            }
        }
    }

    private companion object {
        const val TAG = "AtcFallback"
        const val ASSET = "atc_dictionary.json"

        /** Shared folding so "zel" finds "Železo" and "bicalu" finds "Bikalutamid". */
        fun fold(value: String): String = buildString(value.length) {
            value.lowercase().forEach { c ->
                append(
                    when (c) {
                        'á', 'à', 'ä', 'â' -> 'a'
                        'č', 'ć' -> 'c'
                        'ď' -> 'd'
                        'é', 'ě', 'è', 'ë' -> 'e'
                        'í', 'ì', 'î', 'ï' -> 'i'
                        'ň' -> 'n'
                        'ó', 'ò', 'ô', 'ö' -> 'o'
                        'ř' -> 'r'
                        'š' -> 's'
                        'ť' -> 't'
                        'ú', 'ů', 'ù', 'û', 'ü' -> 'u'
                        'ý', 'ÿ' -> 'y'
                        'ž' -> 'z'
                        else -> c
                    },
                )
            }
        }
    }
}
