package cz.medsy.ui.dashboard

import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.ui.message.asText

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.DoseStatus
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.schedule.Occurrence
import cz.medsy.ui.components.BodyMapPicker
import cz.medsy.ui.components.MedicationIcon
import cz.medsy.ui.components.MorphingDoseButton
import cz.medsy.ui.components.MorphingProgressRing
import cz.medsy.ui.prescriptions.describePrescription
import cz.medsy.ui.theme.BentoLarge
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun DashboardRoute(
    onOpenBloodwork: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenPrescriptions: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    state.pendingLimitWarning?.let { warning ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissLimitWarning,
            title = { Text(stringResource(R.string.dashboard_over_limit)) },
            text = {
                Column {
                    Text(warning.message.asText())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dashboard_hold_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::confirmOverLimit) {
                    Text(stringResource(R.string.dashboard_take_anyway))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::dismissLimitWarning) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    DashboardScreen(
        state = state,
        onLogDose = viewModel::logDose,
        onSnooze = viewModel::snooze,
        onRemovePatch = viewModel::markPatchRemoved,
        onHoldTick = viewModel::tickHold,
        onDismissHold = viewModel::dismissHold,
        onOpenBloodwork = onOpenBloodwork,
        onOpenInventory = onOpenInventory,
        onOpenPrescriptions = onOpenPrescriptions,
    )
}

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onLogDose: (DashboardDose, DoseStatus, cz.medsy.domain.model.ApplicationSite?) -> Unit,
    onSnooze: (DashboardDose) -> Unit,
    onRemovePatch: () -> Unit = {},
    onHoldTick: (Instant) -> Unit = {},
    onDismissHold: () -> Unit = {},
    onOpenBloodwork: () -> Unit,
    onOpenInventory: () -> Unit,
    onOpenPrescriptions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Driven here, outside the lazy item scope: recomputing from the start instant keeps
    // the countdown correct when the screen was off.
    LaunchedEffect(state.activeHold?.startedAt, state.activeHold?.isFinished) {
        val hold = state.activeHold ?: return@LaunchedEffect
        while (!hold.isFinished) {
            delay(1000)
            onHoldTick(Instant.now())
        }
    }

    // A dose that needs a site is not logged on the button press: the patient confirms
    // the site first, because the map is the whole point of the rotation tracker.
    var pendingSiteDose by remember { mutableStateOf<DashboardDose?>(null) }
    var pickedSite by remember { mutableStateOf<ApplicationSite?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                Text(
                    text = state.todayLabel.ifBlank { state.greeting?.asText().orEmpty() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.greeting?.asText() ?: stringResource(R.string.dashboard_greeting),
                    style = MaterialTheme.typography.displayMediumEmphasized,
                )
            }
        }

        state.activeHold?.let { hold ->
            item {
                Surface(
                    shape = BentoMedium,
                    color = if (hold.isFinished) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MorphingProgressRing(
                            progress = hold.progress,
                            size = 52.dp,
                            strokeWidth = 6.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (hold.isFinished) {
                                    stringResource(R.string.dashboard_hold_done)
                                } else {
                                    stringResource(R.string.dashboard_hold_keep)
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (state.discreetMode) {
                                    stringResource(R.string.dashboard_sublingual_discreet)
                                } else {
                                    hold.medicationName
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (hold.isFinished) "Done" else hold.label,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        if (hold.isFinished) {
                            androidx.compose.material3.TextButton(onClick = onDismissHold) {
                                Text(stringResource(R.string.dashboard_dismiss))
                            }
                        }
                    }
                }
            }
        }

        state.activePatch?.let { patch ->
            item {
                Surface(
                    shape = BentoMedium,
                    color = if (patch.askForRemoval) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.discreetMode) "Patch" else patch.medicationName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = patch.description.asText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (patch.askForRemoval) {
                            androidx.compose.material3.TextButton(onClick = onRemovePatch) {
                                Text(stringResource(R.string.dashboard_patch_removed))
                            }
                        }
                    }
                }
            }
        }

        item {
            NextDoseBento(state = state)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AdherenceBento(
                    percent = state.adherencePercent,
                    taken = state.adherenceTaken,
                    total = state.adherenceTotal,
                    modifier = Modifier.weight(1f),
                )
                LabBento(
                    lastTest = state.lastBloodTest,
                    onClick = onOpenBloodwork,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.stockAlerts.isNotEmpty()) {
            item {
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Inventory2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_stock_alert),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        state.stockAlerts.take(3).forEach { line ->
                            // In discreet mode the name is replaced: a stock alert is just
                            // as revealing as the medication list itself.
                            val label = if (state.discreetMode) {
                                cz.medsy.domain.discreet.DiscreetDisplay.MASKED_NAME
                            } else {
                                line.medicationName
                            }
                            val message = when {
                                line.beyondUsePassed.isNotEmpty() ->
                                    stringResource(R.string.dashboard_stock_bud, label)
                                line.isLow -> stringResource(
                                    R.string.dashboard_stock_low,
                                    label,
                                    line.daysRemaining,
                                )
                                else -> stringResource(R.string.dashboard_stock_expires, label)
                            }
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = onOpenInventory) { Text(stringResource(R.string.dashboard_open_inventory)) }
                    }
                }
            }
        }

        if (state.expiringPrescriptions.isNotEmpty()) {
            item {
                // Only prescriptions inside the warning window get a dashboard card; a whole
                // queue is inventory, not an alert.
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = stringResource(R.string.dashboard_rx_soon),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        state.expiringPrescriptions.take(2).forEach { prescription ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.dashboard_rx_line,
                                    prescription.code,
                                    describePrescription(prescription, Instant.now()),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(onClick = onOpenPrescriptions) {
                            Text(stringResource(R.string.dashboard_open_prescriptions))
                        }
                    }
                }
            }
        }

        state.uvWarning?.let { warning ->
            item {
                // Only both conditions together are worth a card: a high UV index alone is
                // just weather, and a photosensitising drug alone says nothing about today.
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.WbSunny,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            // Discreet mode hides which medication is the photosensitiser,
                            // because a trade name on the unlock screen leaks the diagnosis.
                            text = if (state.discreetMode) {
                                stringResource(R.string.msg_uv_exposure, warning.uvIndex)
                            } else {
                                warning.message.asText()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        if (state.interactionAlerts.isNotEmpty()) {
            item {
                // Show every distinct alert, most severe first. Rendering only the first
                // one hid a SEVERE alert behind a MINOR one whenever both applied.
                Card(
                    shape = BentoMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.interactionAlerts.any {
                                it.severity == cz.medsy.domain.model.InteractionSeverity.SEVERE
                            }
                        ) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = stringResource(R.string.dashboard_interactions),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        state.interactionAlerts.forEach { alert ->
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = alert.severity.labelPrefix() + alert.title.asText(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Text(
                                text = alert.detail.asText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.dashboard_greeting),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (state.dosesToday.isEmpty()) {
            item {
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_nothing_scheduled),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }

        items(state.dosesToday, key = { it.occurrence.scheduleId.toString() + it.occurrence.dueAt.toEpochMilli() }) { dose ->
            val choices = state.siteChoicesFor(dose.occurrence.medicationId)
            DoseRow(
                dose = dose,
                discreet = state.discreetMode,
                onTake = {
                    if (choices != null && choices.states.isNotEmpty()) {
                        pickedSite = choices.recommended
                        pendingSiteDose = dose
                    } else {
                        onLogDose(dose, statusFor(dose), null)
                    }
                },
                onSkip = { onLogDose(dose, DoseStatus.SKIPPED, null) },
                onSnooze = { onSnooze(dose) },
            )
        }
    }

    val dialogDose = pendingSiteDose
    val dialogChoices = dialogDose?.let { state.siteChoicesFor(it.occurrence.medicationId) }
    if (dialogDose != null && dialogChoices != null) {
        DoseSiteDialog(
            dose = dialogDose,
            choices = dialogChoices,
            discreet = state.discreetMode,
            selected = pickedSite,
            onSelect = { pickedSite = it },
            onConfirm = {
                onLogDose(dialogDose, statusFor(dialogDose), pickedSite)
                pendingSiteDose = null
            },
            onDismiss = { pendingSiteDose = null },
        )
    }
}

private fun statusFor(dose: DashboardDose): DoseStatus =
    if (dose.isDueNow) DoseStatus.LATE else DoseStatus.TAKEN

/**
 * Confirmation step for a dose that goes into the skin. Shows the map with the last-use
 * history, pre-selects the rotation suggestion and lets the patient override it.
 */
@Composable
private fun DoseSiteDialog(
    dose: DashboardDose,
    choices: SiteChoices,
    discreet: Boolean,
    selected: ApplicationSite?,
    onSelect: (ApplicationSite) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = BentoLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_log_dose_button),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (discreet) "Scheduled dose" else dose.medicationName,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = buildString {
                        append(dose.occurrence.doseAmount.stripTrailingZeros().toPlainString())
                        append(' ')
                        append(dose.occurrence.doseUnit)
                        append(" · ")
                        append(
                            dose.occurrence.dueAt.atZone(ZoneId.systemDefault())
                                .toLocalTime()
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(14.dp))
                BodyMapPicker(
                    states = choices.states,
                    selected = selected,
                    recommended = choices.recommended,
                    onSelect = onSelect,
                    canvasHeight = 240.dp,
                )

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.dashboard_log_dose))
                    }
                }
            }
        }
    }
}

@Composable
private fun NextDoseBento(state: DashboardUiState) {
    val next = state.nextDose
    Surface(
        shape = BentoLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = stringResource(R.string.dashboard_next_dose_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(6.dp))
            if (next == null) {
                Text(
                    text = stringResource(R.string.dashboard_all_done),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.dashboard_no_doses_left),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            } else {
                val countdown = state.timeUntilNext?.let(::formatCountdown) ?: "now"
                Text(
                    text = countdown,
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.dashboard_until_next),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MedicationIcon(
                        form = next.form,
                        size = 40.dp,
                        container = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.discreetMode) "Scheduled dose" else next.medicationName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(next.occurrence.doseAmount.stripTrailingZeros().toPlainString())
                                append(' ')
                                append(next.occurrence.doseUnit)
                                append(" · ")
                                append(
                                    next.occurrence.dueAt.atZone(ZoneId.systemDefault())
                                        .toLocalTime()
                                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdherenceBento(percent: Int, taken: Int, total: Int, modifier: Modifier = Modifier) {
    Surface(
        shape = BentoMedium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MorphingProgressRing(progress = percent / 100f, size = 54.dp, strokeWidth = 7.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_adherence),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.dashboard_doses_taken,
                    total,
                    taken,
                    total,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabBento(lastTest: Instant?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = BentoMedium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Icon(
                Icons.Rounded.Bloodtype,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.nav_bloodwork),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = lastTest?.let {
                    it.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                } ?: stringResource(R.string.bloodwork_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun DoseRow(
    dose: DashboardDose,
    discreet: Boolean,
    onTake: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
) {
    val taken = dose.status == DoseStatus.TAKEN || dose.status == DoseStatus.LATE
    Surface(
        shape = BentoSmall,
        color = if (dose.isDueNow) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MedicationIcon(
                form = dose.form,
                size = 38.dp,
                container = if (taken) {
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                },
                content = if (taken) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (discreet) "Scheduled dose" else dose.medicationName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(dose.occurrence.doseAmount.stripTrailingZeros().toPlainString())
                        append(' ')
                        append(dose.occurrence.doseUnit)
                        append(" · ")
                        append(
                            dose.occurrence.dueAt.atZone(ZoneId.systemDefault())
                                .toLocalTime()
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                taken -> StatusPill("Taken", MaterialTheme.colorScheme.tertiaryContainer)
                dose.status == DoseStatus.SKIPPED -> StatusPill("Skipped", MaterialTheme.colorScheme.surfaceVariant)
                dose.status == DoseStatus.SNOOZED -> {
                    TextButton(onClick = onSnooze) { Text(stringResource(R.string.dose_snoozed)) }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onSkip) { Text(stringResource(R.string.dose_skip)) }
                        Spacer(Modifier.width(6.dp))
                        MorphingDoseButton(
                            form = dose.form,
                            confirmed = false,
                            onClick = onTake,
                            size = 48.dp,
                            contentDescription = stringResource(R.string.dashboard_log_dose),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun cz.medsy.domain.model.InteractionSeverity.labelPrefix(): String = stringResource(
    R.string.severity_prefix,
    stringResource(
        when (this) {
            cz.medsy.domain.model.InteractionSeverity.SEVERE -> R.string.severity_severe
            cz.medsy.domain.model.InteractionSeverity.MODERATE -> R.string.severity_moderate
            cz.medsy.domain.model.InteractionSeverity.MINOR -> R.string.severity_minor
        },
    ),
)

internal fun formatCountdown(duration: java.time.Duration): String {
    if (duration.isNegative) return "now"
    val totalHours = duration.toHours()
    val days = totalHours / 24
    return when {
        days > 0 -> "$days days"
        totalHours > 0 -> "${totalHours}h ${duration.toMinutes() % 60}m"
        else -> "${duration.toMinutes()} min"
    }
}

@Preview(name = "Dashboard light")
@Preview(name = "Dashboard dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DashboardPreview() {
    val occurrence = Occurrence(
        scheduleId = 1,
        medicationId = 1,
        dueAt = Instant.now().plusSeconds(3600 * 30),
        localDate = LocalDate.now(),
        localTime = LocalTime.of(9, 0),
        doseAmount = java.math.BigDecimal("5"),
        doseUnit = "mg",
    )
    MedsyTheme {
        DashboardScreen(
            state = DashboardUiState(
                greeting = message(MessageKey.DASHBOARD_GREETING_MORNING),
                todayLabel = "Tuesday 11. November",
                nextDose = DashboardDose(occurrence, "Estradiol Enanthate", MedicationForm.INJECTION_IM, null, false, null),
                timeUntilNext = java.time.Duration.ofHours(30),
                dosesToday = listOf(
                    DashboardDose(occurrence, "Estradiol Enanthate", MedicationForm.INJECTION_IM, null, false, null),
                    DashboardDose(
                        occurrence.copy(doseUnit = "tbl"),
                        "Progesterone",
                        MedicationForm.TABLET,
                        DoseStatus.TAKEN,
                        false,
                        null,
                    ),
                ),
                adherencePercent = 94,
                adherenceTaken = 28,
                adherenceTotal = 30,
                isLoading = false,
            ),
            onLogDose = { _, _, _ -> },
            onSnooze = {},
            onOpenBloodwork = {},
            onOpenInventory = {},
        )
    }
}

