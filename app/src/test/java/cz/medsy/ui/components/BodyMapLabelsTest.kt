package cz.medsy.ui.components

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import cz.medsy.ui.message.defaultUnitRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two places where a label used to be an English literal: the site enum and the
 * pre-filled dose unit. Both are plain JVM checks, so they run without Robolectric.
 */
class BodyMapLabelsTest {

    @Test
    fun `every site has its own key`() {
        val keys = ApplicationSite.entries.map { it.labelKey }
        assertEquals(ApplicationSite.entries.size, keys.toSet().size)
        assertTrue(keys.all { it.name.startsWith("SITE_") })
    }

    @Test
    fun `the site enum carries no sentence of its own`() {
        val source = File("src/main/java/cz/medsy/domain/model/Enums.kt").readText()
        val enumBody = source.substringAfter("enum class ApplicationSite")
            .substringBefore("val group: SiteGroup")
        // A capitalised English phrase in the enum body would be invisible to the resource scan.
        val literal = Regex(""""[A-Z][a-z]+ [a-z]""")
        assertTrue(
            "the site labels must come from resources",
            !literal.containsMatchIn(enumBody),
        )
    }

    @Test
    fun `an age of one day is a plural, not a sentence`() {
        assertEquals(MessageKey.BODYMAP_AGE_TODAY, describeAge(0).key)
        assertEquals(MessageKey.BODYMAP_AGE_DAYS, describeAge(1).key)
        assertEquals(1, describeAge(1).count)
        assertEquals(21, describeAge(21).count)
        // Czech needs the plural rule for 5 days ("před 5 dny"), so the count must survive.
        assertEquals(MessageKey.BODYMAP_AGE_DAYS, describeAge(5).key)
    }

    @Test
    fun `each dosage form pre-fills its own unit`() {
        assertEquals(defaultUnitRes(MedicationForm.TABLET), defaultUnitRes(MedicationForm.SUBLINGUAL))
        assertEquals(defaultUnitRes(MedicationForm.INJECTION_IM), defaultUnitRes(MedicationForm.INJECTION_SUBQ))
        val distinct = MedicationForm.entries.map { defaultUnitRes(it) }.toSet()
        // Six words for nine forms: tablet/sublingual, both injectables and gel/spray share one.
        assertEquals(6, distinct.size)
    }
}
