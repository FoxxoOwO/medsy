package cz.medsy.ui.inventory

import cz.medsy.domain.model.InventoryBatch
import java.time.LocalDate

/**
 * What the shelf card can say about the box that will be opened next.
 *
 * A package filled in by hand — scanned but matched to nothing — has no reliable name; the lot
 * number and the printed expiry are what identify it, so they are shown on the card rather than
 * buried in the batch editor.
 */
data class BatchCaption(
    val expirationDate: LocalDate? = null,
    val lot: String? = null,
) {
    val isEmpty: Boolean get() = expirationDate == null && lot == null
}

fun InventoryBatch.caption(): BatchCaption = BatchCaption(
    expirationDate = expirationDate,
    lot = batchNumber?.trim()?.takeIf { it.isNotEmpty() },
)
