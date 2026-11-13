package cz.medsy.domain.model

import java.math.BigDecimal
import java.time.LocalDate

/** Which layer of the waterfall produced a candidate. */
enum class DataSourceOrigin {
    /** Czech SÚKL register, already synced into the local database. */
    SUKL_CZ,

    /** Curated HRT/compounding catalog shipped as a JSON asset. */
    HRT_CURATED_CATALOG,

    /** Open Food Facts product lookup (OTC supplements and vitamins). */
    OPEN_FOOD_FACTS,

    /** Nothing matched: the user fills the box in and links it to a WHO ATC code. */
    MANUAL_ATC_FALLBACK,
}

/** Divalent cations that bind levothyroxine and tetracyclines in the gut. */
enum class MineralTag { CALCIUM, MAGNESIUM, IRON, ZINC }

/**
 * One row of the lookup result: everything needed to save a medication, plus the clinical
 * metadata that makes later checks (lab interference, absorption offsets, PK curves) work
 * without a second round trip.
 *
 * Scanned metadata ([scannedBatchNumber], [scannedExpirationDate]) is merged in by the
 * resolver so the caller does not have to zip the barcode back onto the result.
 */
data class ResolvedMedicationCandidate(
    val origin: DataSourceOrigin,
    val name: String,
    val suklCode: String? = null,
    val gtin14: String? = null,
    val ean13: String? = null,
    val subtitleOrSupplement: String? = null,
    val activeSubstance: String? = null,
    val atcCode: String? = null,
    val form: MedicationForm = MedicationForm.TABLET,
    val strengthValue: BigDecimal? = null,
    val strengthUnit: String? = null,
    val concentrationMgPerMl: BigDecimal? = null,
    val packageSize: BigDecimal? = null,
    val packageUnit: String? = null,
    // Pre-filled from a GS1 2D DataMatrix scan:
    val scannedBatchNumber: String? = null,
    val scannedExpirationDate: LocalDate? = null,
    // Clinical & HRT metadata:
    /** Elimination half-life in hours; drives the trough-estimate and lab-timing hints. */
    val halfLifeHours: Double? = null,
    val requiresSiteRotation: Boolean = false,
    /** Biotin skews streptavidin-biotin immunoassays for up to ~72 h. */
    val hasBiotinInterference: Boolean = false,
    val divalentCationTags: Set<MineralTag> = emptySet(),
    /** Injectable oil depot: the dead space of the syringe must be added to the dose. */
    val defaultDeadSpaceMl: BigDecimal? = null,
    val pilPdfUrl: String? = null,
    val spcPdfUrl: String? = null,
    /** Set by [DataSourceOrigin.MANUAL_ATC_FALLBACK]: the payload carried no usable name. */
    val needsManualCompletion: Boolean = false,
) {
    /** Human-readable origin, for the "matched from …" label on the scan screen. */
    val isHrtLikely: Boolean
        get() = atcCode?.startsWith("G03") == true || atcCode?.startsWith("G02") == true
}
