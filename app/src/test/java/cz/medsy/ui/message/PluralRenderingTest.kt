package cz.medsy.ui.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.medsy.domain.message.messagePlural
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every plural sentence is rendered once with a count, in the language the resources resolve to.
 *
 * This is the check that was missing when `backup_restored` crashed the import: `asText` passed the
 * count as text, `getQuantityString` handed it to a `%1$d` slot and threw
 * `IllegalFormatConversionException`. Nobody saw it, because the plurals were only ever asserted by
 * key, never rendered.
 */
@RunWith(RobolectricTestRunner::class)
class PluralRenderingTest {

    @Test
    fun `every plural renders without a format mismatch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failures = mutableListOf<String>()
        for (key in MessageLocalisationTest.PLURAL_KEYS) {
            for (count in listOf(1, 2, 5, 21)) {
                // Three arguments, because some sentences carry the count twice plus a detail
                // ("eRecept %1$s — %3$d more"). A missing slot throws, which is exactly what we
                // want to hear about; an extra one is ignored by the formatter.
                runCatching { messagePlural(key, count, count, count, count).asText(context) }
                    .onFailure { failures += "$key/$count → ${it::class.simpleName}: ${it.message}" }
            }
        }
        assertTrue("plurals that cannot be rendered:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `a restored count reads as a number`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = messagePlural(
            cz.medsy.domain.message.MessageKey.BACKUP_RESTORED,
            7,
            7,
        ).asText(context)
        assertTrue("the count must appear in the sentence, was '$text'", text.contains("7"))
    }

    @Test
    fun `a plural still gets a text argument as text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = messagePlural(
            cz.medsy.domain.message.MessageKey.SCHEDULE_EVERY_DAYS,
            3,
            3,
        ).asText(context)
        assertEquals(false, text.isBlank())
    }
}
