package cz.medsy.domain.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GtinTest {

    @Test
    fun `pads an ean13 to a gtin14`() {
        assertEquals("08595000000126", Gtin.normalize("8595000000126"))
    }

    /** The two encodings of one product must produce the same candidate list. */
    @Test
    fun `ean13 and gtin14 yield identical variants`() {
        assertEquals(Gtin.variantsOf("8595000000126"), Gtin.variantsOf("08595000000126"))
    }

    @Test
    fun `variants cover every packaging indicator`() {
        val variants = Gtin.packagingVariants("08595000000126")
        assertEquals(9, variants.size)
        assertEquals("08595000000126", variants.first())
        assertEquals("88595000000126", variants.last())
        assertTrue(variants.all { it.length == 14 })
    }

    @Test
    fun `rejects payloads that are not gtins`() {
        assertNull(Gtin.normalize(""))
        assertNull(Gtin.normalize("1234567"))
        assertNull(Gtin.normalize("0859500000012600"))
    }

    @Test
    fun `ignores separators inside a typed code`() {
        assertEquals("08595000000126", Gtin.normalize("0859 5000 0001 26"))
    }
}
