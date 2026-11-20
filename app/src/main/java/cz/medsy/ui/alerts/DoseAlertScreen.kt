package cz.medsy.ui.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.data.settings.AppSettings
import cz.medsy.ui.theme.MedsyTheme

/**
 * One dose the overlay announces. A single overlay can hold several schedules that land on the
 * same minute, and each of them needs its own Take/Skip so a batch can be logged honestly.
 */
data class DoseAlertDose(
    val medicationId: Long,
    val scheduleId: Long,
    val dueAt: Long,
    val medicationName: String,
    val doseText: String,
)

/**
 * What the full-screen dose alert renders.
 *
 * [isEmpty] means the schedule is gone by the time the overlay opens; the activity closes
 * itself in that case, so no medication name is ever invented.
 */
data class DoseAlertUi(
    val loading: Boolean = true,
    val doses: List<DoseAlertDose> = emptyList(),
    val timeText: String = "",
    /** Why a PRN ceiling refused the dose; shown inline instead of closing. */
    val limitMessage: String? = null,
    val busy: Boolean = false,
    val isEmpty: Boolean = false,
) {
    /** Several medications fell on the same minute, so the screen turns into a list. */
    val isGroup: Boolean get() = doses.size > 1
}

/**
 * The lock-screen overlay: readable at arm's length, one primary action, and no way to
 * dismiss it into a state where the reminder is silently lost (the notification remains).
 *
 * Discreet mode masks the medication names exactly like the notifications do, because this
 * screen is visible without unlocking the device.
 */
@Composable
fun DoseAlertScreen(
    ui: DoseAlertUi,
    discreet: Boolean,
    snoozeMinutes: Int,
    onTake: (DoseAlertDose) -> Unit,
    onSkip: (DoseAlertDose) -> Unit,
    onTakeAll: () -> Unit,
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

            if (ui.isGroup) {
                Text(
                    text = pluralStringResource(
                        R.plurals.alert_group_count,
                        ui.doses.size,
                        ui.doses.size,
                        ui.timeText,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                ui.doses.forEach { dose ->
                    DoseRow(
                        dose = dose,
                        discreet = discreet,
                        enabled = !ui.busy,
                        onTake = onTake,
                        onSkip = onSkip,
                    )
                }
            } else {
                val dose = ui.doses.firstOrNull()
                if (dose != null && !discreet) {
                    Text(
                        text = dose.medicationName,
                        style = MaterialTheme.typography.displaySmallEmphasized,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = stringResource(
                        R.string.notification_dose_text,
                        dose?.doseText.orEmpty(),
                        ui.timeText,
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                onClick = {
                    if (ui.isGroup) onTakeAll() else ui.doses.firstOrNull()?.let(onTake)
                },
                enabled = !ui.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    text = stringResource(
                        if (ui.isGroup) R.string.alert_take_all else R.string.dose_take,
                    ),
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
            if (!ui.isGroup) {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { ui.doses.firstOrNull()?.let(onSkip) },
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

/**
 * One medication in a batch: name and amount on the left, both decisions on the right, so the
 * row reads as a single object instead of two buttons floating in a list.
 */
@Composable
private fun DoseRow(
    dose: DoseAlertDose,
    discreet: Boolean,
    enabled: Boolean,
    onTake: (DoseAlertDose) -> Unit,
    onSkip: (DoseAlertDose) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (!discreet) {
                Text(
                    text = dose.medicationName,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                text = dose.doseText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onSkip(dose) }, enabled = enabled) {
            Text(stringResource(R.string.dose_skip))
        }
        FilledTonalButton(onClick = { onTake(dose) }, enabled = enabled) {
            Text(stringResource(R.string.dose_take))
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
                doses = listOf(
                    DoseAlertDose(1, 1, 0, "Estradiol valerate", "2 mg"),
                    DoseAlertDose(2, 2, 0, "Progesterone", "100 mg"),
                ),
                timeText = "08:00",
            ),
            discreet = false,
            snoozeMinutes = 15,
            onTake = {},
            onSkip = {},
            onTakeAll = {},
            onSnooze = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Dose alert single", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DoseAlertSinglePreview() {
    MedsyTheme {
        DoseAlertScreen(
            ui = DoseAlertUi(
                loading = false,
                doses = listOf(DoseAlertDose(1, 1, 0, "Estradiol valerate", "2 mg")),
                timeText = "08:00",
            ),
            discreet = false,
            snoozeMinutes = 15,
            onTake = {},
            onSkip = {},
            onTakeAll = {},
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
            ui = DoseAlertUi(
                loading = false,
                doses = listOf(DoseAlertDose(1, 1, 0, "Estradiol valerate", "2 mg")),
                timeText = "08:00",
            ),
            discreet = true,
            snoozeMinutes = 15,
            onTake = {},
            onSkip = {},
            onTakeAll = {},
            onSnooze = {},
            onDismiss = {},
        )
    }
}
