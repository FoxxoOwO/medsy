package cz.medsy.data.prescription

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.lab.OcrDocument
import cz.medsy.data.lab.OcrRecognizer
import cz.medsy.data.lab.PdfTextExtractor
import cz.medsy.data.prescription.PrescriptionImporter.ImportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The importer is the one place that decides what a picked file was and what the user is told
 * about it. Three of the four outcomes are failures that a screen must distinguish: a scanned
 * PDF needs a photo, text without a code needs the code, an unreadable file needs another file.
 */
@RunWith(RobolectricTestRunner::class)
class PrescriptionImporterTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val file = Uri.parse("content://picked/prescription")

    private val printedPdf = """
        eRecept — Recept
        Kód receptu: 7A2B9C4D1E6F
        Vystaveno: 20. 5. 2026
        Platnost do: 3. 6. 2026
        Pacient: Jan Novák
        Pojišťovna: 111
        Předepsal: MUDr. Eva Pokorná, IČO: 12345678
        Estradiol 2 mg, 84 tbl
    """.trimIndent()

    private class FakePdf(private val result: Result<String>) : PdfTextExtractor {
        override suspend fun extractText(uri: Uri): Result<String> = result

        override fun isLikelyScanned(text: String): Boolean = text.trim().length < 40
    }

    private class FakeOcr(private val document: OcrDocument?) : OcrRecognizer {
        override suspend fun recognize(uri: Uri): OcrDocument =
            document ?: throw IllegalStateException("nothing recognised")
    }

    private fun importer(
        pdf: PdfTextExtractor,
        ocr: OcrRecognizer = FakeOcr(null),
        mimeType: String? = "application/pdf",
    ) = PrescriptionImporter(context, pdf, ocr).apply { mimeTypeOf = { mimeType } }

    private val now: Instant = Instant.parse("2026-05-21T09:00:00Z")

    @Test
    fun `a printed pdf is queued with its code and czech dates`() = runTest {
        val result = importer(FakePdf(Result.success(printedPdf))).import(file, now)

        val found = result as ImportResult.Found
        assertEquals("7A2B9C4D1E6F", found.prescription.code)
        val prague = ZoneId.of("Europe/Prague")
        assertEquals(
            LocalDate.of(2026, 5, 20).atStartOfDay(prague).toInstant(),
            found.prescription.issuedAt,
        )
        assertEquals(
            LocalDate.of(2026, 6, 3).atTime(23, 59, 59).atZone(prague).toInstant(),
            found.prescription.validUntil,
        )
        assertEquals(listOf("Estradiol 2 mg"), found.prescription.medications.map { it.name })
    }

    @Test
    fun `a photo is recognised and read like a pdf`() = runTest {
        val ocr = FakeOcr(OcrDocument(listOf(listOf("Kód receptu: 7A2B9C4D1E6F", "Estradiol 2 mg 1x"))))
        val result = importer(
            pdf = FakePdf(Result.failure(IllegalStateException("not a pdf"))),
            ocr = ocr,
            mimeType = "image/jpeg",
        ).import(file, now)

        val found = result as ImportResult.Found
        assertEquals("7A2B9C4D1E6F", found.prescription.code)
    }

    @Test
    fun `text without a code is its own failure`() = runTest {
        val text = "Laboratorní výsledky\nKrevní obraz v normě\nZávěr: bez patologie"
        assertEquals(ImportResult.NoCode, importer(FakePdf(Result.success(text))).import(file, now))
    }

    @Test
    fun `a scanned pdf asks for a photo instead of being sent to ocr`() = runTest {
        val result = importer(FakePdf(Result.success("  \n "))).import(file, now)
        assertEquals(ImportResult.NoTextLayer, result)
    }

    @Test
    fun `a file that cannot be opened is unreadable`() = runTest {
        val pdf = FakePdf(Result.failure(IllegalStateException("encrypted")))
        assertEquals(ImportResult.Unreadable, importer(pdf).import(file, now))
    }

    @Test
    fun `an unreadable photo is unreadable, not a missing code`() = runTest {
        val result = importer(
            pdf = FakePdf(Result.failure(IllegalStateException("not a pdf"))),
            ocr = FakeOcr(null),
            mimeType = "image/png",
        ).import(file, now)

        assertEquals(ImportResult.Unreadable, result)
    }

    @Test
    fun `a pdf of unknown mime type still goes through the text layer`() = runTest {
        val result = importer(FakePdf(Result.success(printedPdf)), mimeType = null).import(file, now)
        assertTrue(result is ImportResult.Found)
    }
}
