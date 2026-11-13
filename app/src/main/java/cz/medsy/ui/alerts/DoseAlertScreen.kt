package cz.medsy.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.data.settings.AppSettings
import cz.medsy.ui.theme.MedsyTheme

/**
 * What the full-screen dose alert renders.
 *
 * [isEmpty] means the schedule is gone by the time the overlay opens; the activity closes
 * itself in that case, so no medication name is ever invented.
 */
data class DoseAlertUi(
    val loading: Boolean = true,
    val medicationName: String = "",
    val doseText: String = "",
    val timeText: String = "",
    /** Why a PRN ceiling refused the dose; shown inline instead of closing. */
    val limitMessage: String? = null,
    val busy: Boolean = false,
    val isEmpty: Boolean = false,
)

/**
 * The lock-screen overlay: readable at arm's length, one primary action, and no way to
 * dismiss it into a state where the reminder is silently lost (the notification remains).
 *
 * Discreet mode masks the medication name exactly like the notification does, because this
 * screen is visible without unlocking the device.
 */
@Composable
fun DoseAlertScreen(
    ui: DoseAlertUi,
    discreet: Boolean,
    snoozeMinutes: Int,
    onTake: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Surface
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (discreet) {
                    stringResource(R.string.notification_dose_title_discreet)
                } else {
                    stringResource(R.string.alert_dose_due)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (!discreet) {
                Text(
                    text = ui.medicationName,
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.notification_dose_text, ui.doseText, ui.timeText),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (ui.limitMessage != null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = ui.limitMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onTake,
                enabled = !ui.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = stringResource(R.string.dose_take),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onSnooze,
                enabled = !ui.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text(
                    text = stringResource(R.string.alert_snooze_minutes, snoozeMinutes),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onSkip,
                enabled = !ui.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.dose_skip),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alert_not_now))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.alert_keeps_reminder),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "Dose alert light")
@Preview(name = "Dose alert dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DoseAlertPreview() {
    MedsyTheme {
        DoseAlertScreen(
            ui = DoseAlertUi(
                loading = false,
                medicationName = "Estradiol valerate",
                doseText = "2 mg",
                timeText = "08:00",
            ),
            discreet = false,
            snoozeMinutes = 15,
            onTake = {},
            onSkip = {},
            onSnooze = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Dose alert discreet", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DoseAlertDiscreetPreview() {
    MedsyTheme(config = AppSettings(discreetMode = true).toThemeConfig(), discreetMode = true) {
        DoseAlertScreen(
            ui = DoseAlertUi(loading = false, doseText = "2 mg", timeText = "08:00"),
            discreet = true,
            snoozeMinutes = 15,
            onTake = {},
            onSkip = {},
            onSnooze = {},
            onDismiss = {},
        )
    }
}
