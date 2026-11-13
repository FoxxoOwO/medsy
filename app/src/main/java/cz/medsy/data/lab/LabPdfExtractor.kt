package cz.medsy.data.lab

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LabPdfExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Extracts text from a PDF picked through the Storage Access Framework.
     * Encrypted or image-only PDFs return an empty string so the caller can fall
     * back to OCR of a photographed report.
     */
    suspend fun extractText(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            PDFBoxResourceLoader.init(context)
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Cannot open $uri")
            stream.use { input ->
                PDDocument.load(input).use { document ->
                    if (document.isEncrypted) {
                        error("The PDF is encrypted")
                    }
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = 1
                        endPage = document.numberOfPages
                    }
                    stripper.getText(document).orEmpty()
                }
            }
        }
    }

    fun isLikelyScanned(text: String): Boolean = text.trim().length < MIN_TEXT_LENGTH

    private companion object {
        const val MIN_TEXT_LENGTH = 40
    }
}
