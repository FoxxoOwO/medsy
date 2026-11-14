package cz.medsy.ui.medications

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.R
import cz.medsy.domain.model.InventoryBatch
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.Schedule
import cz.medsy.ui.components.MedicationIcon
import cz.medsy.ui.message.asText
import cz.medsy.ui.scanner.shortLabelRes
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.LocalDiscreetMode
import cz.medsy.ui.theme.MedsyTheme
import cz.medsy.ui.inventory.caption
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Detail of one medication. The list row opens it, and from here the medication can be edited,
 * archived or deleted, plus its schedules and stock reviewed without leaving the screen.
 */
@Composable
fun MedicationDetailRoute(
    onBack: () -> Unit,
    onEditSchedule: (Long, Long) -> Unit = { _, _ -> },
    onNewSchedule: (Long) -> Unit = {},
    viewModel: MedicationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The row is only really gone once the database says so, which is why the ViewModel reports it.
    LaunchedEffect(state.gone) {
        if (state.gone) onBack()
    }

    MedicationDetailScreen(
        state = state,
        onBack = onBack,
        onSave = viewModel::save,
        onArchive = viewModel::archive,
        onDelete = viewModel::delete,
        onSetScheduleActive = viewModel::setScheduleActive,
        onDeleteSchedule = viewModel::deleteSchedule,
        onEditSchedule = onEditSchedule,
        onNewSchedule = { state.medication?.let { onNewSchedule(it.id) } },
    )
}

@Composable
fun MedicationDetailScreen(
    state: MedicationDetailUi,
    onBack: () -> Unit = {},
    onSave: (Medication, MedicationEdit) -> Unit = { _, _ -> },
    onArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSetScheduleActive: (Schedule, Boolean) -> Unit = { _, _ -> },
    onDeleteSchedule: (Schedule) -> Unit = {},
    onEditSchedule: (Long, Long) -> Unit = { _, _ -> },
    onNewSchedule: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val medication = state.medication
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (medication != null && editing) {
        MedicationEditDialog(
            medication = medication,
            onDismiss = { editing = false },
            onSave = { edit ->
                onSave(medication, edit)
                editing = false
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.meds_detail_delete_title)) },
            text = { Text(stringResource(R.string.meds_detail_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = medication?.name ?: stringResource(R.string.meds_detail_title),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            }
        }

        if (medication == null) {
            item {
                Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@LazyColumn
        }

        item { SummaryCard(medication) }

        if (state.alerts.isNotEmpty()) {
            item { AlertCard(alerts = state.alerts.map { it.title.asText() to it.detail.asText() }) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { editing = true }) {
                    Text(stringResource(R.string.common_edit))
                }
                TextButton(onClick = onArchive) { Text(stringResource(R.string.meds_detail_archive)) }
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        medication.leafletUrl?.let { url ->
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { context.open(url) }) {
                        Text(stringResource(R.string.meds_detail_leaflet))
                    }
                }
            }
        }

        item {
            SideEffectCard(profile = state.sideEffects)
        }

        item {
            Column {
                SectionTitle(stringResource(R.string.meds_detail_schedules))
                if (state.schedules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.meds_no_schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        items(state.schedules, key = { it.id }) { schedule ->
            ScheduleRow(
                schedule = schedule,
                onToggle = { onSetScheduleActive(schedule, !schedule.isActive) },
                onEdit = { onEditSchedule(schedule.medicationId, schedule.id) },
                onDelete = { onDeleteSchedule(schedule) },
            )
        }

        item {
            TextButton(onClick = onNewSchedule) { Text(stringResource(R.string.meds_add_schedule)) }
        }

        item { SectionTitle(stringResource(R.string.meds_detail_stock)) }

        if (state.batches.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.meds_detail_no_stock),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.batches, key = { it.id }) { batch -> BatchRow(batch) }
        }

        medication.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item {
                Column {
                    SectionTitle(stringResource(R.string.meds_detail_notes))
                    Text(text = notes, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SummaryCard(medication: Medication) {
    val discreet = LocalDiscreetMode.current
    val display = cz.medsy.domain.discreet.DiscreetDisplay.row(medication, discreet)
    Surface(shape = BentoMedium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MedicationIcon(
                    form = medication.form,
                    size = 44.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(display.name, style = MaterialTheme.typography.titleMedium)
                    if (display.detail.isNotBlank()) {
                        Text(
                            text = display.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (display.showHrtBadge) {
                    Surface(shape = BentoSmall, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            text = stringResource(R.string.meds_group_hrt),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            DetailLine(stringResource(R.string.meds_detail_form), medication.form.label())
            medication.concentrationMgPerMl?.let { concentration ->
                DetailLine(
                    stringResource(R.string.meds_detail_concentration),
                    concentration.stripTrailingZeros().toPlainString(),
                )
            }
            medication.gtin?.let { DetailLine(stringResource(R.string.meds_detail_gtin), it) }
            medication.suklCode?.let { DetailLine(stringResource(R.string.meds_detail_sukl), it) }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AlertCard(alerts: List<Pair<String, String>>) {
    Surface(shape = BentoSmall, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            alerts.forEach { (title, detail) ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    schedule: Schedule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = BentoSmall, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Text(schedule.summaryLine(), style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onToggle) {
                    Text(
                        stringResource(
                            if (schedule.isActive) R.string.meds_detail_pause else R.string.meds_detail_resume,
                        ),
                    )
                }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.common_edit)) }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BatchRow(batch: InventoryBatch) {
    Surface(shape = BentoSmall, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(
                    R.string.inventory_usable,
                    batch.remainingQuantity.stripTrailingZeros().toPlainString(),
                    batch.unit,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            val caption = batch.caption()
            if (!caption.isEmpty) {
                // Same wording as the Stock screen, so a lot number reads identically in both.
                val expiry = caption.expirationDate?.toString()
                val lot = caption.lot
                Text(
                    text = when {
                        expiry != null && lot != null ->
                            stringResource(R.string.inventory_expires_batch, expiry, lot)
                        expiry != null -> stringResource(R.string.inventory_expires, expiry)
                        else -> stringResource(R.string.inventory_lot, lot.orEmpty())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MedicationEditDialog(
    medication: Medication,
    onDismiss: () -> Unit,
    onSave: (MedicationEdit) -> Unit,
) {
    var edit by remember(medication) { mutableStateOf(MedicationEdit.of(medication)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meds_detail_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = edit.name,
                    onValueChange = { edit = edit.copy(name = it) },
                    label = { Text(stringResource(R.string.scan_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.activeSubstance,
                    onValueChange = { edit = edit.copy(activeSubstance = it) },
                    label = { Text(stringResource(R.string.scan_active_substance)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.atcCode,
                    onValueChange = { edit = edit.copy(atcCode = it) },
                    label = { Text(stringResource(R.string.scan_atc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.strength,
                    onValueChange = { edit = edit.copy(strength = it) },
                    label = { Text(stringResource(R.string.scan_strength)) },
                    isError = edit.strengthInvalid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.concentration,
                    onValueChange = { edit = edit.copy(concentration = it) },
                    label = { Text(stringResource(R.string.meds_detail_concentration)) },
                    isError = edit.concentrationInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = edit.notes,
                    onValueChange = { edit = edit.copy(notes = it) },
                    label = { Text(stringResource(R.string.meds_detail_notes)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.scan_form), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MedicationForm.entries.take(5).forEach { form ->
                        FilterChip(
                            selected = edit.form == form,
                            onClick = { edit = edit.copy(form = form) },
                            label = { Text(form.label()) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MedicationForm.entries.drop(5).forEach { form ->
                        FilterChip(
                            selected = edit.form == form,
                            onClick = { edit = edit.copy(form = form) },
                            label = { Text(form.label()) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(edit) }, enabled = edit.isValid) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun MedicationForm.label(): String = stringResource(shortLabelRes)

private fun android.content.Context.open(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Preview(name = "Medication detail")
@Composable
private fun MedicationDetailPreview() {
    MedsyTheme {
        MedicationDetailScreen(
            state = MedicationDetailUi(
                medication = Medication(
                    id = 1,
                    name = "Estradiol Enanthate",
                    activeSubstance = "Estradiolum enanthicum",
                    atcCode = "G03CA03",
                    form = MedicationForm.INJECTION_IM,
                    strengthValue = BigDecimal("5"),
                    strengthUnit = "mg",
                    concentrationMgPerMl = BigDecimal("50"),
                    gtin = "08595000000126",
                    suklCode = "0249259",
                    notes = "Monday and Thursday.",
                    isHrt = true,
                ),
                schedules = listOf(
                    Schedule(
                        id = 1,
                        medicationId = 1,
                        scheduleType = cz.medsy.domain.model.ScheduleType.INTERVAL_DAYS,
                        intervalValue = BigDecimal("3.5"),
                        doseAmount = BigDecimal("1"),
                        doseUnit = "dose",
                        startDate = LocalDate.of(2026, 1, 5),
                    ),
                ),
                batches = listOf(
                    InventoryBatch(
                        id = 1,
                        medicationId = 1,
                        batchNumber = "LOT42",
                        expirationDate = LocalDate.of(2027, 3, 31),
                        initialQuantity = BigDecimal("5"),
                        remainingQuantity = BigDecimal("3"),
                        unit = "mL",
                    ),
                ),
            ),
        )
    }
}
