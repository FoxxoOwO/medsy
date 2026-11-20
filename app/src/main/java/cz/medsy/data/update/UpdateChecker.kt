package cz.medsy.data.update

import android.content.Context
import android.util.Log
import cz.medsy.BuildConfig
import cz.medsy.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A release newer than the one running: the tag without the leading `v`, and its APK. */
data class AvailableUpdate(val version: String, val apkUrl: String)

/**
 * Compares dotted versions numerically, so 1.10.0 is newer than 1.9.0 — a string compare would
 * offer a downgrade and then keep offering it forever.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    fun parts(raw: String) = raw.trim().removePrefix("v").substringBefore('-')
        .split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

/** Pulls the version and the release APK out of a GitHub `releases/latest` body. */
internal fun parseRelease(body: String): AvailableUpdate? = runCatching {
    val root = JSONObject(body)
    val version = root.optString("tag_name").trim().removePrefix("v")
    val assets = root.optJSONArray("assets")
    val apk = assets?.let { array ->
        (0 until array.length())
            .map { array.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith("-release.apk") }
            ?.optString("browser_download_url")
    }
    if (version.isBlank() || apk.isNullOrBlank()) null else AvailableUpdate(version, apk)
}.getOrNull()

/**
 * Asks GitHub for the latest release and remembers it, so the settings screen can show it without
 * a network round trip. The project ships as an APK on GitHub releases; this is the only way an
 * installed copy learns that a newer one exists.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    /** Test seam: unit tests have no network. */
    internal var fetch: (String) -> String? = ::read

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the update only when it is actually newer than this install. */
    suspend fun check(): AvailableUpdate? {
        val body = withContext(Dispatchers.IO) { runCatching { fetch(LATEST_RELEASE) }.getOrNull() }
        if (body == null) {
            Log.d(TAG, "update check failed")
            return null
        }
        val latest = parseRelease(body)
        settings.recordUpdateCheck(latest?.version, latest?.apkUrl, Instant.now().toEpochMilli())
        return latest?.takeIf { isNewerVersion(it.version, currentVersion) }
    }

    private fun read(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub answers 403 without one.
            setRequestProperty("User-Agent", "Medsy/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
        const val TIMEOUT_MS = 8_000
        const val LATEST_RELEASE = "https://api.github.com/repos/FoxxoOwO/medsy/releases/latest"
    }
}
