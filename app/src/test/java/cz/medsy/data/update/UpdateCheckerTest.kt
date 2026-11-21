package cz.medsy.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun checker(body: String?): UpdateChecker {
        val checker = UpdateChecker(context, SettingsRepository(context))
        checker.fetch = { body }
        return checker
    }

    @Test
    fun `versions compare numerically, not as text`() {
        assertTrue(isNewerVersion("1.10.0", "1.9.0"))
        assertTrue(isNewerVersion("2.0", "1.99.9"))
        assertTrue(isNewerVersion("v1.2", "1.1.0"))
        assertFalse(isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(isNewerVersion("1.1", "1.1.0"))
        assertFalse(isNewerVersion("1.0.9", "1.1.0"))
    }

    @Test
    fun `the release asset is picked out of the release body`() {
        val body = """
            {"tag_name":"v1.3.0","assets":[
              {"name":"medsy-1.3.0-debug.apk","browser_download_url":"https://example/debug"},
              {"name":"medsy-1.3.0-release.apk","browser_download_url":"https://github.com/FoxxoOwO/medsy/releases/download/v1.3.0/medsy-1.3.0-release.apk"}]}
        """.trimIndent()
        assertEquals(AvailableUpdate("1.3.0", "https://github.com/FoxxoOwO/medsy/releases/download/v1.3.0/medsy-1.3.0-release.apk"), parseRelease(body))
    }

    @Test
    fun `a release without an apk is not an update`() {
        assertNull(parseRelease("""{"tag_name":"v1.3.0","assets":[]}"""))
        assertNull(parseRelease("""{"assets":[{"name":"medsy-1.3.0-release.apk"}]}"""))
        assertNull(parseRelease("<html>rate limited</html>"))
    }

    @Test
    fun `a download url outside github is rejected`() {
        fun body(url: String) = """{"tag_name":"v1.3.0","assets":[
            {"name":"medsy-1.3.0-release.apk","browser_download_url":"$url"}]}"""

        assertNull(parseRelease(body("http://github.com/medsy/release.apk"))) // cleartext
        assertNull(parseRelease(body("https://evil.example/medsy-release.apk"))) // foreign host
        assertNull(parseRelease(body("https://github.com.evil.example/a.apk"))) // suffix trick
        assertNull(parseRelease(body("not a url")))
        assertEquals(
            "1.3.0",
            parseRelease(body("https://objects.githubusercontent.com/a/medsy-release.apk"))?.version,
        )
    }

    @Test
    fun `a newer release is offered and remembered`() = runBlocking {
        val checker = checker("""{"tag_name":"v9.0.0","assets":[{"name":"medsy-9.0.0-release.apk","browser_download_url":"https://github.com/FoxxoOwO/medsy/releases/download/v9.0.0/medsy-9.0.0-release.apk"}]}""")

        val available = checker.check()

        assertEquals("9.0.0", available?.version)
        val stored = SettingsRepository(context).settings.first()
        assertEquals("9.0.0", stored.latestVersion)
        assertEquals("https://github.com/FoxxoOwO/medsy/releases/download/v9.0.0/medsy-9.0.0-release.apk", stored.latestApkUrl)
        assertTrue(stored.updateCheckedAt > 0L)
    }

    @Test
    fun `an older release is remembered but not offered`() = runBlocking {
        val checker = checker("""{"tag_name":"v0.9.0","assets":[{"name":"medsy-0.9.0-release.apk","browser_download_url":"https://github.com/FoxxoOwO/medsy/releases/download/v9.0.0/medsy-9.0.0-release.apk"}]}""")

        assertNull(checker.check())
        assertEquals("0.9.0", SettingsRepository(context).settings.first().latestVersion)
    }

    @Test
    fun `an offline check keeps the last known release`() = runBlocking {
        val online = checker("""{"tag_name":"v9.0.0","assets":[{"name":"medsy-9.0.0-release.apk","browser_download_url":"https://github.com/FoxxoOwO/medsy/releases/download/v9.0.0/medsy-9.0.0-release.apk"}]}""")
        online.check()

        assertNull(checker(null).check())
        val stored = SettingsRepository(context).settings.first()
        assertEquals("9.0.0", stored.latestVersion)
        assertEquals("https://github.com/FoxxoOwO/medsy/releases/download/v9.0.0/medsy-9.0.0-release.apk", stored.latestApkUrl)
    }
}
