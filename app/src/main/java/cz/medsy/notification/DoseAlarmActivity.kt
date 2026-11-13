package cz.medsy.notification

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cz.medsy.data.settings.AppSettings
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.domain.alert.LockScreenAlert
import cz.medsy.ui.alerts.DoseAlertScreen
import cz.medsy.ui.theme.MedsyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The dose reminder shown over the lock screen when the alarm fires while the phone is
 * locked. Launched by the notification's full-screen intent, so it only appears while the
 * device is locked or the screen is off; otherwise the heads-up notification is the whole
 * alert and this activity never starts.
 *
 * Every action finishes the activity and leaves the reminder notification in whatever
 * state [DoseActionHandler] put it in: taken/skipped/snoozed reminders disappear, a PRN
 * refusal keeps the notification so the user can resolve it in the app.
 */
@AndroidEntryPoint
class DoseAlarmActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguard()
        enableEdgeToEdge()

        // A wake-the-phone overlay must never hold the screen forever.
        lifecycleScope.launch {
            delay(LockScreenAlert.AUTO_DISMISS_MINUTES * 60_000L)
            finish()
        }

        setContent {
            val viewModel: DoseAlertViewModel = hiltViewModel()
            val appSettings by settings.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val ui by viewModel.state.collectAsStateWithLifecycle()

            // The schedule was deleted while the alarm was armed: nothing to announce.
            LaunchedEffect(ui.isEmpty) {
                if (ui.isEmpty) finish()
            }

            MedsyTheme(
                config = appSettings.toThemeConfig(),
                discreetMode = appSettings.discreetMode,
            ) {
                DoseAlertScreen(
                    ui = ui,
                    discreet = appSettings.discreetMode,
                    snoozeMinutes = appSettings.snoozeMinutes,
                    onTake = { viewModel.take(onFinished = ::finish) },
                    onSkip = { viewModel.skip(onFinished = ::finish) },
                    onSnooze = { viewModel.snooze(minutes = appSettings.snoozeMinutes, onFinished = ::finish) },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /**
     * `setShowWhenLocked` / `setTurnScreenOn` exist from API 27; the window flags are the
     * only way on 26, which is still our minSdk.
     */
    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        fun intent(context: Context, medicationId: Long, scheduleId: Long, dueAt: Long): Intent =
            Intent(context, DoseAlarmActivity::class.java).apply {
                // A dose alarm is never part of the app's task: it must not resurrect the
                // screen the user left behind when it closes.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(DoseAlarmReceiver.EXTRA_MEDICATION_ID, medicationId)
                putExtra(DoseAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
                putExtra(DoseAlarmReceiver.EXTRA_DUE_AT, dueAt)
            }
    }
}
