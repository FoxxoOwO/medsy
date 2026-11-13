package cz.medsy.ui.schedule

import cz.medsy.ui.message.asText

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.model.MealRelation
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.domain.model.ScheduleType
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun ScheduleEditorRoute(
    onFinished: () -> Unit,
    viewModel: ScheduleEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) {
        if (state.saved) onFinished()
    }

    ScheduleEditorScreen(
        state = state,
        onType = viewModel::setType,
        onDose = viewModel::setDose,
        onUnit = viewModel::setUnit,
        onInterval = viewModel::setInterval,
        onMealRelation = viewModel::setMealRelation,
        onOffset = viewModel::setOffsetMinutes,
        onRequireSite = viewModel::setRequireSiteRotation,
        onActive = viewModel::setActive,
        onMaxDaily = viewModel::setMaxDailyDose,
        onActuations = viewModel::setActuationsPerDose,
        onHoldMinutes = viewModel::setHoldMinutes,
        onNote = viewModel::setNote,
        onAddTime = viewModel::addTime,
        onRemoveTime = viewModel::removeTime,
        onAddTaperStep = viewModel::addTaperStep,
        onUpdateTaperStep = viewModel::updateTaperStep,
        onRemoveTaperStep = viewModel::removeTaperStep,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onCancel = onFinished,
    )
}

@Composable
fun ScheduleEditorScreen(
    state: ScheduleEditorUiState,
    onType: (ScheduleType) -> Unit,
    onDose: (String) -> Unit,
    onUnit: (String) -> Unit,
    onInterval: (String) -> Unit,
    onMealRelation: (MealRelation) -> Unit,
    onOffset: (Int) -> Unit,
    onRequireSite: (Boolean) -> Unit,
    onActive: (Boolean) -> Unit,
    onMaxDaily: (String) -> Unit,
    onActuations: (String) -> Unit = {},
    onHoldMinutes: (String) -> Unit = {},
    onNote: (String) -> Unit,
    onAddTime: (LocalTime) -> Unit,
    onRemoveTime: (LocalTime) -> Unit,
    onAddTaperStep: () -> Unit,
    onUpdateTaperStep: (Int, String, String) -> Unit,
    onRemoveTaperStep: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    var showTimePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = if (state.isEditing) stringResource(R.string.schedule_title_edit) else stringResource(R.string.schedule_title_new),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                state.medication?.let { medication ->
                    val discreet = cz.medsy.ui.theme.LocalDiscreetMode.current
                    Text(
                        text = if (discreet) {
                            cz.medsy.domain.discreet.DiscreetDisplay.MASKED_NAME
                        } else {
                            medication.name
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.errors.isNotEmpty()) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        state.errors.forEach { error ->
                            Text(
                                text = "• ${error.asText()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }

        if (draft == null) {
            item {
                Text(
                    text = stringResource(R.string.common_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@LazyColumn
        }

        item {
            Section(title = stringResource(R.string.schedule_section_how_often)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScheduleType.entries.forEach { type ->
                        FilterChip(
                            selected = draft.type == type,
                            onClick = { onType(type) },
                            label = { Text(type.label().asText()) },
                        )
                    }
                }
            }
        }

        if (draft.type == ScheduleType.INTERVAL_DAYS || draft.type == ScheduleType.CYCLICAL) {
            item {
                OutlinedTextField(
                    value = draft.intervalValue,
                    onValueChange = onInterval,
                    label = {
                        Text(
                            if (draft.type == ScheduleType.CYCLICAL) {
                                stringResource(R.string.schedule_interval_cyclical)
                            } else {
                                stringResource(R.string.schedule_interval_every)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (draft.type == ScheduleType.INTERVAL_HOURS) {
            item {
                OutlinedTextField(
                    value = draft.intervalValue,
                    onValueChange = onInterval,
                    label = { Text(stringResource(R.string.schedule_every_n_hours)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (draft.type != ScheduleType.INTERVAL_HOURS && draft.type != ScheduleType.INTERVAL_DAYS) {
            item {
                Section(title = stringResource(R.string.schedule_section_times)) {
                    if (draft.times.isEmpty()) {
                        Text(
                            text = stringResource(R.string.schedule_no_time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draft.times.forEach { time ->
                            Surface(
                                shape = BentoSmall,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                                ) {
                                    Text(
                                        text = time.format(HOUR_MINUTE),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    IconButton(onClick = { onRemoveTime(time) }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(R.string.schedule_remove_time, time),
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.schedule_add_time))
                        }
                    }
                }
            }
        }

        item {
            Section(title = "Dose") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft.doseAmount,
                        onValueChange = onDose,
                        label = { Text(stringResource(R.string.schedule_amount)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.doseUnit,
                        onValueChange = onUnit,
                        label = { Text(stringResource(R.string.batch_unit)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (draft.type == ScheduleType.TAPERING) {
            item {
                Section(title = stringResource(R.string.schedule_section_taper)) {
                    draft.taperSteps.forEachIndexed { index, step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = step.dose,
                                onValueChange = { onUpdateTaperStep(index, it, step.durationDays) },
                                label = { Text(stringResource(R.string.schedule_dose)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = step.durationDays,
                                onValueChange = { onUpdateTaperStep(index, step.dose, it) },
                                label = { Text(stringResource(R.string.schedule_days)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onRemoveTaperStep(index) }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.schedule_remove_step))
                            }
                        }
                    }
                    TextButton(onClick = onAddTaperStep) { Text(stringResource(R.string.schedule_add_step)) }
                }
            }
        }

        if (state.medication?.form?.let {
                cz.medsy.domain.calc.MeteredDose.isMeteredDose(it)
            } == true
        ) {
            item {
                OutlinedTextField(
                    value = draft.actuationsPerDose,
                    onValueChange = onActuations,
                    label = { Text(stringResource(R.string.schedule_pumps)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.medication?.form == cz.medsy.domain.model.MedicationForm.SUBLINGUAL) {
            item {
                OutlinedTextField(
                    value = draft.holdMinutes,
                    onValueChange = onHoldMinutes,
                    label = { Text(stringResource(R.string.schedule_hold_minutes)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (draft.type == ScheduleType.PRN) {
            item {
                OutlinedTextField(
                    value = draft.maxDailyDose,
                    onValueChange = onMaxDaily,
                    label = { Text(stringResource(R.string.schedule_max_per_day)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Section(title = stringResource(R.string.schedule_section_food)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MealRelation.entries.forEach { relation ->
                        FilterChip(
                            selected = draft.mealRelation == relation,
                            onClick = { onMealRelation(relation) },
                            label = { Text(relation.label().asText()) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.schedule_shift_reminder, draft.offsetMinutes),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OFFSET_OPTIONS.forEach { minutes ->
                        FilterChip(
                            selected = draft.offsetMinutes == minutes,
                            onClick = { onOffset(minutes) },
                            label = {
                                Text(
                                    if (minutes == 0) stringResource(R.string.common_none)
                                    else stringResource(R.string.schedule_plus_min, minutes),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (state.medication?.form?.requiresSite == true) {
            item {
                Section(title = stringResource(R.string.schedule_section_application)) {
                    ToggleRow(
                        title = stringResource(R.string.schedule_rotate_site),
                        subtitle = stringResource(R.string.schedule_rotate_site_desc),
                        checked = draft.requireSiteRotation,
                        onChange = onRequireSite,
                    )
                }
            }
        }

        item {
            Section(title = stringResource(R.string.schedule_section_reminder)) {
                ToggleRow(
                    title = stringResource(R.string.schedule_active),
                    subtitle = stringResource(R.string.schedule_active_desc),
                    checked = draft.isActive,
                    onChange = onActive,
                )
            }
        }

        item {
            OutlinedTextField(
                value = draft.note,
                onValueChange = onNote,
                label = { Text(stringResource(R.string.schedule_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave, enabled = !state.isSaving) {
                    Text(if (state.isEditing) stringResource(R.string.schedule_save_changes) else stringResource(R.string.schedule_create))
                }
                Button(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                if (state.isEditing) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            onDismiss = { showTimePicker = false },
            onConfirm = { time ->
                onAddTime(time)
                showTimePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(R.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        text = { TimePicker(state = pickerState) },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = BentoMedium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

private val OFFSET_OPTIONS = listOf(0, 15, 30, 60, 120)

internal fun ScheduleType.label(): Message = when (this) {
    ScheduleType.FIXED_DAILY -> message(MessageKey.SCHEDULE_TYPE_FIXED_DAILY)
    ScheduleType.INTERVAL_HOURS -> message(MessageKey.SCHEDULE_TYPE_INTERVAL_HOURS)
    ScheduleType.INTERVAL_DAYS -> message(MessageKey.SCHEDULE_TYPE_INTERVAL_DAYS)
    ScheduleType.CYCLICAL -> message(MessageKey.SCHEDULE_TYPE_CYCLICAL)
    ScheduleType.TAPERING -> message(MessageKey.SCHEDULE_TYPE_TAPERING)
    ScheduleType.PRN -> message(MessageKey.SCHEDULE_TYPE_PRN)
}

internal fun MealRelation.label(): Message = when (this) {
    MealRelation.NONE -> message(MessageKey.MEAL_RELATION_NONE)
    MealRelation.FASTING_BEFORE -> message(MessageKey.MEAL_RELATION_FASTING_BEFORE)
    MealRelation.WITH_MEAL -> message(MessageKey.MEAL_RELATION_WITH_MEAL)
    MealRelation.AFTER_MEAL -> message(MessageKey.MEAL_RELATION_AFTER_MEAL)
}

@Preview(name = "Schedule editor light")
@Preview(name = "Schedule editor dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleEditorPreview() {
    MedsyTheme {
        ScheduleEditorScreen(
            state = ScheduleEditorUiState(
                medication = Medication(
                    id = 1,
                    name = "Estradiol Enanthate",
                    form = MedicationForm.INJECTION_IM,
                ),
                draft = ScheduleDraft(
                    medicationId = 1,
                    type = ScheduleType.INTERVAL_DAYS,
                    intervalValue = "3.5",
                    doseAmount = "5",
                    doseUnit = "mg",
                    requireSiteRotation = true,
                ),
                isEditing = false,
            ),
            onType = {},
            onDose = {},
            onUnit = {},
            onInterval = {},
            onMealRelation = {},
            onOffset = {},
            onRequireSite = {},
            onActive = {},
            onMaxDaily = {},
            onActuations = {},
            onHoldMinutes = {},
            onNote = {},
            onAddTime = {},
            onRemoveTime = {},
            onAddTaperStep = {},
            onUpdateTaperStep = { _, _, _ -> },
            onRemoveTaperStep = {},
            onSave = {},
            onDelete = {},
            onCancel = {},
        )
    }
}
