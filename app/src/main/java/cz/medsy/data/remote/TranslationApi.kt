package cz.medsy.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One text-to-text translation service.
 *
 * The interface exists so the caching repository can be tested without a socket, and so a
 * self-hosted engine can replace MyMemory without touching the repository.
 */
interface TranslationService {

    /** Translates [text] from Czech into [target] (BCP-47 subtag), or null when unavailable. */
    suspend fun translate(text: String, target: String): String?
}

/**
 * Machine translation over the key-less MyMemory API.
 *
 * Why this service: the app has no backend and must not ship an API key, and the free
 * alternatives either need one (DeepL, Google) or need a self-hosted server (LibreTranslate).
 * MyMemory answers plain anonymous GETs, which keeps the feature working for a user who never
 * signs up for anything.
 *
 * Everything here is best effort by design: a translation is a convenience on top of Czech text
 * that is already complete, so any failure returns null and the caller keeps showing the original.
 * Results are cached in the database by [cz.medsy.data.repository.TranslationRepository], which is
 * what makes the app usable offline after the first look.
 *
 * The API limit is ~500 bytes per request, hence [chunks].
 */
@Singleton
class TranslationApi @Inject constructor() : TranslationService {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun translate(text: String, target: String): String? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext null
        // Long notes are rare but legal; each chunk is one request, so keep them small.
        val parts = chunks(trimmed)
        val translated = ArrayList<String>(parts.size)
        for (part in parts) {
            val piece = request(part, target) ?: return@withContext null
            translated += piece
        }
        translated.joinToString(" ").trim().ifEmpty { null }
    }

    private suspend fun request(text: String, target: String): String? = withContext(Dispatchers.IO) {
        val query = "q=" + URLEncoder.encode(text, "UTF-8") +
            "&langpair=" + URLEncoder.encode("cs|$target", "UTF-8")
        val connection = (URL("$BASE_URL?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (_: IOException) {
            // Offline, DNS failure, timeout: the UI keeps the Czech text.
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SOURCE_LANGUAGE = "cs"
        const val BASE_URL = "https://api.mymemory.translated.net/get"
        const val USER_AGENT = "Medsy/1.0 (+offline-first medication tracker; cz.medsy)"

        /**
         * The service rejects queries over ~500 bytes with a 200-status error payload, so the
         * text is split on word boundaries with headroom for the URL-encoded expansion.
         */
        const val MAX_QUERY_CHARS = 420

        /** Pure, so the JSON contract is testable without a network. */
        fun parse(body: String): String? {
            val root = runCatching { Json { isLenient = true }.parseToJsonElement(body).jsonObject }
                .getOrNull() ?: return null
            val status = (root["responseStatus"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (status != null && status != 200) return null
            val data = root["responseData"] as? kotlinx.serialization.json.JsonObject ?: return null
            val text = (data["translatedText"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) return null
            // The free tier answers with prose in `translatedText` when the daily quota is gone.
            if (text.contains("MYMEMORY WARNING", ignoreCase = true)) return null
            return text
        }

        /**
         * Splits [text] into requests of at most [MAX_QUERY_CHARS] characters, preferring to break
         * after a sentence (or at worst a word) so the joined result still reads as one text.
         */
        fun chunks(text: String, maxChars: Int = MAX_QUERY_CHARS): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > maxChars) {
                val window = rest.take(maxChars)
                val cut = listOf(window.lastIndexOf(". "), window.lastIndexOf("; "), window.lastIndexOf(", "))
                    .maxOrNull()
                    ?.takeIf { it > maxChars / 3 }
                    ?: window.lastIndexOf(' ')
                val splitAt = if (cut > 0) cut + 1 else maxChars
                out += rest.take(splitAt).trim()
                rest = rest.drop(splitAt).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
