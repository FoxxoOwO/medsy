package cz.medsy.data.local.datasource

import android.content.Context
import android.util.Log

/**
 * One JSON asset read once, with its diagnostic kept beside the data.
 *
 * The three immutable catalogues used to each carry a `private var error` written from
 * whichever thread first touched them. Returning the error with the entries keeps the
 * data sources immutable and removes that unsynchronised state.
 */
internal data class AssetLoad<T>(val entries: List<T>, val error: String?)

internal class AssetLoader(private val context: Context) {

    /**
     * [parse] decodes the raw text; the label is only for the log and the diagnostic, never
     * for user-visible prose.
     */
    fun <T> load(asset: String, label: String, parse: (String) -> List<T>): AssetLoad<T> {
        val raw = runCatching {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        }.getOrElse { failure ->
            // A log diagnostic, not user-facing prose — hence `diagnostic`, not `message`.
            val diagnostic = "$label asset unreadable: ${failure.message}"
            Log.e(TAG, diagnostic, failure)
            return AssetLoad(emptyList(), diagnostic)
        }
        return runCatching { parse(raw) }.fold(
            onSuccess = { AssetLoad(it, null) },
            onFailure = { failure ->
                val diagnostic = "$label malformed: ${failure.message}"
                Log.e(TAG, diagnostic, failure)
                AssetLoad(emptyList(), diagnostic)
            },
        )
    }

    private companion object {
        const val TAG = "AssetLoader"
    }
}
