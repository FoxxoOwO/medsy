package cz.medsy.ui.components

import cz.medsy.domain.model.MedicationForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every dose form must render something. A `when` without an `else` would catch a new form at
 * compile time, but this pins the intent — and catches the tempting shortcut of pointing two
 * unrelated forms at the same glyph.
 */
class MedicationIconTest {

    @Test
    fun `every form has a glyph`() {
        MedicationForm.entries.forEach { form ->
            val icon = form.icon
            assertTrue("$form has no glyph name", icon.name.isNotBlank())
            assertTrue("$form has an empty path", icon.defaultWidth.value > 0f)
        }
    }

    @Test
    fun `the injections are one family and the oral forms another`() {
        assertEquals(MedicationForm.INJECTION_IM.icon.name, MedicationForm.INJECTION_SUBQ.icon.name)
        assertEquals(MedicationForm.TABLET.icon.name, MedicationForm.SUBLINGUAL.icon.name)
    }

    @Test
    fun `forms that look different in the hand do not share a glyph`() {
        val names = listOf(
            MedicationForm.TABLET,
            MedicationForm.INJECTION_IM,
            MedicationForm.PATCH,
            MedicationForm.GEL,
            MedicationForm.SPRAY,
            MedicationForm.DROPS,
            MedicationForm.SUPPLEMENT,
        ).map { it.icon.name }

        assertEquals("each of these needs its own glyph", names.size, names.distinct().size)
    }

    @Test
    fun `a supplement is a leaf, not a pill`() {
        assertEquals("Rounded.Eco", MedicationForm.SUPPLEMENT.icon.name)
    }
}
