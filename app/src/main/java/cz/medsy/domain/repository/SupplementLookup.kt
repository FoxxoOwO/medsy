package cz.medsy.domain.repository

import cz.medsy.domain.model.ParsedBarcode
import cz.medsy.domain.model.ResolvedMedicationCandidate

/**
 * Port for stage 3 of the lookup waterfall.
 *
 * Modelled as an interface rather than a concrete remote client so the waterfall can be tested
 * without the network, and so the implementation stays swappable — a second provider or a
 * cached region would not touch the resolver.
 */
interface SupplementLookup {

    /** Null when the barcode is unknown. Network failures surface as exceptions. */
    suspend fun lookup(parsed: ParsedBarcode): ResolvedMedicationCandidate?
}
