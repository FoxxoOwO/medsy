package cz.medsy.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings switch used to stay off because the permission request resolved to an empty set:
 * Health Connect refuses the sheet when the app declares no rationale activity. Both halves of
 * that contract are checked here, since neither shows up in the UI when it breaks.
 */
class HealthPermissionsTest {

    private val manifest = listOf(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    ).first { it.exists() }

    @Test
    fun `writes need both permission`() {
        assertTrue(HealthPermissions.allowsWrites(HealthPermissions.writePermissions))
    }

    @Test
    fun `one missing write permission is not enough`() {
        val half = setOf(HealthPermissions.WRITE_HEART_RATE)

        assertFalse(HealthPermissions.allowsWrites(half))
    }

    @Test
    fun `an empty result means the request was refused`() {
        assertFalse(HealthPermissions.allowsWrites(emptySet()))
    }

    @Test
    fun `reading alone does not allow writing`() {
        assertFalse(HealthPermissions.allowsWrites(HealthPermissions.readPermissions))
    }

    @Test
    fun `the rationale activity is declared`() {
        val text = manifest.readText()

        assertTrue(
            "missing androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE",
            text.contains("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"),
        )
        assertTrue(
            "missing .health.HealthRationaleActivity",
            text.contains("""android:name=".health.HealthRationaleActivity""""),
        )
        assertTrue(
            "the rationale activity must be exported or Health Connect cannot start it",
            Regex("""HealthRationaleActivity"[\s\S]{0,200}?android:exported="true"""").containsMatchIn(text),
        )
    }
}
