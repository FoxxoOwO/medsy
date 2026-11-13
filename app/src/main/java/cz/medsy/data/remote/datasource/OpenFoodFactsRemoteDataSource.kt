package cz.medsy.data.remote.datasource

import cz.medsy.data.remote.OffProduct
import cz.medsy.data.remote.OpenFoodFactsApi
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.MineralTag
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.repository.SupplementLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 3 of the lookup waterfall: OTC vitamins, minerals and supplements that a pharmacy or
 * drugstore sells with a plain EAN-13 and that SÚKL does not register as medicines.
 *
 * Two derived flags are the whole point of this stage — a supplement that is merely a name in
 * the list is useless to the rest of the app:
 *
 *  - biotin skews every streptavidin-biotin immunoassay (thyroid panel, ferritin, hCG) for
 *    about 72 h, so the bloodwork module can warn before venipuncture;
 *  - calcium, magnesium, iron and zinc chelate levothyroxine and tetracyclines, so the
 *    scheduler offsets them instead of letting the user take both at 8:00.
 */
@Singleton
class OpenFoodFactsRemoteDataSource @Inject constructor(
    private val api: OpenFoodFactsApi,
) : SupplementLookup {

    /** Null when the barcode is unknown to Open Food Facts. Throws on network failure. */
    override suspend fun lookup(parsed: ParsedBarcode): ResolvedMedicationCandidate? =
        withContext(Dispatchers.IO) {
            val code = parsed.ean13 ?: parsed.gtin14 ?: return@withContext null
            val product = api.fetchProduct(code) ?: return@withContext null
            product.toCandidate(parsed)
        }

    internal fun OffProduct.toCandidate(parsed: ParsedBarcode): ResolvedMedicationCandidate {
        val texts = listOf(name, nameCs, ingredients, ingredientsCs, categories)
        val minerals = SupplementDetectors.minerals(texts)
        val biotin = SupplementDetectors.hasBiotin(texts)
        return ResolvedMedicationCandidate(
            origin = DataSourceOrigin.OPEN_FOOD_FACTS,
            name = bestName ?: code,
            subtitleOrSupplement = listOfNotNull(brands, quantity).joinToString(" ").ifBlank { null },
            form = MedicationForm.SUPPLEMENT,
            gtin14 = parsed.gtin14,
            ean13 = parsed.ean13,
            scannedBatchNumber = parsed.batchNumber,
            scannedExpirationDate = parsed.expirationDate,
            hasBiotinInterference = biotin,
            divalentCationTags = minerals,
        )
    }
}

/**
 * Free-text detectors for supplement metadata. Pure functions over the strings the product
 * entry carries, with Czech diacritics folded so "vápník" and "vapnik" behave the same.
 */
object SupplementDetectors {

    /** Biotin's assay interference is quoted as 72 h; shorter washouts miss late doses. */
    const val BIOTIN_WASHOUT_HOURS = 72

    private val BIOTIN = Regex(
        """\b(biotin|d-biotin|vitamin\s*h|vitamin\s*b7|vitamin\s*b\s*7)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val MINERAL_PATTERNS: Map<MineralTag, Regex> = mapOf(
        // Czech packaging rarely says "vápník": it names the salt ("uhličitan vápenatý"),
        // so the pattern matches the adjective stem, not just the noun.
        MineralTag.CALCIUM to Regex("""\b(calcium|calcii|vapen\w*|vapnik)\b"""),
        MineralTag.MAGNESIUM to Regex("""\b(magnesium|magnesii|horec\w*|horcik)\b"""),
        MineralTag.IRON to Regex("""\b(iron|ferrum|ferrous|ferric|zelez\w*)\b"""),
        MineralTag.ZINC to Regex("""\b(zinc|zinci|zincum|zinec\w*|zinek)\b"""),
    )

    fun hasBiotin(texts: Iterable<String?>): Boolean = contains(BIOTIN, texts)

    fun minerals(texts: Iterable<String?>): Set<MineralTag> =
        MINERAL_PATTERNS.filterValues { pattern -> contains(pattern, texts) }.keys

    /** The nutriment keys are slugs ("vitamin-b7", "biotin"), so they need the same scan. */
    fun hasBiotinNutriment(keys: Collection<String>): Boolean =
        keys.any { key ->
            val slug = key.lowercase().replace('-', ' ').replace('_', ' ')
            BIOTIN.containsMatchIn(slug) || slug.trim() == "biotin"
        }

    private fun contains(pattern: Regex, texts: Iterable<String?>): Boolean =
        texts.filterNotNull().any { text -> pattern.containsMatchIn(fold(text)) }

    /** Lowercase + diacritics folded to ASCII, so one pattern covers Czech and English. */
    internal fun fold(value: String): String = buildString(value.length) {
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