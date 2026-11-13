package cz.medsy.data.local.datasource

import android.content.Context
import android.util.Log
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.scanner.Gtin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 2 of the lookup waterfall: HRT esters, antiandrogens and individually prepared
 * (IPLP) compounding templates that are not commercial Czech boxes and therefore never appear
 * in SÚKL.
 *
 * The catalog is a JSON asset rather than a database table on purpose: it is immutable
 * reference data that changes only when the app is rebuilt, so a migration would buy nothing.
 *
 * Route-specific pharmacokinetics are modelled as **separate entries** (oral vs sublingual vs
 * transdermal estradiol). That is not duplication: oral estradiol has a 14–18 h half-life, the
 * sublingual route peaks and clears in 8–10 h, and a patch has no meaningful terminal half-life
 * at all. Collapsing them into one "estradiol" row would make every trough estimate wrong.
 */
@Singleton
class HrtCuratedCatalogDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parsed once per process: 40 entries, a few dozen KB, read on the first lookup. */
    private val catalog: List<Entry> by lazy { load() }

    /** Non-null when the asset failed to load; forces the lazy read so it is accurate. */
    val loadError: String?
        get() {
            catalog
            return error
        }

    private var error: String? = null

    fun all(): List<Entry> = catalog

    /**
     * Barcode hits are rare here: ester vials and compounded capsules are not GS1-labelled, so
     * the shipped asset carries no GTINs at all. The rule still has to be right for the case that
     * matters — a user who mapped a package once must keep matching even when the pharmacy
     * dispenses a multipack with packaging indicator 1.
     */
    fun findByBarcode(parsed: ParsedBarcode): Entry? {
        val gtin = parsed.gtin14 ?: return null
        return catalog.firstOrNull { entry -> matchesGtin(entry, gtin) }
    }

    /** Internal so the variant rule is testable with injected GTINs. */
    internal fun matchesGtin(entry: Entry, scannedGtin14: String): Boolean {
        val variants = Gtin.variantsOf(scannedGtin14).toSet()
        return entry.gtins.any { known -> Gtin.variantsOf(known).any { it in variants } }
    }

    /**
     * Ranked text search over name, aliases and substance. An exact alias ("EEn", "Nebido")
     * outranks a prefix, which outranks a mid-string hit, so typing a brand name does not put
     * the compounded 40 mg/mL variant first.
     */
    fun search(query: String, limit: Int = 10): List<Entry> {
        val needle = fold(query)
        if (needle.length < 2) return emptyList()
        return catalog
            .map { entry -> entry to entry.score(needle) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (entry, _) -> entry }
    }

    /**
     * PK metadata for a SÚKL product: the register knows the substance and the form but not the
     * half-life, and the curated catalog does. [formHint] comes from the SÚKL form name, so
     * "Estrofem 2 mg tbl" picks the oral entry and "Divigel" the transdermal one.
     */
    fun enrichment(
        atcCode: String?,
        formHint: MedicationForm?,
        substanceHint: String?,
    ): Entry? {
        val candidates = catalog.filter { entry ->
            atcCode != null && entry.atcCode == atcCode
        }
        if (candidates.isEmpty()) return null
        val foldedSubstance = substanceHint?.let(::fold)
        return candidates.firstOrNull { entry ->
            formHint != null && entry.form == formHint.name &&
                (foldedSubstance.isNullOrBlank() || entry.matches(foldedSubstance))
        } ?: candidates.firstOrNull { entry -> formHint != null && entry.form == formHint.name }
            ?: candidates.first()
    }

    private fun load(): List<Entry> {
        val raw = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrElse { failure ->
            error = "HRT catalog asset unreadable: ${failure.message}"
            Log.e(TAG, error!!, failure)
            return emptyList()
        }
        return runCatching { json.decodeFromString<CatalogFile>(raw).entries }
            .getOrElse { failure ->
                error = "HRT catalog malformed: ${failure.message}"
                Log.e(TAG, error!!, failure)
                emptyList()
            }
    }

    @Serializable
    private data class CatalogFile(
        val version: Int = 1,
        val entries: List<Entry> = emptyList(),
    )

    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        @SerialName("subtitle") val subtitle: String? = null,
        val substance: String? = null,
        val atcCode: String? = null,
        val form: String = "TABLET",
        @Serializable(with = BigDecimalSerializer::class) val strengthValue: BigDecimal? = null,
        val strengthUnit: String? = null,
        @Serializable(with = BigDecimalSerializer::class) val concentrationMgPerMl: BigDecimal? = null,
        @Serializable(with = BigDecimalSerializer::class) val defaultDeadSpaceMl: BigDecimal? = null,
        val halfLifeHours: Double? = null,
        val requiresSiteRotation: Boolean = false,
        val compounded: Boolean = false,
        val aliases: List<String> = emptyList(),
        val gtins: List<String> = emptyList(),
        val note: String? = null,
    ) {
        fun toCandidate(
            gtin14: String? = null,
            ean13: String? = null,
            parsed: ParsedBarcode? = null,
        ): ResolvedMedicationCandidate = ResolvedMedicationCandidate(
            origin = DataSourceOrigin.HRT_CURATED_CATALOG,
            name = name,
            subtitleOrSupplement = subtitle,
            activeSubstance = substance,
            atcCode = atcCode,
            form = form.toMedicationForm(),
            strengthValue = strengthValue,
            strengthUnit = strengthUnit,
            concentrationMgPerMl = concentrationMgPerMl,
            gtin14 = gtin14,
            ean13 = ean13,
            scannedBatchNumber = parsed?.batchNumber,
            scannedExpirationDate = parsed?.expirationDate,
            halfLifeHours = halfLifeHours,
            requiresSiteRotation = requiresSiteRotation,
            defaultDeadSpaceMl = defaultDeadSpaceMl,
        )

        fun score(needle: String): Int {
            if (matches(needle)) return MATCH_SUBSTANCE
            val foldedName = fold(name)
            if (foldedName == needle) return MATCH_NAME_EXACT
            if (aliases.any { fold(it) == needle }) return MATCH_ALIAS_EXACT
            if (foldedName.startsWith(needle)) return MATCH_NAME_PREFIX
            if (aliases.any { fold(it).startsWith(needle) }) return MATCH_ALIAS_PREFIX
            if (foldedName.contains(needle)) return MATCH_NAME_CONTAINS
            if (aliases.any { fold(it).contains(needle) }) return MATCH_ALIAS_CONTAINS
            return 0
        }

        fun matches(foldedNeedle: String): Boolean {
            val foldedName = fold(name)
            val foldedAliases = aliases.map(::fold)
            return foldedName.contains(foldedNeedle) ||
                foldedAliases.any { it.contains(foldedNeedle) } ||
                (substance != null && fold(substance).contains(foldedNeedle))
        }
    }

    private companion object {
        const val TAG = "HrtCatalog"
        const val ASSET = "hrt_curated_catalog.json"

        const val MATCH_SUBSTANCE = 10
        const val MATCH_NAME_EXACT = 9
        const val MATCH_ALIAS_EXACT = 8
        const val MATCH_NAME_PREFIX = 7
        const val MATCH_ALIAS_PREFIX = 6
        const val MATCH_NAME_CONTAINS = 5
        const val MATCH_ALIAS_CONTAINS = 4

        /** Czech diacritics are folded so "cyproteron" finds "Cyproteroni acetas". */
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

/** Asset strings map to the enum by name; an unknown value falls back instead of crashing a scan. */
internal fun String.toMedicationForm(): MedicationForm =
    runCatching { MedicationForm.valueOf(uppercase()) }.getOrDefault(MedicationForm.TABLET)

/**
 * The asset stores strengths as plain JSON numbers ("2", "0.75"), which is what a human writes
 * in a catalogue. BigDecimal keeps the value exact instead of turning 0.75 into 0.7500000001.
 */
internal object BigDecimalSerializer : KSerializer<BigDecimal> {
    // The asset writes strengths as JSON numbers (5, 0.75), which is what a human types in a
    // catalogue; decoding as a string here would fail at startup with a type mismatch.
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal.valueOf(decoder.decodeDouble()).stripTrailingZeros()

    override fun serialize(encoder: Encoder, value: BigDecimal) =
        encoder.encodeDouble(value.toDouble())
}
