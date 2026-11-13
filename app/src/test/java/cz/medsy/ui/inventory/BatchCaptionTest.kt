package cz.medsy.ui.inventory

import cz.medsy.domain.model.InventoryBatch
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caption decides which of the three strings the shelf card uses — expiry + lot, expiry only,
 * or lot only — so it has to say "nothing to show" rather than print an empty line.
 */
class BatchCaptionTest {

    private fun batch(lot: String? = null, expires: LocalDate? = null) = InventoryBatch(
        medicationId = 1L,
        batchNumber = lot,
        expirationDate = expires,
        initialQuantity = BigDecimal.ONE,
        remainingQuantity = BigDecimal.ONE,
        unit = "tbl",
    )

    @Test
    fun `a lot and an expiry travel together`() {
        val caption = batch(lot = "LOT42", expires = LocalDate.of(2027, 3, 31)).caption()

        assertEquals("LOT42", caption.lot)
        assertEquals(LocalDate.of(2027, 3, 31), caption.expirationDate)
        assertTrue(!caption.isEmpty)
    }

    @Test
    fun `whitespace around a lot number is not part of the label`() {
        assertEquals("LOT42", batch(lot = "  LOT42 ").caption().lot)
    }

    @Test
    fun `a blank lot is treated as no lot`() {
        val caption = batch(lot = "   ", expires = LocalDate.of(2027, 3, 31)).caption()

        assertNull(caption.lot)
        assertEquals(LocalDate.of(2027, 3, 31), caption.expirationDate)
    }

    @Test
    fun `a box with neither lot nor expiry has nothing to caption`() {
        assertTrue(batch().caption().isEmpty)
    }
}
