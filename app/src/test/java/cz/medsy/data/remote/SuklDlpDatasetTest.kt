package cz.medsy.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The DLP endpoint moved: `api/1.0/csv/dlp_lecivepripravky.csv` answers 404 and the dataset is
 * now a date-stamped zip of CSVs. These tests pin the two fragile steps — finding the current
 * archive on the catalog page and reaching the medicines table inside it.
 */
class SuklDlpDatasetTest {

    private val catalog = """
        <div class="files">
          <a href="https://opendata.sukl.gov.cz/soubory/DLP_datove_rozhrani20260701.csv">rozhraní</a>
          <a href="https://opendata.sukl.gov.cz/soubory/SOD20261027/DLP20261027.zip">DLP20261027.zip</a>
        </div>
    """.trimIndent()

    @Test
    fun `finds the dated DLP archive and not the schema csv`() {
        assertEquals(
            "https://opendata.sukl.gov.cz/soubory/SOD20261027/DLP20261027.zip",
            SuklDlpDataset.zipUrl(catalog),
        )
    }

    @Test
    fun `returns null when the catalog lists no archive`() {
        assertNull(SuklDlpDataset.zipUrl("<html><body>Výpadek</body></html>"))
    }

    @Test
    fun `extracts the medicines csv from the archive`() {
        val csv = "KOD_SUKL;NAZEV\n0000009;ACYLCOFFIN\n"
        val archive = zipOf("docs/readme.txt" to "x", "dlp_lecivepripravky.csv" to csv)
        assertEquals(csv, SuklDlpDataset.medicinesCsv(archive))
    }

    /** The archive is Windows-1250; UTF-8 would throw on 0xC9 (É) before the parser ever ran. */
    @Test
    fun `decodes the czech csv as windows-1250`() {
        val csv = "KOD_SUKL;NAZEV\n0000168;HYDROCHLOROTHIAZID LÉČIVA\n"
        val archive = zipOf("dlp_lecivepripravky.csv" to csv, charset = "windows-1250")
        val decoded = SuklDlpDataset.medicinesCsv(archive)
        assertEquals(csv, decoded)
    }

    @Test
    fun `returns null when the medicines entry is absent`() {
        assertNull(SuklDlpDataset.medicinesCsv(zipOf("dlp_atc.csv" to "ATC\n")))
    }

    @Test
    fun `rejects a payload that is not a zip`() {
        assertNull(SuklDlpDataset.medicinesCsv("KOD_SUKL;NAZEV\n".toByteArray()))
    }

    private fun zipOf(
        vararg entries: Pair<String, String>,
        charset: String = "UTF-8",
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(javaCharset(charset)))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun javaCharset(name: String) = java.nio.charset.Charset.forName(name)
}
