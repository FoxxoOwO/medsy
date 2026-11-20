package cz.medsy.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * Android refuses a truly silent install for a side-loaded app: the package manager always shows
 * its own confirmation. So "automatic" stops at the download — the user taps one button and then
 * confirms in the system dialog.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Result {
        /** The system installer is on screen. */
        data object Started : Result

        /** The user has to allow installs from this app first. */
        data object AskedForPermission : Result

        data object Failed : Result
    }

    suspend fun downloadAndInstall(url: String): Result {
        val file = withContext(Dispatchers.IO) { download(url) } ?: return Result.Failed
        if (!context.packageManager.canRequestPackageInstalls()) {
            askForInstallPermission()
            return Result.AskedForPermission
        }
        val apk: Uri = FileProvider.getUriForFile(context, "${context.packageName}.$AUTHORITY_SUFFIX", file)
        return install(apk)
    }

    private fun install(apk: Uri): Result = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        Result.Started
    }.getOrDefault(Result.Failed)

    private fun askForInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Replaces the previous download: the cache is not a place to collect APKs. */
    private fun download(url: String): File? = runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "medsy-update.apk")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub redirects release assets to its CDN.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Medsy")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.takeIf { it.length() > 0 }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val TIMEOUT_MS = 30_000
        const val APK_MIME = "application/vnd.android.package-archive"
        const val AUTHORITY_SUFFIX = "updates"
    }
}
