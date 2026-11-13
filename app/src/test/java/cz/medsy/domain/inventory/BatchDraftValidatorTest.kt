package cz.medsy.domain.inventory

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A hand-entered batch is the only way stock bought without a box reaches the shelf, so the
 * rules have to be strict about what is *stock* (quantity, unit) and forgiving about what is
 * merely suspicious (an old expiry date) — blocking the latter would push people to type a
 * fake date instead.
 */
class BatchDraftValidatorTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 1)

    private fun draft(
        quantity: String = "10",
        unit: String = "ml",
        expiry: LocalDate? = null,
        threshold: Int = 7,
        deadSpace: String? = null,
        beyondUse: Int? = null,
    ) = BatchDraft(
        medicationId = 1,
        quantity = BigDecimal(quantity),
        unit = unit,
        expirationDate = expiry,
        deadSpaceMlPerDose = deadSpace?.let(::BigDecimal),
        beyondUseDaysLimit = beyondUse,
        lowStockThresholdDays = threshold,
    )

    @Test
    fun `a plain batch is valid and produces no noise`() {
        val result = BatchDraftValidator.validate(draft(), MedicationForm.INJECTION_SUBQ, today)

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `zero quantity is an error, not stock`() {
        val result = BatchDraftValidator.validate(draft(quantity = "0"), MedicationForm.INJECTION_SUBQ, today)

        assertFalse(result.isValid)
        assertEquals(MessageKey.BATCH_QUANTITY_POSITIVE, result.errors.single().key)
    }

    @Test
    fun `negative quantity is an error`() {
        val result = BatchDraftValidator.validate(draft(quantity = "-5"), MedicationForm.INJECTION_SUBQ, today)

        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `an expiry date in the past warns but still saves`() {
        val result = BatchDraftValidator.validate(
            draft(expiry = today.minusDays(30)),
            MedicationForm.INJECTION_SUBQ,
            today,
        )

        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertEquals(MessageKey.BATCH_EXPIRED, result.warnings.single().key)
    }

    @Test
    fun `an expiry date of today warns`() {
        val result = BatchDraftValidator.validate(draft(expiry = today), MedicationForm.INJECTION_SUBQ, today)

        assertTrue(result.isValid)
        assertEquals(MessageKey.BATCH_EXPIRES_TODAY, result.warnings.single().key)
    }

    @Test
    fun `a blank unit is an error`() {
        val result = BatchDraftValidator.validate(draft(unit = "  "), MedicationForm.TABLET, today)

        assertFalse(result.isValid)
        assertEquals(MessageKey.BATCH_UNIT_REQUIRED, result.errors.single().key)
    }

    @Test
    fun `a unit that does not match the form only warns`() {
        val result = BatchDraftValidator.validate(draft(unit = "tbl"), MedicationForm.INJECTION_IM, today)

        assertTrue(result.isValid)
        assertEquals(MessageKey.BATCH_UNUSUAL_UNIT, result.warnings.single().key)
    }

    @Test
    fun `a negative low-stock threshold is an error`() {
        val result = BatchDraftValidator.validate(draft(threshold = -1), MedicationForm.TABLET, today)

        assertFalse(result.isValid)
        assertEquals(MessageKey.BATCH_THRESHOLD_NEGATIVE, result.errors.single().key)
    }

    @Test
    fun `a zero-day beyond-use limit is an error`() {
        val result = BatchDraftValidator.validate(draft(beyondUse = 0), MedicationForm.INJECTION_IM, today)

        assertFalse(result.isValid)
        assertEquals(MessageKey.BATCH_BEYOND_USE_MIN, result.errors.single().key)
    }

    @Test
    fun `negative dead space is an error but dead space on tablets only warns`() {
        val negative = BatchDraftValidator.validate(
            draft(deadSpace = "-0.1"),
            MedicationForm.INJECTION_IM,
            today,
        )
        assertFalse(negative.isValid)

        val onTablet = BatchDraftValidator.validate(
            draft(unit = "tbl", deadSpace = "0.1"),
            MedicationForm.TABLET,
            today,
        )
        assertTrue(onTablet.isValid)
        assertEquals(MessageKey.BATCH_DEAD_SPACE_INJECTABLE_ONLY, onTablet.warnings.single().key)
    }

    @Test
    fun `a draft becomes a batch with quantity as both initial and remaining`() {
        val batch = draft(quantity = "10", expiry = today.plusDays(90)).toBatch()

        assertEquals(0, batch.initialQuantity.compareTo(BigDecimal("10")))
        assertEquals(0, batch.remainingQuantity.compareTo(BigDecimal("10")))
        assertEquals(today.plusDays(90), batch.expirationDate)
        assertEquals("ml", batch.unit)
        assertNull(batch.batchNumber)
        assertNull(batch.firstPuncturedDate)
    }
}
