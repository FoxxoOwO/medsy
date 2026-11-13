package cz.medsy.ui.bloodwork

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.ui.message.asText
import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.calc.BloodworkCalculator
import cz.medsy.domain.calc.RangeStatus
import cz.medsy.domain.model.ProfileType
import cz.medsy.ui.components.BiomarkerChart
import cz.medsy.ui.components.ChartPoint
import cz.medsy.ui.components.TargetBand
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyStatusColors
import cz.medsy.ui.theme.MedsyTheme
import java.time.Instant
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun BloodworkRoute(
    onEditCustomTarget: (String) -> Unit = {},
    viewModel: BloodworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFromUri) }
    // The capture step is a screen-local state, not a nav route: the staging result lives
    // in this view model, so a separate route would need its own hand-off of rows.
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        LabScanScreen(
            onCaptured = { uri ->
                scanning = false
                viewModel.importPhoto(uri)
            },
            onCancel = { scanning = false },
        )
        return
    }

    BloodworkScreen(
        state = state,
        onSelect = viewModel::select,
        onProfile = viewModel::setProfile,
        onToggleOverlay = viewModel::toggleOverlay,
        onImportPdf = { picker.launch(arrayOf("application/pdf")) },
        onScanReport = { scanning = true },
        onSaveImport = viewModel::saveImport,
        onDiscardImport = viewModel::discardImport,
        onEditCustomTarget = onEditCustomTarget,
    )
}

@Composable
fun BloodworkScreen(
    state: BloodworkUiState,
    onSelect: (String) -> Unit,
    onProfile: (ProfileType) -> Unit,
    onToggleOverlay: (ProfileType) -> Unit = {},
    onImportPdf: () -> Unit,
    onScanReport: () -> Unit = {},
    onSaveImport: (Instant, String?, Boolean) -> Unit,
    onDiscardImport: () -> Unit,
    onEditCustomTarget: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selected = state.series.firstOrNull { it.code == state.selectedCode } ?: state.series.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.channel_bloodwork),
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // LAB_PRINTED is included: it is the only profile that can catch a lab
                // whose own range differs from the built-in targets.
                ProfileType.entries.forEach { profile ->
                    FilterChip(
                        selected = state.profile == profile || profile in state.overlayProfiles,
                        enabled = true,
                        onClick = {
                            if (state.profile == profile) {
                                onProfile(profile)
                            } else {
                                onToggleOverlay(profile)
                            }
                        },
                        label = {
                            Text(
                                if (state.profile == profile) {
                                    message(
                                        MessageKey.PROFILE_PRIMARY_SUFFIX,
                                        profile.friendlyName(),
                                    ).asText()
                                } else {
                                    profile.friendlyName().asText()
                                },
                            )
                        },
                    )
                }
            }
        }

        if (state.warnings.isNotEmpty()) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.labs_before_draw),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        state.warnings.forEach { warning ->
                            Text(
                                text = "• " + warning.asText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        item {
            Surface(
                shape = BentoMedium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (selected == null) {
                        Text(
                            text = stringResource(R.string.bloodwork_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selected.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                selected.lastValue?.let { value ->
                                    Text(
                                        text = "${formatValue(value)} ${selected.unit}",
                                        style = MaterialTheme.typography.displaySmallEmphasized,
                                    )
                                }
                            }
                            selected.delta?.let { delta ->
                                val sign = if (delta >= 0) "+" else ""
                                val magnitude = String.format(java.util.Locale.ROOT, "%.0f", delta)
                                Surface(
                                    shape = BentoSmall,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Text(
                                        text = "$sign$magnitude%",
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // Resolved before the lambda: `asText` is composable and
                        // `mapIndexed` is not.
                        val bandLabels = selected.bands.map { it.label.asText() }
                        BiomarkerChart(
                            points = selected.points.map { ChartPoint(it.timestamp, it.value) },
                            bands = selected.bands.mapIndexed { index, band ->
                                TargetBand(
                                    min = band.min?.toDouble(),
                                    max = band.max?.toDouble(),
                                    label = bandLabels[index],
                                    color = bandColor(index, band.isPrimary),
                                    isPrimary = band.isPrimary,
                                )
                            },
                            milestones = state.milestones,
                            unitSuffix = selected.unit,
                        )
                        if (selected.bands.size > 1) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                selected.bands.forEachIndexed { index, band ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = bandColor(index, band.isPrimary),
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .height(10.dp)
                                                .width(10.dp),
                                        ) {}
                                        Text(
                                            text = bandLabels[index],
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.profile == ProfileType.CUSTOM && selected != null) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selected.bandMin != null || selected.bandMax != null) {
                                stringResource(
                                    R.string.labs_window_set,
                                    listOfNotNull(
                                        selected.bandMin?.let { formatValue(it) },
                                        selected.bandMax?.let { formatValue(it) },
                                    ).joinToString(" – ") + " ${selected.unit}",
                                )
                            } else {
                                stringResource(R.string.labs_no_target_for, selected.displayName)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onEditCustomTarget(selected.code) }) {
                            Text(stringResource(R.string.common_edit))
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScanReport) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.labs_scan_report))
                }
                OutlinedButton(onClick = onImportPdf) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.labs_pdf))
                }
            }
        }

        if (state.isReadingPhoto) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.labs_reading_photo),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        state.trough?.let { trough ->
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = if (trough.isTrough) stringResource(R.string.bloodwork_true_trough) else stringResource(R.string.bloodwork_not_trough),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = trough.label.asText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.labs_biomarkers), style = MaterialTheme.typography.headlineSmall)
        }

        state.series.forEach { series ->
            item(key = series.code) {
                Surface(
                    shape = BentoSmall,
                    color = if (series.code == selected?.code) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                    onClick = { onSelect(series.code) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = series.displayName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = pluralStringResource(
                                    R.plurals.labs_result_count,
                                    series.points.size,
                                    series.points.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = series.lastValue?.let { "${formatValue(it)} ${series.unit}" } ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            color = series.status.color(),
                        )
                    }
                }
            }
        }

        state.pendingImport?.let { staging ->
            item {
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when {
                                staging.requiresOcr -> stringResource(R.string.labs_pdf_scanned)
                                staging.rows.isEmpty() -> stringResource(R.string.labs_no_values_hint)
                                else -> pluralStringResource(
                                    R.plurals.labs_review_results,
                                    staging.rows.size,
                                    staging.rows.size,
                                )
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        if (staging.rows.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onScanReport) { Text(stringResource(R.string.labs_retake)) }
                                AssistChip(
                                    onClick = onDiscardImport,
                                    label = { Text(stringResource(R.string.labs_discard)) },
                                )
                            }
                        }
                        if (!staging.requiresOcr && staging.rows.isNotEmpty()) {
                            staging.rows.take(8).forEach { row ->
                                Text(
                                    text = stringResource(
                                        R.string.labs_row_line,
                                        row.displayName,
                                        row.rawValue,
                                        row.rawUnit,
                                        row.siValue?.let { "$it ${row.siUnit}" }
                                            ?: stringResource(R.string.labs_unit_unknown),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { onSaveImport(Instant.now(), null, true) }) {
                                    Text(stringResource(R.string.labs_save_results, staging.rows.count { it.isValid }))
                                }
                                AssistChip(
                                    onClick = onDiscardImport,
                                    label = { Text(stringResource(R.string.labs_discard)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Distinct colours for overlapping bands. The primary uses the status green; overlays
 * rotate through a small fixed palette so at most three are ever on screen.
 */
private fun bandColor(index: Int, isPrimary: Boolean) = when {
    isPrimary -> MedsyStatusColors.good
    else -> listOf(
        MedsyStatusColors.trough,
        MedsyStatusColors.warning,
        MedsyStatusColors.peak,
    )[(index - 1).coerceAtLeast(0) % 3]
}

private fun formatValue(value: Double): String =
    if (value >= 100) value.toInt().toString()
    else String.format(java.util.Locale.ROOT, "%.2f", value)

internal fun RangeStatus.color(): Color = when (this) {
    RangeStatus.CRITICAL_LOW, RangeStatus.CRITICAL_HIGH -> MedsyStatusColors.critical
    RangeStatus.LOW, RangeStatus.HIGH -> MedsyStatusColors.warning
    RangeStatus.IN_RANGE -> MedsyStatusColors.good
    RangeStatus.NO_RANGE -> Color.Unspecified
}

internal fun ProfileType.friendlyName(): Message = when (this) {
    ProfileType.HRT_FEM -> message(MessageKey.PROFILE_HRT_FEMINIZING)
    ProfileType.HRT_MASC -> message(MessageKey.PROFILE_HRT_MASCULINIZING)
    ProfileType.CIS_F -> message(MessageKey.PROFILE_CIS_FEMALE)
    ProfileType.CIS_M -> message(MessageKey.PROFILE_CIS_MALE)
    ProfileType.CUSTOM -> message(MessageKey.PROFILE_DOCTOR_TARGET)
    ProfileType.LAB_PRINTED -> message(MessageKey.PROFILE_LAB_PRINTED)
}

@Preview(name = "Bloodwork light")
@Preview(name = "Bloodwork dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BloodworkPreview() {
    MedsyTheme {
        BloodworkScreen(
            state = BloodworkUiState(
                series = listOf(
                    BiomarkerSeries(
                        code = "E2",
                        displayName = "Estradiol",
                        unit = "pmol/L",
                        points = listOf(
                            SeriesPoint(Instant.now().minusSeconds(86400L * 60), 420.0),
                            SeriesPoint(Instant.now().minusSeconds(86400L * 30), 610.0),
                        ),
                        lastValue = 610.0,
                        status = RangeStatus.IN_RANGE,
                        bandMin = 367.0,
                        bandMax = 734.0,
                        delta = 45.0,
                    ),
                ),
                selectedCode = "E2",
                isLoading = false,
            ),
            onSelect = {},
            onProfile = {},
            onImportPdf = {},
            onScanReport = {},
            onSaveImport = { _, _, _ -> },
            onDiscardImport = {},
        )
    }
}

