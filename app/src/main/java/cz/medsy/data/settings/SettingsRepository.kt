package cz.medsy.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cz.medsy.data.uv.UvCache
import cz.medsy.data.uv.UvReading
import androidx.datastore.preferences.preferencesDataStore
import cz.medsy.ui.theme.MedsyPalette
import cz.medsy.ui.theme.ThemeConfig
import cz.medsy.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

private val Context.settingsData: DataStore<Preferences> by preferencesDataStore(name = "medsy_settings")

data class AppSettings(
    val discreetMode: Boolean = false,
    val discreetIcon: Boolean = false,
    val biometricLock: Boolean = false,
    val dynamicColor: Boolean = true,
    val palette: MedsyPalette = MedsyPalette.BLOSSOM,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultRangeProfile: String = "HRT_FEM",
    val healthConnectEnabled: Boolean = false,
    val remindersEnabled: Boolean = true,
    val reminderSound: Boolean = true,
    val reminderVibration: Boolean = true,
    /** Minutes before the scheduled dose to alert. 0 = exactly on time. */
    val reminderLeadMinutes: Int = 0,
    /** Show a full-screen alert over the lock screen when a dose alarm fires. */
    val fullScreenReminders: Boolean = false,
    val snoozeMinutes: Int = 15,
    /** Show the "high UV + photosensitising medication" card on the dashboard. */
    val uvWarningsEnabled: Boolean = true,
    /** City used for the UV lookup; blank means "do not fetch". */
    val uvCity: String? = null,
) {
    fun toThemeConfig(): ThemeConfig = ThemeConfig(
        palette = palette,
        mode = themeMode,
        dynamicColor = dynamicColor,
    )
}

class SettingsRepository(private val context: Context) : UvCache {

    private object Keys {
        val discreetMode = booleanPreferencesKey("discreet_mode")
        val discreetIcon = booleanPreferencesKey("discreet_icon")
        val biometricLock = booleanPreferencesKey("biometric_lock")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val palette = stringPreferencesKey("palette")
        val themeMode = stringPreferencesKey("theme_mode")
        val defaultRange = stringPreferencesKey("default_range")
        val healthConnect = booleanPreferencesKey("health_connect")
        val remindersEnabled = booleanPreferencesKey("reminders_enabled")
        val reminderSound = booleanPreferencesKey("reminder_sound")
        val reminderVibration = booleanPreferencesKey("reminder_vibration")
        val reminderLead = intPreferencesKey("reminder_lead_minutes")
        val fullScreenReminders = booleanPreferencesKey("full_screen_reminders")
        val snooze = intPreferencesKey("snooze_minutes")
        val uvWarnings = booleanPreferencesKey("uv_warnings")
        val uvCity = stringPreferencesKey("uv_city")
        val uvValue = doublePreferencesKey("uv_value")
        val uvFetchedAt = longPreferencesKey("uv_fetched_at")
        val uvPlace = stringPreferencesKey("uv_place")
    }

    val settings: Flow<AppSettings> = context.settingsData.data.map { prefs ->
        AppSettings(
            discreetMode = prefs[Keys.discreetMode] ?: false,
            discreetIcon = prefs[Keys.discreetIcon] ?: false,
            biometricLock = prefs[Keys.biometricLock] ?: false,
            dynamicColor = prefs[Keys.dynamicColor] ?: true,
            palette = prefs[Keys.palette]?.let { runCatching { MedsyPalette.valueOf(it) }.getOrNull() }
                ?: MedsyPalette.BLOSSOM,
            themeMode = prefs[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            defaultRangeProfile = prefs[Keys.defaultRange] ?: "HRT_FEM",
            healthConnectEnabled = prefs[Keys.healthConnect] ?: false,
            remindersEnabled = prefs[Keys.remindersEnabled] ?: true,
            reminderSound = prefs[Keys.reminderSound] ?: true,
            reminderVibration = prefs[Keys.reminderVibration] ?: true,
            reminderLeadMinutes = prefs[Keys.reminderLead] ?: 0,
            fullScreenReminders = prefs[Keys.fullScreenReminders] ?: false,
            snoozeMinutes = prefs[Keys.snooze] ?: 15,
            uvWarningsEnabled = prefs[Keys.uvWarnings] ?: true,
            uvCity = prefs[Keys.uvCity]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setDiscreetMode(enabled: Boolean) = context.settingsData.edit { it[Keys.discreetMode] = enabled }

    suspend fun setDiscreetIcon(enabled: Boolean) = context.settingsData.edit { it[Keys.discreetIcon] = enabled }

    suspend fun setBiometricLock(enabled: Boolean) = context.settingsData.edit { it[Keys.biometricLock] = enabled }

    suspend fun setDynamicColor(enabled: Boolean) = context.settingsData.edit { it[Keys.dynamicColor] = enabled }

    suspend fun setPalette(palette: MedsyPalette) =
        context.settingsData.edit { it[Keys.palette] = palette.name }

    suspend fun setThemeMode(mode: ThemeMode) =
        context.settingsData.edit { it[Keys.themeMode] = mode.name }

    suspend fun setDefaultRangeProfile(profile: String) =
        context.settingsData.edit { it[Keys.defaultRange] = profile }

    suspend fun setHealthConnect(enabled: Boolean) =
        context.settingsData.edit { it[Keys.healthConnect] = enabled }

    suspend fun setRemindersEnabled(enabled: Boolean) =
        context.settingsData.edit { it[Keys.remindersEnabled] = enabled }

    suspend fun setReminderSound(enabled: Boolean) =
        context.settingsData.edit { it[Keys.reminderSound] = enabled }

    suspend fun setReminderVibration(enabled: Boolean) =
        context.settingsData.edit { it[Keys.reminderVibration] = enabled }

    suspend fun setReminderLeadMinutes(minutes: Int) =
        context.settingsData.edit { it[Keys.reminderLead] = minutes.coerceIn(0, 120) }

    suspend fun setFullScreenReminders(enabled: Boolean) =
        context.settingsData.edit { it[Keys.fullScreenReminders] = enabled }

    suspend fun setUvWarningsEnabled(enabled: Boolean) =
        context.settingsData.edit { it[Keys.uvWarnings] = enabled }

    suspend fun setUvCity(city: String) = context.settingsData.edit {
        val trimmed = city.trim()
        if (trimmed.isEmpty()) {
            it.remove(Keys.uvCity)
        } else {
            it[Keys.uvCity] = trimmed
        }
        // A different city invalidates the reading: the cached number belongs to the old one.
        it.remove(Keys.uvValue)
        it.remove(Keys.uvFetchedAt)
        it.remove(Keys.uvPlace)
    }

    suspend fun saveUvReading(reading: UvReading) = context.settingsData.edit {
        it[Keys.uvValue] = reading.uvIndex
        it[Keys.uvFetchedAt] = reading.fetchedAt.toEpochMilli()
        val place = reading.place
        if (place == null) it.remove(Keys.uvPlace) else it[Keys.uvPlace] = place
    }

    suspend fun readUvReading(): UvReading? {
        val prefs = context.settingsData.data.first()
        val uv = prefs[Keys.uvValue] ?: return null
        val fetchedAt = prefs[Keys.uvFetchedAt] ?: return null
        return UvReading(
            uvIndex = uv,
            fetchedAt = Instant.ofEpochMilli(fetchedAt),
            place = prefs[Keys.uvPlace],
        )
    }

    suspend fun setSnoozeMinutes(minutes: Int) =
        context.settingsData.edit { it[Keys.snooze] = minutes.coerceIn(5, 120) }

    override suspend fun read(): UvReading? = readUvReading()

    override suspend fun write(reading: UvReading) {
        saveUvReading(reading)
    }
}
