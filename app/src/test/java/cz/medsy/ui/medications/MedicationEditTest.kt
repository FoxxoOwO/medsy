package cz.medsy.ui.medications

import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Editing a scanned medication must not cost it the barcode that identifies it next time, and the
 * editor's own validation has to agree with what the scanner stores.
 */
class MedicationEditTest {

    private val scanned = Medication(
        id = 7,
        suklCode = "0249259",
        gtin = "08595000000126",
        name = "Estradiol valerate",
        activeSubstance = "Estradiolum valeras",
        atcCode = "G03CA03",
        form = MedicationForm.INJECTION_IM,
        strengthValue = BigDecimal("5"),
        strengthUnit = "mg",
        concentrationMgPerMl = BigDecimal("5"),
        pilUrl = "https://example.test/pil.pdf",
        spcUrl = "https://example.test/spc.pdf",
        isHrt = true,
        isArchived = true,
    )

    @Test
    fun `the editor starts from what is stored`() {
        val edit = MedicationEdit.of(scanned)

        assertEquals("Estradiol valerate", edit.name)
        assertEquals("5 mg", edit.strength)
        assertEquals("G03CA03", edit.atcCode)
        assertEquals(MedicationForm.INJECTION_IM, edit.form)
    }

    @Test
    fun `a blank name cannot be saved`() {
        assertFalse(MedicationEdit.of(scanned).copy(name = "   ").isValid)
        assertTrue(MedicationEdit.of(scanned).isValid)
    }

    @Test
    fun `text without a number blocks the save instead of storing zero`() {
        val edit = MedicationEdit.of(scanned).copy(strength = "mg")

        assertTrue(edit.strengthInvalid)
        assertFalse(edit.isValid)
    }

    @Test
    fun `saving keeps the identity fields the dialog does not show`() {
        val edited = MedicationEdit.of(scanned)
            .copy(name = "Estradiol valerate 2", strength = "1,5 mg", atcCode = "g03ca03")
            .applyTo(scanned)

        assertEquals(7, edited.id)
        assertEquals("08595000000126", edited.gtin)
        assertEquals("0249259", edited.suklCode)
        assertEquals("https://example.test/pil.pdf", edited.pilUrl)
        assertTrue("archiving is not undone by an edit", edited.isArchived)
        assertEquals(BigDecimal("1.5"), edited.strengthValue)
        assertEquals("mg", edited.strengthUnit)
        assertEquals("the ATC code is normalised to upper case", "G03CA03", edited.atcCode)
    }

    @Test
    fun `clearing a field stores null rather than an empty string`() {
        val edited = MedicationEdit.of(scanned)
            .copy(activeSubstance = " ", notes = " ")
            .applyTo(scanned)

        assertNull(edited.activeSubstance)
        assertNull(edited.notes)
    }

    @Test
    fun `changing the ATC code re-derives the HRT flag`() {
        val edited = MedicationEdit.of(scanned).copy(atcCode = "H02AB07").applyTo(scanned)

        assertFalse(edited.isHrt)
    }
}
