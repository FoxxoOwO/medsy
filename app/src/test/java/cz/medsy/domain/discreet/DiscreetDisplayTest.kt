package cz.medsy.domain.discreet

import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Discreet mode is a privacy guarantee: nothing on screen may reveal a diagnosis. These
 * tests pin every field that could leak it, because a regression here is not a visual
 * bug — it is outing someone standing in a queue.
 */
class DiscreetDisplayTest {

    private fun esradiol() = Medication(
        id = 1,
        name = "Estradiol Enanthate",
        activeSubstance = "Estradiolum enanthicum",
        atcCode = "G03CA03",
        form = MedicationForm.INJECTION_IM,
        strengthValue = BigDecimal("5"),
        strengthUnit = "mg",
        isHrt = true,
    )

    private fun paracetamol() = Medication(
        id = 2,
        name = "Paracetamol",
        activeSubstance = "Paracetamolum",
        atcCode = "N02BE01",
        form = MedicationForm.TABLET,
        strengthValue = BigDecimal("500"),
        strengthUnit = "mg",
        isHrt = false,
    )

    // --- visible mode -----------------------------------------------------------

    @Test
    fun `without discreet mode the real name is shown`() {
        val row = DiscreetDisplay.row(esradiol(), discreet = false)
        assertEquals("Estradiol Enanthate", row.name)
        assertFalse(row.nameMasked)
    }

    @Test
    fun `without discreet mode an HRT medication shows its badge`() {
        assertTrue(DiscreetDisplay.row(esradiol(), discreet = false).showHrtBadge)
    }

    @Test
    fun `without discreet mode a non HRT medication shows no badge`() {
        assertFalse(DiscreetDisplay.row(paracetamol(), discreet = false).showHrtBadge)
    }

    @Test
    fun `without discreet mode the substance and ATC are shown`() {
        val detail = DiscreetDisplay.row(esradiol(), discreet = false).detail
        assertTrue(detail, detail.contains("Estradiolum enanthicum"))
        assertTrue(detail, detail.contains("G03CA03"))
    }

    // --- discreet mode: the leaks ----------------------------------------------

    @Test
    fun `discreet mode hides the HRT badge`() {
        assertFalse(DiscreetDisplay.row(esradiol(), discreet = true).showHrtBadge)
    }

    @Test
    fun `discreet mode masks the medication name`() {
        val row = DiscreetDisplay.row(esradiol(), discreet = true)
        assertEquals(DiscreetDisplay.MASKED_NAME, row.name)
        assertTrue(row.nameMasked)
    }

    @Test
    fun `discreet mode hides the active substance`() {
        val detail = DiscreetDisplay.row(esradiol(), discreet = true).detail
        assertFalse("substance would reveal the diagnosis: $detail", detail.contains("Estradiol", ignoreCase = true))
    }

    @Test
    fun `discreet mode hides the ATC code`() {
        val detail = DiscreetDisplay.row(esradiol(), discreet = true).detail
        assertFalse("ATC G03CA is an oestrogen: $detail", detail.contains("G03CA"))
        assertFalse(detail.contains("ATC", ignoreCase = true))
    }

    @Test
    fun `discreet mode never leaks the real name for any medication`() {
        listOf(esradiol(), paracetamol()).forEach { medication ->
            val row = DiscreetDisplay.row(medication, discreet = true)
            assertFalse(row.name.contains(medication.name))
        }
    }

    // --- discreet mode keeps what is needed to pick the box --------------------

    @Test
    fun `discreet mode still shows the strength`() {
        val detail = DiscreetDisplay.row(esradiol(), discreet = true).detail
        assertTrue("strength must stay visible: $detail", detail.contains("5 mg"))
    }

    @Test
    fun `discreet mode leaves an empty detail when there is nothing safe to show`() {
        val bare = Medication(id = 3, name = "Something", form = MedicationForm.TABLET)
        assertEquals("", DiscreetDisplay.row(bare, discreet = true).detail)
    }

    @Test
    fun `masked rows share one label so the list cannot be told apart by name`() {
        val first = DiscreetDisplay.row(esradiol(), discreet = true)
        val second = DiscreetDisplay.row(paracetamol(), discreet = true)
        assertEquals(first.name, second.name)
    }
}
