package cz.medsy.data.lab

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Recognised text kept as blocks of lines, in the order ML Kit returned it.
 *
 * Blocks are preserved rather than flattened to one string because the join rule is the
 * interesting part (see [text]) and it is the part worth testing without a camera.
 */
data class OcrDocument(val blocks: List<List<String>>) {

    fun lines(): List<String> = blocks.flatten().map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Lines are joined with a newline and never with a space. Czech lab reports print the
     * analyte and its value on one line ("Estradiol 610 pmol/L"); gluing blocks with spaces
     * would splice the value of one column onto the name of the next whenever the table is
     * recognised as two blocks, and would split nothing that was already whole.
     */
    fun text(): String = lines().joinToString("\n")
}

/**
 * Thin seam over ML Kit. It exists so the extraction tests can supply text from a fake:
 * the recogniser itself needs Android and a trained model, neither of which runs in a
 * JVM unit test.
 */
interface OcrRecognizer {
    suspend fun recognize(uri: Uri): OcrDocument
}

@Singleton
class MlKitOcrRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrRecognizer {

    override suspend fun recognize(uri: Uri): OcrDocument {
        // fromFilePath handles both file:// from the camera cache and content:// from the
        // photo picker, including EXIF rotation.
        val image = InputImage.fromFilePath(context, uri)
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { continuation ->
                client.process(image)
                    .addOnSuccessListener { text ->
                        val blocks = text.textBlocks.map { block -> block.lines.map { it.text } }
                        continuation.resume(OcrDocument(blocks))
                    }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } finally {
            // One client per recogniser call: a shared one would hold the native model
            // loaded for the whole process. Recognition happens once per photo, so this
            // is not on a hot path.
            client.close()
        }
    }
}
