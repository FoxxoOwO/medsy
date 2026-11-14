package cz.medsy.domain.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class Gs1ParserTest {

    private val fnc1 = "\u001D"

    // Valid GTIN-14 (check digit 6) used by the fixtures below.
    private val gtin14 = "08595000000126"
    private val ai01 = "01$gtin14"

    @Test
    fun `parses gtin expiry and batch from a DataMatrix payload`() {
        val raw = ai01 + "17" + "271231" + "10ABC123"
        val parsed = Gs1Parser.parse(raw)
        assertNotNull(parsed)
        assertEquals(gtin14, parsed!!.gtin)
        assertEquals(LocalDate.of(2027, 12, 31), parsed.expirationDate)
        assertEquals("ABC123", parsed.batchNumber)
    }

    @Test
    fun `parses fnc1 separated variable length fields`() {
        val raw = ai01 + "17" + "271231" + "10BATCH42" + fnc1 + "21SER0001"
        val parsed = Gs1Parser.parse(raw)
        assertNotNull(parsed)
        assertEquals(gtin14, parsed!!.gtin)
        assertEquals("BATCH42", parsed.batchNumber)
        assertEquals("SER0001", parsed.serialNumber)
    }

    @Test
    fun `strips AIM symbology prefix`() {
        val fields = Gs1Parser.parseFields("]d2" + ai01 + "17271231")
        assertEquals(gtin14, fields["01"])
        assertEquals("271231", fields["17"])
    }

    @Test
    fun `day 00 means the last day of the month`() {
        val date = Gs1Parser.parseGs1Date("270200")
        assertEquals(LocalDate.of(2027, 2, 28), date)
    }

    @Test
    fun `leap year day 00 resolves to february 29`() {
        val date = Gs1Parser.parseGs1Date("280200")
        assertEquals(LocalDate.of(2028, 2, 29), date)
    }

    @Test
    fun `month 00 is treated as december`() {
        val date = Gs1Parser.parseGs1Date("270000")
        assertEquals(LocalDate.of(2027, 12, 31), date)
    }

    @Test
    fun `years below 50 are 2000s and above are 1900s`() {
        assertEquals(2030, Gs1Parser.parseGs1Date("300101")!!.year)
        assertEquals(1999, Gs1Parser.parseGs1Date("991231")!!.year)
    }

    @Test
    fun `invalid date returns null`() {
        assertNull(Gs1Parser.parseGs1Date("271340"))
        assertNull(Gs1Parser.parseGs1Date("abc"))
    }

    @Test
    fun `payload without any known AI returns null`() {
        assertNull(Gs1Parser.parse("hello world"))
    }

    @Test
    fun `gtin13 conversion drops the packaging indicator`() {
        assertEquals("8595000000126", Gs1Parser.toGtin13(gtin14))
    }

    @Test
    fun `ean13 check digit validation`() {
        assertTrue(Ean13.isValid("8595000000010"))
        assertFalse(Ean13.isValid("8595000000011"))
        assertFalse(Ean13.isValid("12345"))
    }

    @Test
    fun `ean13 check digit computation is consistent with validation`() {
        val prefix = "859500000000"
        val digit = Ean13.checkDigit(prefix)
        assertTrue(Ean13.isValid("$prefix$digit"))
    }

    @Test
    fun `ean normalization accepts a leading zero gtin14`() {
        assertEquals("8595000000126", Ean13.normalize(gtin14))
    }

    @Test
    fun `ean normalization rejects invalid check digits`() {
        assertNull(Ean13.normalize("8595000000011"))
    }

    @Test
    fun `production date AI 11 is ignored for expiry`() {
        val parsed = Gs1Parser.parse(ai01 + "11260101" + "10LOT1")
        assertNull(parsed!!.expirationDate)
        assertEquals("LOT1", parsed.batchNumber)
    }
}

class EReceptParserTest {

    @Test
    fun `extracts a bare twelve character code`() {
        val prescription = EReceptParser.parse("ABCD1234EFGH")
        assertNotNull(prescription)
        assertEquals("ABCD1234EFGH", prescription!!.code)
    }

    @Test
    fun `extracts the code from a QR url`() {
        val prescription = EReceptParser.parse("https://www.epreskripce.cz/qr?code=ABCD1234EFGH")
        assertEquals("ABCD1234EFGH", prescription!!.code)
    }

    @Test
    fun `defaults validity to fourteen days from issue`() {
        val prescription = EReceptParser.parse("vystaveno:2026-01-01 code=ABCD1234EFGH")
        assertNotNull(prescription)
        val days = java.time.temporal.ChronoUnit.DAYS.between(
            prescription!!.issuedAt!!.atZone(EReceptParser.CZECH_ZONE).toLocalDate(),
            prescription.validUntil!!.atZone(EReceptParser.CZECH_ZONE).toLocalDate(),
        )
        assertEquals(14L, days)
    }

    @Test
    fun `honours an explicit validity date`() {
        val prescription = EReceptParser.parse(
            "vystaveno:2026-01-01 platnost:2026-02-15 code=ABCD1234EFGH",
        )
        val localValid = prescription!!.validUntil!!.atZone(EReceptParser.CZECH_ZONE).toLocalDate()
        assertEquals(LocalDate.of(2026, 2, 15), localValid)
    }

    @Test
    fun `detects expired prescriptions`() {
        val expired = EReceptParser.parse("vystaveno:2020-01-01 code=ABCD1234EFGH")
        assertTrue(EReceptParser.isExpired(expired!!))
    }

    @Test
    fun `parses json payload items`() {
        val json = """{"code":"ABCD1234EFGH","items":[{"suklCode":"0012345","name":"Estradiol","quantity":2}]}"""
        val prescription = EReceptParser.parse(json)
        assertNotNull(prescription)
        assertEquals(1, prescription!!.medications.size)
        assertEquals("0012345", prescription.medications.first().suklCode)
        assertEquals(2, prescription.medications.first().quantity)
    }

    @Test
    fun `parses plain text medication lines`() {
        val text = """
            code=ABCD1234EFGH
            Estradiol 2x
            Progesteron 30 tbl
        """.trimIndent()
        val prescription = EReceptParser.parse(text)
        assertTrue(prescription!!.medications.size >= 2)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(EReceptParser.parse("hello"))
    }

    @Test
    fun `days remaining is never negative`() {
        val expired = EReceptParser.parse("vystaveno:2020-01-01 code=ABCD1234EFGH")
        assertEquals(0L, EReceptParser.daysRemaining(expired!!))
    }

    /** The text PDFBox returns for a printed eRecept handed out by a pharmacy. */
    private val printedPdf = """
        eRecept — Recept
        Kód receptu: 7A2B9C4D1E6F
        Vystaveno: 20. 5. 2026
        Platnost do: 3. 6. 2026
        Pacient: Jan Novák
        Pojišťovna: 111
        Předepsal: MUDr. Eva Pokorná, IČO: 12345678
        Estradiol 2 mg, 84 tbl
        Spironolacton 50 mg 2x
    """.trimIndent()

    @Test
    fun `a printed pdf reads czech dates`() {
        val prescription = EReceptParser.parse(printedPdf)
        assertNotNull(prescription)
        val issued = prescription!!.issuedAt!!.atZone(EReceptParser.CZECH_ZONE).toLocalDate()
        val valid = prescription.validUntil!!.atZone(EReceptParser.CZECH_ZONE).toLocalDate()
        assertEquals(LocalDate.of(2026, 5, 20), issued)
        assertEquals(LocalDate.of(2026, 6, 3), valid)
    }

    @Test
    fun `a labelled code wins over an earlier twelve character token`() {
        val text = """
            Číslo dokladu: AB12CD34EF56
            Kód receptu: 7A2B9C4D1E6F
        """.trimIndent()
        assertEquals("7A2B9C4D1E6F", EReceptParser.parse(text)!!.code)
    }

    @Test
    fun `patient and prescriber lines are not medications`() {
        val names = EReceptParser.parse(printedPdf)!!.medications.map { it.name }
        assertEquals(listOf("Estradiol 2 mg", "Spironolacton 50 mg"), names)
    }

    @Test
    fun `the prescriber ico is not the prescription code`() {
        val text = "Předepsal: MUDr. Eva Pokorná\nIČO: 12345678"
        assertNull(EReceptParser.extractCode(text))
    }
}
