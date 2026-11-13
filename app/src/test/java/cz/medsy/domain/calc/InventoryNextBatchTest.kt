package cz.medsy.domain.calc

import cz.medsy.domain.model.InventoryBatch
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shelf card shows the box that will be opened next. For a package filled in by hand that line
 * is the only place the printed lot number and expiry appear without opening the batch editor, so
 * the choice of batch has to be right.
 */
class InventoryNextBatchTest {

    private val today = LocalDate.of(2026, 11, 13)

    private fun batch(
        lot: String? = null,
        expires: LocalDate? = null,
        quantity: String = "10",
        id: Long = 0,
    ) = InventoryBatch(
        id = id,
        medicationId = 1L,
        batchNumber = lot,
        expirationDate = expires,
        initialQuantity = BigDecimal(quantity),
        remainingQuantity = BigDecimal(quantity),
        unit = "tbl",
    )

    private fun line(vararg batches: InventoryBatch) = InventoryCalculator.buildStockLine(
        medicationId = 1L,
        medicationName = "Test",
        unit = "tbl",
        batches = batches.toList(),
        schedules = emptyList(),
        today = today,
    )

    @Test
    fun `the earliest expiry is the box opened next`() {
        val later = batch(lot = "LOT42", expires = LocalDate.of(2027, 3, 31), id = 1)
        val sooner = batch(lot = "LOT01", expires = LocalDate.of(2027, 1, 15), id = 2)

        val next = line(later, sooner).nextBatch

        assertEquals("LOT01", next?.batchNumber)
        assertEquals(LocalDate.of(2027, 1, 15), next?.expirationDate)
    }

    @Test
    fun `an expired box is never advertised as next`() {
        val expired = batch(lot = "OLD", expires = today.minusDays(1), id = 1)
        val usable = batch(lot = "NEW", expires = LocalDate.of(2027, 3, 31), id = 2)

        val next = line(expired, usable).nextBatch

        assertEquals("NEW", next?.batchNumber)
    }

    @Test
    fun `a dated box wins over an undated one`() {
        val undated = batch(lot = "NODATE", id = 1)
        val dated = batch(lot = "DATED", expires = LocalDate.of(2027, 3, 31), id = 2)

        assertEquals("DATED", line(undated, dated).nextBatch?.batchNumber)
    }

    @Test
    fun `a lot without any expiry still identifies the box`() {
        val next = line(batch(lot = "LOT42", id = 7)).nextBatch

        assertEquals("LOT42", next?.batchNumber)
        assertNull(next?.expirationDate)
    }

    @Test
    fun `stock with neither lot nor expiry has nothing to advertise`() {
        val anonymous = batch(quantity = "5", id = 1)

        assertNull(line(anonymous).nextBatch)
    }

    @Test
    fun `batches of another medication are ignored`() {
        val foreign = batch(lot = "OTHER", expires = LocalDate.of(2026, 12, 1)).copy(medicationId = 2L)
        val own = batch(lot = "MINE", expires = LocalDate.of(2027, 3, 31)).copy(medicationId = 1L)

        assertEquals("MINE", line(foreign, own).nextBatch?.batchNumber)
    }
}
