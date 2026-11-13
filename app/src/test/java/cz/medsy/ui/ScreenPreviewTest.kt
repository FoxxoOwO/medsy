package cz.medsy.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F9: every screen must be reviewable in both themes without a device. Previews are the
 * only place where layout and colour are checked by eye, so a screen without a dark
 * variant hides contrast bugs until someone runs it at night.
 */
class ScreenPreviewTest {

    private val uiDir = listOf(
        File("src/main/java/cz/medsy/ui"),
        File("app/src/main/java/cz/medsy/ui"),
    ).first { it.exists() }

    private val screens = listOf(
        "bloodwork/BloodworkScreen.kt",
        "bloodwork/CustomTargetScreen.kt",
        "bloodwork/LabScanScreen.kt",
        "dashboard/DashboardScreen.kt",
        "inventory/InventoryScreen.kt",
        "medications/MedicationsScreen.kt",
        "prescriptions/PrescriptionsScreen.kt",
        "scanner/ScannerScreen.kt",
        "schedule/ScheduleEditorScreen.kt",
        "settings/SettingsScreen.kt",
        "symptoms/SymptomsScreen.kt",
        "MedsyApp.kt",
    )

    private fun previews(rel: String): List<String> {
        val file = File(uiDir, rel)
        assertTrue("missing source file $rel", file.exists())
        return file.readLines().filter { it.trimStart().startsWith("@Preview") }
    }

    @Test
    fun `every screen has at least two previews`() {
        val thin = screens.filter { previews(it).size < 2 }

        assertTrue("screens with fewer than two previews: $thin", thin.isEmpty())
    }

    @Test
    fun `every screen has a dark preview`() {
        val withoutDark = screens.filter { rel ->
            previews(rel).none { it.contains("UI_MODE_NIGHT_YES") }
        }

        assertTrue("screens without a dark preview: $withoutDark", withoutDark.isEmpty())
    }

    @Test
    fun `previews are named so the picker is readable`() {
        val unnamed = screens.filter { rel ->
            previews(rel).any { !it.contains("name = ") }
        }

        assertTrue("unnamed previews in: $unnamed", unnamed.isEmpty())
    }
}
