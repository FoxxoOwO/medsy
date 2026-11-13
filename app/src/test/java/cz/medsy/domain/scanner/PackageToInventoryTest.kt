package cz.medsy.domain.scanner

import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ScannedPackage
import cz.medsy.domain.model.Symbology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The scan's whole value is the (17) expiry and (10) lot on the box; these must end up
 * in the inventory batch so expiry and beyond-use alerts can fire.
 */
class PackageToInventoryTest {

    private fun scanned(
        expiry: LocalDate? = LocalDate.of(2027, 12, 31),
        batch: String? = "BATCH42",
        quantity: String? = null,
    ) = ScannedPackage(
        gtin = "08595000000126",
        expirationDate = expiry,
        batchNumber = batch,
        serialNumber = null,
        packageQuantity = quantity,
        raw = "",
        symbology = Symbology.GS1_DATAMATRIX,
    )

    @Test
    fun `expiry and lot are written to the batch`() {
        val batch = PackageToInventory.batchFrom(scanned(), medicationId = 7)
        assertNotNull(batch)
        assertEquals(LocalDate.of(2027, 12, 31), batch!!.expirationDate)
        assertEquals("BATCH42", batch.batchNumber)
        assertEquals(7L, batch.medicationId)
    }

    @Test
    fun `quantity typed by the user is used`() {
        val batch = PackageToInventory.batchFrom(scanned(), medicationId = 1, quantity = "84")
        assertEquals(0, batch!!.initialQuantity.compareTo(BigDecimal("84")))
        assertEquals(0, batch.remainingQuantity.compareTo(BigDecimal("84")))
    }

    @Test
    fun `gs1 ai 30 quantity is used when the user types nothing`() {
        val batch = PackageToInventory.batchFrom(scanned(quantity = "30"), medicationId = 1)
        assertEquals(0, batch!!.initialQuantity.compareTo(BigDecimal("30")))
    }

    @Test
    fun `user quantity wins over the printed one`() {
        val batch = PackageToInventory.batchFrom(scanned(quantity = "30"), medicationId = 1, quantity = "10")
        assertEquals(0, batch!!.initialQuantity.compareTo(BigDecimal("10")))
    }

    @Test
    fun `missing quantity falls back to one unit`() {
        val batch = PackageToInventory.batchFrom(scanned(), medicationId = 1)
        assertEquals(0, batch!!.initialQuantity.compareTo(PackageToInventory.DEFAULT_QUANTITY))
    }

    @Test
    fun `a box without expiry or lot creates no batch`() {
        assertNull(PackageToInventory.batchFrom(scanned(expiry = null, batch = null), medicationId = 1))
    }

    @Test
    fun `expiry alone is still worth a batch`() {
        val batch = PackageToInventory.batchFrom(scanned(batch = null), medicationId = 1)
        assertNotNull(batch)
        assertNull(batch!!.batchNumber)
        assertEquals(LocalDate.of(2027, 12, 31), batch.expirationDate)
    }

    @Test
    fun `injectables are counted in millilitres`() {
        assertEquals("mL", PackageToInventory.unitFor("50 mg/mL", MedicationForm.INJECTION_IM))
        assertEquals("mL", PackageToInventory.unitFor(null, MedicationForm.INJECTION_SUBQ))
    }

    @Test
    fun `tablets and capsules get their own units`() {
        assertEquals("tbl", PackageToInventory.unitFor("2 mg", MedicationForm.TABLET))
        assertEquals("cps", PackageToInventory.unitFor("100 cps", MedicationForm.CAPSULE))
    }

    @Test
    fun `patches are not counted in millilitres`() {
        assertEquals("tbl", PackageToInventory.unitFor("100 µg/24h", MedicationForm.PATCH))
    }
}
