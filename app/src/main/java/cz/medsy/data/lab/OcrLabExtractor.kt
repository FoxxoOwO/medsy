package cz.medsy.data.lab

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photographed report → staging rows.
 *
 * Deliberately never throws: an unreadable or unopenable photo produces an empty staging
 * result, which the screen shows as "nothing recognised" with a retake button. Letting the
 * exception escape would surface as a crash on a blurry picture, the single most likely
 * input.
 */
@Singleton
class OcrLabExtractor @Inject constructor(
    private val recognizer: OcrRecognizer,
    private val pipeline: LabImportPipeline,
) {

    suspend fun import(uri: Uri): LabImportPipeline.StagingResult = withContext(Dispatchers.IO) {
        val document = runCatching { recognizer.recognize(uri) }.getOrNull()
        pipeline.importOcrText(document?.text().orEmpty())
    }
}
