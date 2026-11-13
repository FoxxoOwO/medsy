package cz.medsy.data.uv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant

/** A place the user typed in, resolved to coordinates. */
data class UvPlace(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

/** One reading and when it was downloaded. */
data class UvReading(
    val uvIndex: Double,
    val fetchedAt: Instant,
    val place: String?,
) {
    fun isFresh(now: Instant, maxAge: Duration = UvRepository.MAX_AGE): Boolean =
        Duration.between(fetchedAt, now).let { it >= Duration.ZERO && it < maxAge }
}

/** Everything that touches the network, so the cache logic stays testable. */
interface UvSource {
    suspend fun locate(city: String): UvPlace?
    suspend fun uvIndex(place: UvPlace): Double?
}

interface UvCache {
    suspend fun read(): UvReading?
    suspend fun write(reading: UvReading)
}

/**
 * Cache-or-network UV reading. A reading younger than three hours wins without touching
 * the network. Anything older is refetched, and a failed refetch returns null rather than
 * the stale number: showing yesterday's UV as today's would be worse than showing nothing.
 */
class UvRepository(
    private val source: UvSource,
    private val cache: UvCache,
    private val now: () -> Instant = Instant::now,
) {

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(3)
    }

    suspend fun current(city: String?): UvReading? {
        val cached = cache.read()
        if (cached != null && cached.isFresh(now())) return cached

        val query = city?.trim().orEmpty()
        if (query.isEmpty()) return null

        val place = runCatching { source.locate(query) }.getOrNull() ?: return null
        val uv = runCatching { source.uvIndex(place) }.getOrNull() ?: return null
        val reading = UvReading(uvIndex = uv, fetchedAt = now(), place = place.label)
        runCatching { cache.write(reading) }
        return reading
    }
}

/** Open-Meteo: no key, no account, and the same numbers the phone's weather app shows. */
class OpenMeteoUvSource(
    private val fetch: (String) -> String? = ::httpGet,
) : UvSource {

    override suspend fun locate(city: String): UvPlace? {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${encode(city)}&count=1&language=en&format=json"
        return fetch(url)?.let(OpenMeteoParser::place)
    }

    override suspend fun uvIndex(place: UvPlace): Double? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${place.latitude}&longitude=${place.longitude}&current=uv_index"
        return fetch(url)?.let(OpenMeteoParser::uvIndex)
    }

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")
}

/**
 * Parsing is separated from fetching so it can be tested against real payloads without a
 * network, and so a schema change shows up as a null instead of an exception.
 */
object OpenMeteoParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun uvIndex(payload: String): Double? = runCatching {
        json.parseToJsonElement(payload)
            .jsonObject["current"]
            ?.takeIf { it !is JsonNull }
            ?.jsonObject
            ?.get("uv_index")
            ?.takeIf { it !is JsonNull }
            ?.jsonPrimitive
            ?.doubleOrNull
    }.getOrNull()

    fun place(payload: String): UvPlace? = runCatching {
        val results = json.parseToJsonElement(payload)
            .jsonObject["results"]
            ?.takeIf { it !is JsonNull }
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: return@runCatching null
        val first = results.firstOrNull()?.jsonObject ?: return@runCatching null
        val latitude = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
        val longitude = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
        val name = first["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val country = first["country"]?.jsonPrimitive?.contentOrNull
        UvPlace(
            latitude = latitude,
            longitude = longitude,
            label = listOfNotNull(name, country).joinToString(", "),
        )
    }.getOrNull()
}

private fun httpGet(url: String): String? = runCatching {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.requestMethod = "GET"
    try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}.getOrNull()
