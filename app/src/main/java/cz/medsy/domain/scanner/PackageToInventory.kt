package cz.medsy.domain.scanner

import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ScannedPackage
import java.math.BigDecimal

/**
 * Turns a scanned EU-FMD box into a stock batch. The (17) expiry and (10) lot printed
 * on the box are the reason to scan at all: they drive expiry and beyond-use alerts.
 *
 * Pure function so the mapping can be tested without a ViewModel or database.
 */
object PackageToInventory {

    /** Default when the box does not print a quantity (GS1 AI 30) and the user typed none. */
    val DEFAULT_QUANTITY: BigDecimal = BigDecimal.ONE

    fun batchFrom(
        scanned: ScannedPackage,
        medicationId: Long,
        quantity: String? = null,
        strength: String? = null,
        form: MedicationForm = MedicationForm.TABLET,
        unit: String? = null,
    ): InventoryBatch? {
        // Nothing worth recording: a GTIN alone does not describe stock.
        if (scanned.expirationDate == null && scanned.batchNumber == null) return null

        val resolved = quantity?.toBigDecimalOrNull()
            ?: scanned.packageQuantity?.toBigDecimalOrNull()
            ?: DEFAULT_QUANTITY

        return InventoryBatch(
            medicationId = medicationId,
            batchNumber = scanned.batchNumber,
            expirationDate = scanned.expirationDate,
            initialQuantity = resolved,
            remainingQuantity = resolved,
            unit = unit ?: unitFor(strength, form),
        )
    }

    fun unitFor(strength: String?, form: MedicationForm): String {
        val value = strength?.lowercase().orEmpty()
        return when {
            form.isInjectable -> "mL"
            value.contains("ml") -> "mL"
            value.contains("tbl") -> "tbl"
            value.contains("cps") -> "cps"
            value.contains("iu") -> "IU"
            value.contains("µg") || value.contains("mcg") -> "tbl"
            else -> "tbl"
        }
    }
}
