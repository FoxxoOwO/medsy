package cz.medsy.data.uv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payloads are copied from the live API responses, so a schema change breaks a test. */
class OpenMeteoParsingTest {

    @Test
    fun `current uv index is read from the forecast payload`() {
        val payload = """
            {"latitude":50.0755,"longitude":14.4378,"generationtime_ms":0.04,
             "current":{"time":"2026-06-01T12:00","uv_index":7.35}}
        """.trimIndent()

        assertEquals(7.35, OpenMeteoParser.uvIndex(payload)!!, 0.001)
    }

    @Test
    fun `a null uv index is not a reading`() {
        val payload = """{"current":{"time":"2026-12-01T12:00","uv_index":null}}"""

        assertNull(OpenMeteoParser.uvIndex(payload))
    }

    @Test
    fun `a malformed payload is not a reading`() {
        assertNull(OpenMeteoParser.uvIndex("<html>502 Bad Gateway</html>"))
        assertNull(OpenMeteoParser.uvIndex("""{"current":[]}"""))
    }

    @Test
    fun `the first geocoding result becomes the place`() {
        val payload = """
            {"results":[{"id":3067696,"name":"Prague","latitude":50.08804,
             "longitude":14.42076,"country":"Czechia","admin1":"Prague"}],
             "generationtime_ms":0.5}
        """.trimIndent()

        val place = OpenMeteoParser.place(payload)!!

        assertEquals(50.08804, place.latitude, 0.00001)
        assertEquals(14.42076, place.longitude, 0.00001)
        assertEquals("Prague, Czechia", place.label)
    }

    @Test
    fun `an unknown city has no results`() {
        assertNull(OpenMeteoParser.place("""{"generationtime_ms":0.5}"""))
        assertNull(OpenMeteoParser.place("""{"results":[]}"""))
    }
}
