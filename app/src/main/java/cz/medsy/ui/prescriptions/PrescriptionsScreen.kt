package cz.medsy.ui.prescriptions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.R
import cz.medsy.domain.message.Message
import cz.medsy.domain.model.PrescriptionItem
import cz.medsy.domain.prescription.QueuedPrescription
import cz.medsy.ui.message.asText
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.MedsyTheme
import java.time.Instant
import androidx.compose.ui.res.stringResource

@Composable
fun PrescriptionsRoute(
    onBack: () -> Unit,
    onCreateMedication: (String) -> Unit = {},
    viewModel: PrescriptionsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()

    // One picker for both shapes: a PDF cannot come out of the photo picker, and the importer
    // decides from the MIME type whether the file needs OCR or a text layer.
    val pickFile = rememberLauncherForActivityResult(
        contract = remember { ActivityResultContracts.OpenDocument() },
    ) { uri -> uri?.let(viewModel::import) }

    PrescriptionsScreen(
        items = items,
        onBack = onBack,
        onMarkDispensed = viewModel::markDispensed,
        onDelete = viewModel::delete,
        onCreateMedication = onCreateMedication,
        importStatus = importStatus,
        onImport = {
            viewModel.clearImportStatus()
            pickFile.launch(arrayOf("application/pdf", "image/*"))
        },
    )
}

@Composable
fun PrescriptionsScreen(
    items: List<QueuedPrescription>,
    onBack: () -> Unit = {},
    onMarkDispensed: (Long) -> Unit = {},
    onDelete: (Long) -> Unit = {},
    onCreateMedication: (String) -> Unit = {},
    importStatus: Message? = null,
    onImport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val now = Instant.now()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.rx_title),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            }
        }

        item {
            Column {
                FilledTonalButton(onClick = onImport) {
                    Text(stringResource(R.string.rx_import_button))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.rx_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The outcome stays until the next import: "no code in this file" is the only
                // thing telling the user why nothing appeared in the list.
                importStatus?.let { status ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = status.asText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        if (items.isEmpty()) {
            item {
                Surface(shape = BentoMedium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.rx_empty_title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.rx_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(items, key = { it.id }) { prescription ->
            PrescriptionCard(
                prescription = prescription,
                now = now,
                onMarkDispensed = { onMarkDispensed(prescription.id) },
                onDelete = { onDelete(prescription.id) },
                onCreateMedication = onCreateMedication,
            )
        }
    }
}

@Composable
private fun PrescriptionCard(
    prescription: QueuedPrescription,
    now: Instant,
    onMarkDispensed: () -> Unit,
    onDelete: () -> Unit,
    onCreateMedication: (String) -> Unit,
) {
    val expiringSoon = prescription.isExpiringSoon(now)
    Surface(
        shape = BentoMedium,
        color = if (expiringSoon) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = prescription.code,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = describePrescription(prescription, now),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (expiringSoon) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.rx_expiring)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                }
            }

            prescription.items.forEach { item ->
                Spacer(Modifier.height(8.dp))
                Text(item.name, style = MaterialTheme.typography.bodyMedium)
                val detail = buildString {
                    item.quantity?.let { append("$it pcs") }
                    item.dosageInstruction?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                    item.suklCode?.let {
                        if (isNotEmpty()) append(" · ")
                        append("SÚKL $it")
                    }
                }
                if (detail.isNotEmpty()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!prescription.dispensed) {
                    TextButton(onClick = onMarkDispensed) { Text(stringResource(R.string.rx_mark_dispensed)) }
                }
                prescription.items.firstOrNull()?.name?.let { name ->
                    TextButton(onClick = { onCreateMedication(name) }) {
                        Text(stringResource(R.string.rx_create_medication))
                    }
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
            }
        }
    }
}

@Preview(name = "Prescriptions light")
@Preview(name = "Prescriptions dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PrescriptionsScreenPreview() {
    MedsyTheme {
        PrescriptionsScreen(
            items = listOf(
                QueuedPrescription(
                    id = 1,
                    code = "ABCD1234EFGH",
                    issuedAt = Instant.parse("2026-05-20T08:00:00Z"),
                    validUntil = Instant.parse("2026-06-03T21:59:59Z"),
                    dispensed = false,
                    items = listOf(
                        PrescriptionItem(
                            suklCode = "0012345",
                            name = "Estradiol 2 mg",
                            quantity = 84,
                            dosageInstruction = "1-0-1",
                        ),
                    ),
                ),
            ),
            onBack = {},
        )
    }
}
