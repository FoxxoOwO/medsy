package cz.medsy.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things break quietly here: a switch position that does not round-trip (the chip jumps back to
 * "System" after a restart) and a locale tag that Android does not know about (the choice is
 * accepted in-app but the system settings entry stays empty). Both are checked from the outside.
 */
class AppLanguageTest {

    private val projectDir = listOf(File("."), File("app")).first { File(it, "src/main/AndroidManifest.xml").exists() }

    private val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()
    private val localeConfig = File(projectDir, "src/main/res/xml/locales_config.xml").readText()

    @Test
    fun `the empty list means the device locale wins`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.selectionOf(""))
    }

    @Test
    fun `a region tag maps back to its language`() {
        assertEquals(AppLanguage.CZECH, AppLanguage.selectionOf("cs-CZ"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.selectionOf("en-GB"))
    }

    @Test
    fun `a bare language tag maps too`() {
        assertEquals(AppLanguage.CZECH, AppLanguage.selectionOf("cs"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.selectionOf("en"))
    }

    @Test
    fun `an unsupported language is not presented as a choice`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.selectionOf("de-DE"))
    }

    @Test
    fun `system carries no tag`() {
        assertEquals(null, AppLanguage.SYSTEM.languageTag)
    }

    @Test
    fun `every offered language is declared to android`() {
        val declared = Regex("""<locale android:name="([^"]+)"""")
            .findAll(localeConfig)
            .map { it.groupValues[1] }
            .toSet()

        AppLanguage.entries.mapNotNull { it.languageTag }.forEach { tag ->
            assertTrue("$tag is not in res/xml/locales_config.xml", tag in declared)
        }
    }

    @Test
    fun `the manifest points at the locale config`() {
        assertTrue(manifest.contains("""android:localeConfig="@xml/locales_config""""))
    }

    @Test
    fun `the choice survives a cold start on android 12 and older`() {
        assertTrue(manifest.contains("AppLocalesMetadataHolderService"))
        assertTrue(manifest.contains("""android:name="autoStoreLocales""""))
    }
}
