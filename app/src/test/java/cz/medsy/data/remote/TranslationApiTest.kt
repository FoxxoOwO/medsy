package cz.medsy.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MyMemory contract, tested without a socket.
 *
 * The free endpoint does not fail with HTTP errors when it is out of quota — it answers `200` with
 * a warning inside `translatedText`, which would otherwise end up on the screen as if it were the
 * translation. [TranslationApi.parse] therefore has to recognise that payload, and these tests pin
 * the shapes down.
 */
class TranslationApiTest {

    @Test
    fun `a normal answer yields the translated text`() {
        val body = """
            {"responseData":{"translatedText":"Estradiol enanthate","match":1},
             "quotaFinished":false,"responseStatus":200,"responseDetails":""}
        """.trimIndent()
        assertEquals("Estradiol enanthate", TranslationApi.parse(body))
    }

    @Test
    fun `the quota-exhausted prose is not mistaken for a translation`() {
        val body = """
            {"responseData":{"translatedText":"MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY"},
             "responseStatus":200}
        """.trimIndent()
        assertNull(TranslationApi.parse(body))
    }

    @Test
    fun `an error response status is a failure`() {
        val body = """{"responseData":"PLEASE SELECT TWO DISTINCT LANGUAGES","responseStatus":403}"""
        assertNull(TranslationApi.parse(body))
    }

    @Test
    fun `a body that is not the documented shape is a failure`() {
        assertNull(TranslationApi.parse("<html>502 Bad Gateway</html>"))
        assertNull(TranslationApi.parse(""))
        assertNull(TranslationApi.parse("""{"responseData":{"translatedText":"   "}}"""))
    }

    @Test
    fun `short text is one request`() {
        assertEquals(listOf("Estradiol valerate"), TranslationApi.chunks("Estradiol valerate"))
    }

    @Test
    fun `long text is split into requests that stay under the limit`() {
        val text = (1..80).joinToString(" ") { "slovo$it" }
        val chunks = TranslationApi.chunks(text, maxChars = 120)
        assertTrue("expected several chunks, got ${chunks.size}", chunks.size > 1)
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 120) }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `the split prefers a sentence end over cutting mid-word`() {
        val text = "První věta je krátká. Druhá věta je o dost delší a pokračuje dál a dál."
        val chunks = TranslationApi.chunks(text, maxChars = 40)
        assertTrue("first chunk should end a sentence: ${chunks.first()}", chunks.first().endsWith("."))
        assertTrue(chunks.all { it.length <= 40 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `a single very long word is still sent, not dropped`() {
        val word = "x".repeat(90)
        val chunks = TranslationApi.chunks(word, maxChars = 40)
        assertEquals(word, chunks.joinToString(""))
    }
}
