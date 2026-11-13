package cz.medsy.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The product facts this app actually uses from Open Food Facts.
 *
 * The full payload is ~100 KB of nutrition tables; requesting `fields` keeps it to a few KB,
 * which matters because the lookup sits in front of a user waiting with a barcode in hand.
 */
data class OffProduct(
    val code: String,
    val name: String?,
    val nameCs: String?,
    val brands: String?,
    val quantity: String?,
    val ingredients: String?,
    val ingredientsCs: String?,
    val categories: String?,
    val nutrientKeys: Set<String>,
) {
    /** Czech name first: the app's audience reads Czech packaging names. */
    val bestName: String?
        get() = nameCs?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
}

/**
 * Minimal Open Food Facts v2 client over `HttpURLConnection`.
 *
 * No Retrofit/Ktor dependency is added on purpose: this is one GET with a fixed field list,
 * and the project already talks to SÚKL and Open-Meteo the same way. Pulling in a client
 * library for a single endpoint would be a dependency, a proguard surface and a build-time
 * cost for no behaviour.
 */
@Singleton
class OpenFoodFactsApi @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns the product, or null when Open Food Facts does not know the barcode
     * (`status: 0`, HTTP 404). Network failures throw [IOException] so the caller can tell
     * "not in the database" apart from "we are offline".
     */
    suspend fun fetchProduct(ean13: String): OffProduct? = withContext(Dispatchers.IO) {
        val url = URL(
            "$BASE_URL/api/v2/product/${URLEncoder.encode(ean13, "UTF-8")}.json" +
                "?fields=${FIELDS.joinToString(",")}",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            // Open Food Facts throttles anonymous clients that do not identify themselves.
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (status !in 200..299) {
                throw IOException("Open Food Facts answered HTTP $status")
            }
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parse(body, ean13)
        } finally {
            connection.disconnect()
        }
    }

    /** Pure parser, so the mapping is testable without network access. */
    fun parse(body: String, requestedCode: String): OffProduct? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val status = root["status"]?.jsonPrimitive?.content?.toIntOrNull()
        if (status != null && status != 1) return null
        val product = root["product"] as? JsonObject ?: return null
        val name = product.string("product_name")
        val nameCs = product.string("product_name_cs")
        if (name.isNullOrBlank() && nameCs.isNullOrBlank()) return null

        val nutrients = product["nutriments"] as? JsonObject
        return OffProduct(
            code = product.string("code") ?: requestedCode,
            name = name,
            nameCs = nameCs,
            brands = product.string("brands"),
            quantity = product.string("quantity"),
            ingredients = product.string("ingredients_text"),
            ingredientsCs = product.string("ingredients_text_cs"),
            categories = product.string("categories_tags"),
            nutrientKeys = nutrients?.keys.orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private companion object {
        const val BASE_URL = "https://world.openfoodfacts.org"
        const val USER_AGENT = "Medsy/1.0 (+offline-first medication tracker; cz.medsy)"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000

        val FIELDS = listOf(
            "code",
            "product_name",
            "product_name_cs",
            "brands",
            "quantity",
            "nutriments",
            "ingredients_text",
            "ingredients_text_cs",
            "categories_tags",
        )
    }
}
