package cz.medsy.data.lab

import android.net.Uri

/**
 * Text layer of a picked PDF.
 *
 * An interface rather than the concrete [LabPdfExtractor] because the prescription importer
 * needs to be testable without a real PDF file: a lab report and an eRecept are fed through the
 * same PDFBox call, and only the text differs.
 */
interface PdfTextExtractor {

    /** Empty [Result] failure when the file cannot be opened, is encrypted, or is not a PDF. */
    suspend fun extractText(uri: Uri): Result<String>

    /** True when the page carried no text layer — a scan, not a print. */
    fun isLikelyScanned(text: String): Boolean
}
