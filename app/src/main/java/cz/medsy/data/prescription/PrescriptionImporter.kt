package cz.medsy.data.prescription

import android.content.Context
import android.net.Uri
import cz.medsy.data.lab.OcrRecognizer
import cz.medsy.data.lab.PdfTextExtractor
import cz.medsy.domain.model.Prescription
import cz.medsy.domain.scanner.EReceptParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks up an eRecept that arrived as a file instead of as a QR code: the PDF a pharmacy or the
 * eRecept portal hands out, or a photograph of the printed paper.
 *
 * The outcomes are kept apart on purpose. "No code in this text" and "this file has no text at
 * all" look identical to a log line but not to a user, and the fixes differ — retake the photo
 * versus find the QR code. Collapsing them into one empty result would leave the screen silent,
 * which for a prescription queue means the user cannot tell whether anything was imported.
 */
@Singleton
class PrescriptionImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdf: PdfTextExtractor,
    private val ocr: OcrRecognizer,
) {

    /**
     * Overridable in tests: a JVM test has no content provider to answer for a picked file, and
     * the branch that matters is which of the two readers the file is handed to.
     */
    internal var mimeTypeOf: (Uri) -> String? = { uri ->
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
    }

    sealed interface ImportResult {
        data class Found(val prescription: Prescription) : ImportResult

        /** Readable text, but nothing in it looks like an eRecept code. */
        data object NoCode : ImportResult

        /** A scanned PDF: pages are pixels, so there is no text to parse. */
        data object NoTextLayer : ImportResult

        /** The file could not be opened at all (wrong type, encrypted, unreadable). */
        data object Unreadable : ImportResult
    }

    suspend fun import(uri: Uri, now: Instant = Instant.now()): ImportResult =
        withContext(Dispatchers.IO) {
            val mimeType = mimeTypeOf(uri)
            val text: String = if (mimeType?.startsWith("image/") == true) {
                ocrText(uri) ?: return@withContext ImportResult.Unreadable
            } else {
                val extracted = pdf.extractText(uri).getOrNull()
                    ?: return@withContext ImportResult.Unreadable
                // A PDF without a text layer is reported as such rather than handed to OCR:
                // ML Kit reads bitmaps, and this app does not render PDF pages to bitmaps.
                if (pdf.isLikelyScanned(extracted)) return@withContext ImportResult.NoTextLayer
                extracted
            }
            EReceptParser.parse(text, now)?.let(ImportResult::Found) ?: ImportResult.NoCode
        }

    /** A photo has to be recognised; an unreadable picture is not a readable prescription. */
    private suspend fun ocrText(uri: Uri): String? =
        runCatching { ocr.recognize(uri).text() }.getOrNull()?.takeIf { it.isNotBlank() }
}
