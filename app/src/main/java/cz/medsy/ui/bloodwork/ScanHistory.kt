package cz.medsy.ui.bloodwork

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.domain.model.LabImportSource
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val historyDate = DateTimeFormatter.ofPattern("d. M. yyyy")

internal fun formatHistoryDate(timestamp: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    timestamp.atZone(zone).format(historyDate)

/**
 * The whole edit-and-delete intent in one object. Eleven callbacks threaded through the
 * screen signature would describe the same thing less clearly.
 */
interface ScanHistoryActions {
    fun edit(sessionId: Long)
    fun delete(sessionId: Long)
    fun draftDate(timestamp: Instant)
    fun draftLabName(name: String)
    fun draftFasting(fasting: Boolean)
    fun draftValue(id: Long, text: String)
    fun draftRemoveValue(id: Long)
    fun save()
    fun cancelEdit()
    fun confirmDelete()
    fun cancelDelete()
}

/** Preview and default: the history renders, and nothing it triggers has anywhere to go. */
object NoScanHistoryActions : ScanHistoryActions {
    override fun edit(sessionId: Long) = Unit
    override fun delete(sessionId: Long) = Unit
    override fun draftDate(timestamp: Instant) = Unit
    override fun draftLabName(name: String) = Unit
    override fun draftFasting(fasting: Boolean) = Unit
    override fun draftValue(id: Long, text: String) = Unit
    override fun draftRemoveValue(id: Long) = Unit
    override fun save() = Unit
    override fun cancelEdit() = Unit
    override fun confirmDelete() = Unit
    override fun cancelDelete() = Unit
}

/**
 * Every imported report, newest first. One import is one draw, so this list is the session
 * list with the value count attached — the same rows the chart is drawn from, which is why
 * deleting here removes the chart point too.
 */
@Composable
fun ScanHistorySection(
    history: List<ScanHistoryRow>,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.labs_history_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.labs_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            history.forEach { row ->
                ScanHistoryCard(row = row, onEdit = onEdit, onDelete = onDelete)
            }
        }
    }
}

@Composable
private fun ScanHistoryCard(
    row: ScanHistoryRow,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Surface(
        shape = BentoSmall,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatHistoryDate(row.timestamp),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    // The lab name is the one thing the user typed, so it leads when present.
                    text = row.labName?.takeIf { it.isNotBlank() }
                        ?: stringResource(sourceLabel(row.source)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.labs_history_values,
                        row.valueCount,
                        row.valueCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onEdit(row.sessionId) }) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.common_edit))
            }
            IconButton(onClick = { onDelete(row.sessionId) }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete))
            }
        }
    }
}

/** String resource for how the report arrived; the history card and the editor share it. */
internal fun sourceLabel(source: LabImportSource): Int = when (source) {
    LabImportSource.PDF_TEXT -> R.string.labs_history_source_pdf
    LabImportSource.OCR_IMAGE -> R.string.labs_history_source_photo
    LabImportSource.MANUAL -> R.string.labs_history_source_manual
}

/**
 * Corrections after the fact: the date, the lab, fasting, and each printed number. A row
 * cleared to empty is removed rather than saved as blank, so deleting a single value does
 * not need its own dialog.
 */
@Composable
fun ScanEditDialog(
    draft: ScanDraft,
    onTimestamp: (Instant) -> Unit,
    onLabName: (String) -> Unit,
    onFasting: (Boolean) -> Unit,
    onValue: (Long, String) -> Unit,
    onRemoveValue: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labs_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.labs_edit_date),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickingDate = true }) {
                        Text(formatHistoryDate(draft.timestamp))
                    }
                }
                OutlinedTextField(
                    value = draft.labName,
                    onValueChange = onLabName,
                    label = { Text(stringResource(R.string.labs_edit_lab)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.labs_edit_fasting),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = draft.fasting, onCheckedChange = onFasting)
                }
                Text(
                    text = stringResource(R.string.labs_edit_values),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (draft.values.isEmpty()) {
                    Text(
                        text = stringResource(R.string.labs_edit_no_values),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                draft.values.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedTextField(
                            value = row.text,
                            onValueChange = { onValue(row.id, it) },
                            label = { Text(row.displayName) },
                            suffix = { Text(row.unit) },
                            isError = row.invalid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            supportingText = if (row.invalid) {
                                { Text(stringResource(R.string.labs_edit_value_invalid)) }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemoveValue(row.id) }) {
                            Icon(
                                Icons.Rounded.RemoveCircleOutline,
                                contentDescription = stringResource(R.string.labs_edit_remove_value),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !draft.hasInvalidValues) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )

    if (pickingDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = draft.timestamp.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { millis ->
                            onTimestamp(mergePickedDate(draft.timestamp, millis))
                        }
                        pickingDate = false
                    },
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = picker)
        }
    }
}

/**
 * The picker speaks UTC midnight; the stored draw keeps its local time of day, so moving a
 * draw from the 3rd to the 5th does not silently turn a 07:40 venipuncture into 02:00.
 */
internal fun mergePickedDate(
    original: Instant,
    pickedMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Instant {
    val date = Instant.ofEpochMilli(pickedMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val time = original.atZone(zone).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant()
}

@Composable
fun ScanDeleteDialog(
    row: ScanHistoryRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labs_history_delete_title)) },
        text = {
            Text(
                stringResource(
                    R.string.labs_history_delete_body,
                    formatHistoryDate(row.timestamp),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Preview(name = "Scan history")
@Composable
private fun ScanHistoryPreview() {
    MedsyTheme {
        ScanHistorySection(
            history = listOf(
                ScanHistoryRow(1, Instant.now(), "Fingerland", LabImportSource.PDF_TEXT, 12),
                ScanHistoryRow(2, Instant.now().minusSeconds(86400L * 40), null, LabImportSource.OCR_IMAGE, 0),
            ),
            onEdit = {},
            onDelete = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Scan editor")
@Composable
private fun ScanEditPreview() {
    MedsyTheme {
        ScanEditDialog(
            draft = ScanDraft(
                sessionId = 1,
                timestamp = Instant.now(),
                labName = "Fingerland",
                fasting = true,
                values = listOf(ScanValueDraft(1, "E2", "Estradiol", "420", "pg/mL")),
            ),
            onTimestamp = {},
            onLabName = {},
            onFasting = {},
            onValue = { _, _ -> },
            onRemoveValue = {},
            onSave = {},
            onDismiss = {},
        )
    }
}
