package cz.medsy.domain.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror what Czech pharmacy scanners actually return: linear EAN-13 from the box and
 * the EU FMD DataMatrix payload (AIs 01/17/10/21, FNC1-separated, often with a lot number that
 * is longer than the 20 characters GS1 allows).
 */
class Gs1BarcodeParserTest {

    @Test
    fun `plain ean13 is normalised to gtin14`() {
        val parsed = Gs1BarcodeParser.parse("8595000000124")!!

        assertEquals("08595000000124", parsed.gtin14)
        assertEquals("8595000000124", parsed.ean13)
        assertNull(parsed.expirationDate)
        assertNull(parsed.batchNumber)
    }

    @Test
    fun `gtin14 keeps its packaging indicator and has no ean13 twin`() {
        val parsed = Gs1BarcodeParser.parse("18595000000124")!!

        assertEquals("18595000000124", parsed.gtin14)
        // Indicator 1 means a multipack: there is no EAN-13 for it, and pretending otherwise
        // would make a 3-pack match the single box.
        assertNull(parsed.ean13)
    }

    @Test
    fun `czech fmd datamatrix with all four ais`() {
        val raw = "01085950000001241727033110LOT-42AB\u001D21SN0001234"

        val parsed = Gs1BarcodeParser.parse(raw)!!

        assertEquals("08595000000124", parsed.gtin14)
        assertEquals("8595000000124", parsed.ean13)
        assertEquals(LocalDate.of(2027, 3, 31), parsed.expirationDate)
        assertEquals("LOT-42AB", parsed.batchNumber)
        assertEquals("SN0001234", parsed.serialNumber)
    }

    @Test
    fun `bracketed human readable notation parses identically`() {
        val bracketed = Gs1BarcodeParser.parse("(01)08595000000124(17)270331(10)LOT42(21)SN1")!!
        val machine = Gs1BarcodeParser.parse("01085950000001241727033110LOT42\u001D21SN1")!!

        assertEquals(bracketed.gtin14, machine.gtin14)
        assertEquals(bracketed.expirationDate, machine.expirationDate)
        assertEquals(bracketed.batchNumber, machine.batchNumber)
        assertEquals(bracketed.serialNumber, machine.serialNumber)
    }

    @Test
    fun `expiry with day zero means the last day of that month`() {
        // February 2027 has 28 days, February 2028 has 29: FMD prints AA00 when only the month
        // is known, and the medicine is usable through the end of it.
        assertEquals(LocalDate.of(2027, 2, 28), Gs1BarcodeParser.parseExpiration("270200"))
        assertEquals(LocalDate.of(2028, 2, 29), Gs1BarcodeParser.parseExpiration("280200"))
        assertEquals(LocalDate.of(2027, 4, 30), Gs1BarcodeParser.parseExpiration("270400"))
    }

    @Test
    fun `month zero means december`() {
        assertEquals(LocalDate.of(2027, 12, 31), Gs1BarcodeParser.parseExpiration("270000"))
        // Only the day is printed as-is when the month is unknown — still a valid reading.
        assertEquals(LocalDate.of(2027, 12, 12), Gs1BarcodeParser.parseExpiration("270012"))
    }

    @Test
    fun `impossible dates are rejected instead of guessed`() {
        assertNull(Gs1BarcodeParser.parseExpiration("271332"))
        assertNull(Gs1BarcodeParser.parseExpiration("270231"))
        assertNull(Gs1BarcodeParser.parseExpiration("27033"))
    }

    @Test
    fun `variable length ai stops at fnc1 and is capped at twenty characters`() {
        val raw = "010859500000012410B1234567890123456789012345\u001D21123"

        val fields = Gs1BarcodeParser.parseFields(raw)

        assertEquals("B1234567890123456789", fields["10"])
        assertEquals("123", fields["21"])
    }

    @Test
    fun `symbology identifier prefix does not poison ai matching`() {
        val parsed = Gs1BarcodeParser.parse("]d2010859500000012417270331")!!

        assertEquals("08595000000124", parsed.gtin14)
        assertEquals(LocalDate.of(2027, 3, 31), parsed.expirationDate)
    }

    @Test
    fun `thirteen digit gtin inside ai 01 is accepted`() {
        // Some producers print the EAN-13 into (01) even though the AI is defined as 14 digits.
        val parsed = Gs1BarcodeParser.parse("(01)8595000000124")!!

        assertEquals("08595000000124", parsed.gtin14)
    }

    @Test
    fun `text that is not a barcode yields nothing`() {
        assertNull(Gs1BarcodeParser.parse("https://example.com/lek"))
        assertNull(Gs1BarcodeParser.parse("IBUPROFEN 400 MG"))
        assertNull(Gs1BarcodeParser.parse(""))
    }

    @Test
    fun `unknown ai stops parsing rather than reinterpreting the payload`() {
        val fields = Gs1BarcodeParser.parseFields("010859500000012499XYZ10LOT1")

        assertEquals("08595000000124", fields["01"])
        assertNull(fields["10"])
    }

    @Test
    fun `serial number alone still counts as a scan`() {
        val parsed = Gs1BarcodeParser.parse("2112345678")!!

        assertEquals("12345678", parsed.serialNumber)
        assertTrue(parsed.gtin14 == null)
    }
}
