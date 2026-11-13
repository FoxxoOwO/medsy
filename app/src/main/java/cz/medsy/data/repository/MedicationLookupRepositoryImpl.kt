package cz.medsy.data.repository

import cz.medsy.data.local.datasource.AtcFallbackDataSource
import cz.medsy.data.local.datasource.HrtCuratedCatalogDataSource
import cz.medsy.data.local.datasource.SuklCatalogDataSource
import cz.medsy.domain.repository.SupplementLookup
import cz.medsy.domain.model.DataSourceOrigin
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate
import cz.medsy.domain.model.SuklMedication
import cz.medsy.domain.repository.MedicationLookupRepository
import cz.medsy.domain.util.Gs1BarcodeParser
import cz.medsy.domain.util.MedicationFormMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The 4-stage waterfall described by [MedicationLookupRepository].
 *
 * Ordering rationale (each stage only runs because the previous one had no answer):
 *  1. SÚKL — the only source with legal Czech metadata, and it is already in the local database,
 *     so it works offline and costs one query.
 *  2. Curated HRT/IPLP catalogue — compounded preparations and imported esters that SÚKL does
 *     not register, plus the PK metadata (half-life, dead space, site rotation) that makes a
 *     dose schedule meaningful.
 *  3. Open Food Facts — supplements that only exist as a barcode; also the only source that can
 *     say "this contains biotin", which the bloodwork module must know.
 *  4. Manual ATC entry — never fails, keeps interaction checking available for everything else.
 *
 * The barcode's own metadata (batch, expiry, GTIN) is never dropped: it is merged into the
 * winning candidate so the save form is pre-filled no matter which stage won.
 */
@Singleton
class MedicationLookupRepositoryImpl @Inject constructor(
    private val sukl: SuklCatalogDataSource,
    private val hrt: HrtCuratedCatalogDataSource,
    private val supplements: SupplementLookup,
    private val atc: AtcFallbackDataSource,
) : MedicationLookupRepository {

    override suspend fun resolveByBarcode(
        rawBarcode: String,
    ): Result<ResolvedMedicationCandidate> {
        val parsed = Gs1BarcodeParser.parse(rawBarcode)
            ?: return Result.failure(
                IllegalArgumentException("Not a GS1 DataMatrix or EAN-13: $rawBarcode"),
            )

        // Stage 1 — SÚKL.
        sukl.findByBarcode(parsed)?.let { medication ->
            return Result.success(medication.toCandidate(parsed))
        }

        // Stage 2 — curated HRT/IPLP catalogue (compounded and imported products).
        hrt.findByBarcode(parsed)?.let { entry ->
            return Result.success(entry.toCandidate(gtin14 = parsed.gtin14, ean13 = parsed.ean13, parsed = parsed))
        }

        // Stage 3 — Open Food Facts. A network failure is not an error here: it means "we could
        // not ask", which is the same outcome as "unknown" as far as the waterfall is concerned.
        val supplement = withTimeoutOrNull(OFF_TIMEOUT_MS) {
            runCatching { supplements.lookup(parsed) }
                .getOrElse { failure ->
                    if (failure is IOException) null else throw failure
                }
        }
        if (supplement != null) return Result.success(supplement)

        // Stage 4 — manual entry, pre-filled with what the code already told us.
        return Result.success(atc.partial(parsed = parsed))
    }

    override fun searchByQuery(query: String): Flow<List<ResolvedMedicationCandidate>> = flow {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            emit(emptyList())
            return@flow
        }
        val results = buildList {
            sukl.search(trimmed, SUKL_LIMIT).forEach { add(it.toCandidate()) }
            hrt.search(trimmed, HRT_LIMIT).forEach { add(it.toCandidate()) }
            // A code-shaped query ("M01AE01", "G03CA03") is a legitimate way to find a drug even
            // when neither catalogue has it, so the ATC dictionary answers it as well.
            atc.suggest(trimmed, ATC_LIMIT).forEach { add(atc.partial(suggestedAtc = it)) }
        }
        emit(rank(results).take(MAX_RESULTS))
    }.flowOn(Dispatchers.IO)

    override suspend fun suggestAtc(
        query: String,
        limit: Int,
    ): List<AtcFallbackDataSource.Entry> = atc.suggest(query, limit)

    override suspend fun manualCandidate(
        rawBarcode: String?,
        atcCode: String?,
        nameHint: String?,
    ): ResolvedMedicationCandidate {
        val parsed = rawBarcode?.let { Gs1BarcodeParser.parse(it) }
        val entry = atcCode?.let { atc.byCode(it) }
        return atc.partial(parsed = parsed, suggestedAtc = entry, nameHint = nameHint)
    }

    /**
     * SÚKL rows and catalogue rows can describe the same product, and the ATC dictionary adds a
     * third, less specific hit. Deduplicate on the fields a user would call "the same medicine"
     * (name + form) and keep the earlier — that is, more authoritative — stage's row.
     */
    private fun rank(candidates: List<ResolvedMedicationCandidate>): List<ResolvedMedicationCandidate> {
        val seen = HashSet<String>()
        return candidates.filter { candidate ->
            val key = candidate.name.lowercase() + "|" + candidate.form
            seen.add(key)
        }
    }

    private fun SuklMedication.toCandidate(parsed: ParsedBarcode? = null): ResolvedMedicationCandidate {
        val form = MedicationFormMapper.fromSuklForm(formName)
        val strength = parseStrength(strength)
        // Stage 2 doubles as the PK reference table here: a SÚKL row for "Estrofem 2 mg" has no
        // half-life, but the curated catalogue knows oral estradiol is ~16 h.
        val enrichment = hrt.enrichment(atcCode = atcCode, formHint = form, substanceHint = activeSubstance)
        return ResolvedMedicationCandidate(
            origin = DataSourceOrigin.SUKL_CZ,
            suklCode = suklCode.padStart(7, '0'),
            gtin14 = parsed?.gtin14 ?: gtin?.let { ParsedBarcode.gtin14Of(it) },
            ean13 = parsed?.ean13 ?: gtin?.let { ParsedBarcode.gtin14Of(it) }
                ?.let { ParsedBarcode.ean13Of(it) },
            name = name,
            subtitleOrSupplement = formName,
            activeSubstance = activeSubstance,
            atcCode = atcCode,
            form = form,
            strengthValue = strength?.first,
            strengthUnit = strength?.second,
            concentrationMgPerMl = enrichment?.concentrationMgPerMl,
            scannedBatchNumber = parsed?.batchNumber,
            scannedExpirationDate = parsed?.expirationDate,
            halfLifeHours = enrichment?.halfLifeHours,
            requiresSiteRotation = enrichment?.requiresSiteRotation ?: (form == MedicationForm.INJECTION_IM),
            defaultDeadSpaceMl = enrichment?.defaultDeadSpaceMl,
            pilPdfUrl = pilUrl,
            spcPdfUrl = spcUrl,
        )
    }

    /** "2,5 mg" / "250 mg/5 ml" → (2.5, "mg") — the first number and the first unit are enough. */
    private fun parseStrength(value: String?): Pair<BigDecimal, String>? {
        if (value.isNullOrBlank()) return null
        val number = NUMBER.find(value)?.value?.replace(',', '.')?.toBigDecimalOrNull() ?: return null
        val unit = UNIT.find(value)?.value ?: return null
        return number to unit
    }

    private companion object {
        const val SUKL_LIMIT = 15
        const val HRT_LIMIT = 10
        const val ATC_LIMIT = 5
        const val MAX_RESULTS = 25

        /** Long enough for a slow mobile network, short enough that the user is not left waiting. */
        const val OFF_TIMEOUT_MS = 10_000L

        val NUMBER = Regex("""\d+[.,]?\d*""")
        val UNIT = Regex("""(?i)\b(mg|g|µg|mcg|ug|ml|i\.?u\.?|%)\b""")
    }
}
