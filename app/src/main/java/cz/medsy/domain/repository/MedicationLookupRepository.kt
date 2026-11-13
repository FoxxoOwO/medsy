package cz.medsy.domain.repository

import cz.medsy.data.local.datasource.AtcFallbackDataSource
import cz.medsy.domain.model.ResolvedMedicationCandidate
import kotlinx.coroutines.flow.Flow

/**
 * The multi-source medication lookup used by the scanner and by manual entry.
 *
 * The contract is one waterfall with a fixed order, because each stage trades precision for
 * reach: the Czech register first (exact, legal, offline), then the curated HRT/IPLP catalogue
 * (compounded and foreign esters the register never lists), then Open Food Facts (supplements
 * that only exist as an EAN), then manual entry with an offline ATC dictionary.
 */
interface MedicationLookupRepository {

    /**
     * Resolves a raw scanner string (EAN-13 or EU FMD DataMatrix) through the waterfall.
     *
     * The returned candidate always carries the metadata the code itself proved — GTIN, batch,
     * expiry — even when the product is unknown, so manual completion does not retype them.
     */
    suspend fun resolveByBarcode(rawBarcode: String): Result<ResolvedMedicationCandidate>

    /** Czech name, substance, SÚKL code or ATC code → ranked candidates, DB first. */
    fun searchByQuery(query: String): Flow<List<ResolvedMedicationCandidate>>

    /** ATC suggestions for the manual-entry form (offline, Czech/Latin/English names). */
    suspend fun suggestAtc(query: String, limit: Int = 10): List<AtcFallbackDataSource.Entry>

    /**
     * Builds the pre-filled manual candidate for a scan the waterfall could not identify,
     * optionally already linked to an ATC code the user picked.
     */
    suspend fun manualCandidate(
        rawBarcode: String? = null,
        atcCode: String? = null,
        nameHint: String? = null,
    ): ResolvedMedicationCandidate
}
