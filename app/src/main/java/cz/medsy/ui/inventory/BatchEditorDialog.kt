package cz.medsy.ui.inventory

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cz.medsy.domain.inventory.BatchDraft
import cz.medsy.domain.inventory.BatchValidation
import cz.medsy.ui.message.asText
import cz.medsy.domain.model.Medication
import cz.medsy.ui.message.defaultUnitText
import cz.medsy.ui.theme.BentoLarge
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import androidx.compose.ui.res.stringResource
import cz.medsy.R

/**
 * Manual batch entry. Without this, a vial bought without its box (or a box whose code is
 * not in the database) can never reach the shelf, and the stock screen quietly understates
 * what the user owns.
 */
@Composable
fun BatchEditorDialog(
    medications: List<Medication>,
    onValidate: (BatchDraft, Medication) -> BatchValidation,
    onSave: (BatchDraft, Medication) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(medications.firstOrNull()?.id ?: 0L) }
    val selected = medications.firstOrNull { it.id == selectedId }
    var quantity by remember { mutableStateOf("1") }
    // Resolved before `remember`: a resource lookup is a composable call, an initial value is not.
    val units = medications.associate { it.id to defaultUnitText(it.form) }
    val initialUnit = selected?.let { units[it.id] }.orEmpty()
    var unit by remember { mutableStateOf(initialUnit) }
    var expiry by remember { mutableStateOf("") }
    var batchNumber by remember { mutableStateOf("") }
    var deadSpace by remember { mutableStateOf("") }
    var threshold by remember { mutableStateOf("7") }
    var beyondUse by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<BatchValidation?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val parsedQuantity = quantity.trim().replace(',', '.').toBigDecimalOrNull()
    val parsedExpiry = expiry.toLocalDateOrNull()
    val expiryInvalid = expiry.isNotBlank() && parsedExpiry == null

    fun draft(): BatchDraft = BatchDraft(
        medicationId = selectedId,
        quantity = parsedQuantity ?: BigDecimal.ZERO,
        unit = unit,
        expirationDate = parsedExpiry,
        batchNumber = batchNumber,
        deadSpaceMlPerDose = deadSpace.trim().replace(',', '.').toBigDecimalOrNull(),
        beyondUseDaysLimit = beyondUse.trim().toIntOrNull(),
        lowStockThresholdDays = threshold.trim().toIntOrNull() ?: 7,
    )

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
                Text(stringResource(R.string.batch_add), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.batch_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    medications.forEach { medication ->
                        FilterChip(
                            selected = medication.id == selectedId,
                            onClick = {
                                selectedId = medication.id
                                // A click lambda is not composable, so the text is resolved here.
                                unit = units.getValue(medication.id)
                                feedback = null
                            },
                            label = { Text(medication.name) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it; feedback = null },
                        label = { Text(stringResource(R.string.batch_quantity)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it; feedback = null },
                        label = { Text(stringResource(R.string.batch_unit)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it; feedback = null },
                    label = { Text(stringResource(R.string.batch_expiry_label)) },
                    singleLine = true,
                    isError = expiryInvalid,
                    supportingText = if (expiryInvalid) {
                        { Text(stringResource(R.string.batch_expiry_error)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = batchNumber,
                    onValueChange = { batchNumber = it },
                    label = { Text(stringResource(R.string.batch_number_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { threshold = it; feedback = null },
                        label = { Text(stringResource(R.string.batch_low_stock_days)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = beyondUse,
                        onValueChange = { beyondUse = it; feedback = null },
                        label = { Text(stringResource(R.string.batch_beyond_use)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (selected?.form?.isInjectable == true) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deadSpace,
                        onValueChange = { deadSpace = it; feedback = null },
                        label = { Text(stringResource(R.string.batch_dead_space)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val shown = feedback
                if (shown != null) {
                    Spacer(Modifier.height(8.dp))
                    shown.errors.forEach { error ->
                        Text(
                            text = error.asText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    shown.warnings.forEach { warning ->
                        Text(
                            text = warning.asText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                localError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(
                        onClick = {
                            if (selected == null) {
                                localError = "Choose a medication."
                                return@TextButton
                            }
                            if (expiryInvalid) {
                                localError = "Use the format yyyy-MM-dd for the expiry date."
                                return@TextButton
                            }
                            localError = null
                            val validation = onValidate(draft(), selected)
                            if (validation.isValid) {
                                onSave(draft(), selected)
                                onDismiss()
                            } else {
                                feedback = validation
                            }
                        },
                    ) {
                        Text(stringResource(R.string.batch_save))
                    }
                }
            }
        }
    }
}

private fun String.toLocalDateOrNull(): LocalDate? =
    trim().takeIf { it.isNotEmpty() }?.let { text ->
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }
