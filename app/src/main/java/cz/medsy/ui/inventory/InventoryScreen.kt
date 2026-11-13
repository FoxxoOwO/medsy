package cz.medsy.ui.inventory

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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cz.medsy.ui.message.asText
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.domain.calc.InventoryCalculator
import cz.medsy.domain.model.Consumable
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import java.math.BigDecimal
import java.time.LocalDate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun InventoryRoute(viewModel: InventoryViewModel = hiltViewModel()) {
    val lines by viewModel.stockLines.collectAsStateWithLifecycle()
    val consumables by viewModel.consumables.collectAsStateWithLifecycle()
    val input by viewModel.travelInput.collectAsStateWithLifecycle()
    val plan by viewModel.travelPlan.collectAsStateWithLifecycle()
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    InventoryScreen(
        lines = lines,
        consumables = consumables,
        travelInput = input,
        travelPlan = plan,
        medications = medications,
        onCalculateTravel = viewModel::calculateTravelPlan,
        onUpdateTravel = viewModel::updateTrip,
        onValidateBatch = viewModel::validateBatch,
        onSaveBatch = viewModel::addBatch,
    )
}

@Composable
fun InventoryScreen(
    lines: List<InventoryCalculator.StockLine>,
    consumables: List<Consumable>,
    travelInput: TravelPlanInput,
    travelPlan: InventoryCalculator.TravelPlan?,
    medications: List<cz.medsy.domain.model.Medication> = emptyList(),
    onCalculateTravel: () -> Unit,
    onUpdateTravel: (LocalDate, LocalDate, Int) -> Unit,
    onValidateBatch: (cz.medsy.domain.inventory.BatchDraft, cz.medsy.domain.model.Medication) ->
        cz.medsy.domain.inventory.BatchValidation = { _, _ ->
        cz.medsy.domain.inventory.BatchValidation()
    },
    onSaveBatch: (cz.medsy.domain.inventory.BatchDraft, cz.medsy.domain.model.Medication) -> Unit =
        { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val discreet = cz.medsy.ui.theme.LocalDiscreetMode.current
    var showBatchDialog by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.inventory_title),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                if (medications.isNotEmpty()) {
                    TextButton(onClick = { showBatchDialog = true }) {
                        Text(stringResource(R.string.batch_add))
                    }
                }
            }
        }

        items(lines, key = { it.medicationId }) { line ->
            Surface(
                shape = BentoSmall,
                color = if (line.isLow) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // A low-stock card is `errorContainer`, so `error` red on top of it is red on
                    // dark red — the "usable" and "expired" lines vanished in dark mode.
                    val accent = if (line.isLow) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                    val muted = if (line.isLow) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (discreet) {
                                cz.medsy.domain.discreet.DiscreetDisplay.MASKED_NAME
                            } else {
                                line.medicationName
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${line.remaining.stripTrailingZeros().toPlainString()} ${line.unit}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    val days = if (line.daysRemaining == Int.MAX_VALUE) {
                        stringResource(R.string.inventory_prn)
                    } else {
                        pluralStringResource(
                            R.plurals.inventory_days_left,
                            line.daysRemaining,
                            line.daysRemaining,
                        )
                    }
                    line.meter?.let { meter ->
                        Text(
                            text = stringResource(
                                R.string.inventory_pumps_line,
                                meter.remainingPumps.stripTrailingZeros().toPlainString(),
                                meter.totalPumps.stripTrailingZeros().toPlainString(),
                                meter.dosesLeft,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = days,
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    // Distinguish spoiled stock from usable stock: "14 tbl, 4 usable".
                    if (line.usableRemaining.compareTo(line.remaining) < 0) {
                        Text(
                            text = stringResource(
                                R.string.inventory_usable,
                                line.usableRemaining.stripTrailingZeros().toPlainString(),
                                line.unit,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    if (line.perDay.signum() > 0 && line.daysRemaining != Int.MAX_VALUE) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (line.daysRemaining.coerceAtMost(60) / 60f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (line.expiredBatches.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.inventory_expired_batches),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    if (line.beyondUsePassed.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.inventory_bud_passed),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    line.expiringBatches.firstOrNull()?.expirationDate?.let { expiry ->
                        Text(
                            text = stringResource(R.string.inventory_expires, expiry),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.inventory_consumables), style = MaterialTheme.typography.headlineSmall)
        }

        items(consumables, key = { it.id }) { consumable ->
            Surface(
                shape = BentoSmall,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = consumable.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${consumable.remainingCount}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (consumable.remainingCount <= consumable.lowStockAlertCount) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            TravelPlanner(
                input = travelInput,
                plan = travelPlan,
                onCalculate = onCalculateTravel,
                onUpdate = onUpdateTravel,
            )
        }
    }

    if (showBatchDialog) {
        BatchEditorDialog(
            medications = medications,
            onValidate = onValidateBatch,
            onSave = onSaveBatch,
            onDismiss = { showBatchDialog = false },
        )
    }
}

@Composable
private fun TravelPlanner(
    input: TravelPlanInput,
    plan: InventoryCalculator.TravelPlan?,
    onCalculate: () -> Unit,
    onUpdate: (LocalDate, LocalDate, Int) -> Unit,
) {
    var buffer by remember(input.bufferDays) { mutableStateOf(input.bufferDays.toString()) }
    val discreet = cz.medsy.ui.theme.LocalDiscreetMode.current
    Surface(
        shape = BentoMedium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.inventory_travel_plan),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = input.tripStart.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.inventory_travel_start)) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = input.tripEnd.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.inventory_travel_end)) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = buffer,
                onValueChange = { value ->
                    buffer = value.filter(Char::isDigit)
                    buffer.toIntOrNull()?.let { onUpdate(input.tripStart, input.tripEnd, it) }
                },
                label = { Text(stringResource(R.string.inventory_safety_buffer)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCalculate) { Text(stringResource(R.string.inventory_calculate)) }

            plan?.let { travelPlan ->
                Spacer(Modifier.height(14.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.inventory_for_days,
                        travelPlan.tripDays,
                        travelPlan.tripDays,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                travelPlan.items.forEach { item ->
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (discreet) {
                                    cz.medsy.domain.discreet.DiscreetDisplay.MASKED_NAME
                                } else {
                                    item.medicationName
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                text = stringResource(
                                    R.string.inventory_need_have_units,
                                    item.required.stripTrailingZeros().toPlainString(),
                                    item.unit,
                                    item.available.stripTrailingZeros().toPlainString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                            )
                        }
                        if (item.shortfall.signum() > 0) {
                            Text(
                                text = "−${item.shortfall.stripTrailingZeros().toPlainString()}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (travelPlan.consumables.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.inventory_consumables_for,
                            travelPlan.injectionCount,
                            travelPlan.injectionCount,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    travelPlan.consumables.forEach { item ->
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.label.asText(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.inventory_need_have,
                                        item.required,
                                        item.available,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                                )
                            }
                            if (item.shortfall > 0) {
                                Text(
                                    text = "−${item.shortfall}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (travelPlan.consumables.any { it.available == 0 }) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.inventory_no_stock_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Inventory light")
@Preview(name = "Inventory dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InventoryPreview() {
    MedsyTheme {
        InventoryScreen(
            lines = listOf(
                InventoryCalculator.StockLine(
                    medicationId = 1,
                    medicationName = "Estradiol Enanthate",
                    unit = "mL",
                    remaining = BigDecimal("2.4"),
                    perDay = BigDecimal("0.143"),
                    usableRemaining = BigDecimal("2.4"),
                    daysRemaining = 16,
                    isLow = false,
                    expiringBatches = emptyList(),
                    expiredBatches = emptyList(),
                    beyondUsePassed = emptyList(),
                    beyondUseSoon = emptyList(),
                ),
            ),
            consumables = listOf(
                Consumable(id = 1, name = "29G Insulin Syringe", remainingCount = 4, lowStockAlertCount = 5),
                Consumable(id = 2, name = "Alcohol Swab", remainingCount = 40, lowStockAlertCount = 12),
            ),
            travelInput = TravelPlanInput(),
            travelPlan = null,
            onCalculateTravel = {},
            onUpdateTravel = { _, _, _ -> },
        )
    }
}
