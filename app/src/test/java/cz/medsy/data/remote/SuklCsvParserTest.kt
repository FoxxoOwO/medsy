package cz.medsy.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The DLP header is ~40 columns wide and the app only reads a handful of them. These tests pin
 * the two properties the rest of the app relies on: the SÚKL code is normalised to seven digits,
 * and a GTIN is only accepted when the dataset actually carries one.
 */
class SuklCsvParserTest {

    private val header =
        "KOD_SUKL;H;NAZEV;SILA;FORMA;BALENI;CESTA;DOPLNEK;OBAL;DRZ;ZEMDRZ;AKT_DRZ;AKT_ZEM;REG;" +
            "V_PLATDO;NEOMEZ;UVADENIDO;IS_;ATC_WHO;RC;SDOV;SDOV_DOD;SDOV_ZEM;REG_PROC;" +
            "DDDAMNT_WHO;DDDUN_WHO;DDDP_WHO;ZDROJ_WHO;LL;VYDEJ;ZAV;DOPING;NARVLA;DODAVKY;EAN;" +
            "BRAILLOVO_PISMO;EXP;EXP_T;NAZEV_REG;MRP_CISLO;PRAVNI_ZAKLAD_RE"

    @Test
    fun `reads the columns the app uses`() {
        val csv = header + "\n" + row(
            "KOD_SUKL" to "0000009",
            "NAZEV" to "ACYLCOFFIN",
            "SILA" to "500 mg",
            "FORMA" to "TBL",
            "ATC_WHO" to "N02BA51",
        )

        val parsed = SuklCsvParser.parse(csv).single()
        assertEquals("0000009", parsed.suklCode)
        assertEquals("ACYLCOFFIN", parsed.name)
        assertEquals("500 mg", parsed.strength)
        assertEquals("TBL", parsed.formName)
        assertEquals("N02BA51", parsed.atcCode)
        assertNull(parsed.gtin)
    }

    /** The released DLP has the EAN column present but blank on every row. */
    @Test
    fun `blank ean cells leave the gtin null`() {
        val csv = "KOD_SUKL;NAZEV;EAN\n0000168;HYDROCHLOROTHIAZID;\"\"\n0000176;IBALGIN;\"\"\n"
        val parsed = SuklCsvParser.parse(csv)
        assertEquals(2, parsed.size)
        parsed.forEach { assertNull(it.gtin) }
    }

    @Test
    fun `pads a thirteen digit ean to gtin fourteen`() {
        val csv = "KOD_SUKL;NAZEV;EAN\n0213070;ESTROFEM;8590000123456\n"
        assertEquals("08590000123456", SuklCsvParser.parse(csv).single().gtin)
    }

    @Test
    fun `keeps a short code seven wide`() {
        val csv = "KOD_SUKL;NAZEV\n12345;TEST\n"
        assertEquals("0012345", SuklCsvParser.parse(csv).single().suklCode)
    }

    /** One full-width row, so the test cannot drift from the 41-column header. */
    private fun row(vararg values: Pair<String, String>): String {
        val supplied = values.toMap()
        return header.split(';').joinToString(";") { supplied[it].orEmpty() }
    }
}
