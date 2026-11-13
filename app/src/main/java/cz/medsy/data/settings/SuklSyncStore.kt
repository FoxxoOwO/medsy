package cz.medsy.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** What the last SÚKL sync did, so the settings screen can say more than "nothing happened". */
data class SuklSyncRecord(
    val finishedAt: Long? = null,
    val count: Int = 0,
    val failed: Boolean = false,
)

private val Context.suklSyncData: DataStore<Preferences> by
    preferencesDataStore(name = "medsy_sukl_sync")

/**
 * The worker and the settings screen run at different times, so the outcome has to outlive the
 * process: a sync that finished while the app was closed must still be visible afterwards.
 */
@Singleton
class SuklSyncStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val finishedAt = longPreferencesKey("finished_at")
        val count = intPreferencesKey("count")
        val failed = booleanPreferencesKey("failed")
    }

    val record: Flow<SuklSyncRecord> = context.suklSyncData.data.map { prefs ->
        SuklSyncRecord(
            finishedAt = prefs[Keys.finishedAt],
            count = prefs[Keys.count] ?: 0,
            failed = prefs[Keys.failed] ?: false,
        )
    }

    suspend fun recordSuccess(medications: Int, at: Long = System.currentTimeMillis()) =
        context.suklSyncData.edit { prefs ->
            prefs[Keys.finishedAt] = at
            prefs[Keys.count] = medications
            prefs[Keys.failed] = false
        }

    /** Keeps the previous timestamp — a failed run must not erase when the last good one was. */
    suspend fun recordFailure() = context.suklSyncData.edit { prefs ->
        prefs[Keys.failed] = true
    }
}
