package cz.medsy.ui.symptoms

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.domain.calc.SymptomCorrelation
import cz.medsy.domain.model.SymptomLog
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun SymptomsRoute(viewModel: SymptomsViewModel = hiltViewModel()) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val patterns by viewModel.patterns.collectAsStateWithLifecycle()
    SymptomsScreen(
        draft = draft,
        recent = recent,
        patterns = patterns,
        onKind = viewModel::setKind,
        onSeverity = viewModel::setSeverity,
        onNote = viewModel::setNote,
        onSystolic = viewModel::setSystolic,
        onDiastolic = viewModel::setDiastolic,
        onHeartRate = viewModel::setHeartRate,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
    )
}

@Composable
fun SymptomsScreen(
    draft: SymptomDraft,
    recent: List<SymptomLog>,
    onKind: (SymptomKind) -> Unit,
    onSeverity: (Int) -> Unit,
    onNote: (String) -> Unit,
    onSystolic: (String) -> Unit,
    onDiastolic: (String) -> Unit,
    onHeartRate: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: (SymptomLog) -> Unit,
    patterns: List<SymptomCorrelation> = emptyList(),
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.symptoms_title),
                style = MaterialTheme.typography.displaySmallEmphasized,
            )
        }

        item {
            Surface(
                shape = BentoMedium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.symptoms_log_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SymptomKind.entries.forEach { kind ->
                            FilterChip(
                                selected = draft.kind == kind,
                                onClick = { onKind(kind) },
                                label = { Text(stringResource(kind.label)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.symptoms_severity, draft.severity),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = draft.severity.toFloat(),
                        onValueChange = { onSeverity(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = draft.note,
                        onValueChange = onNote,
                        label = { Text(stringResource(R.string.symptoms_note)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.systolic,
                            onValueChange = onSystolic,
                            label = { Text(stringResource(R.string.symptoms_sys)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.diastolic,
                            onValueChange = onDiastolic,
                            label = { Text(stringResource(R.string.symptoms_dia)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = draft.heartRate,
                            onValueChange = onHeartRate,
                            label = { Text(stringResource(R.string.symptoms_hr)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onSave) { Text(stringResource(R.string.symptoms_save)) }
                }
            }
        }

        if (patterns.isNotEmpty()) {
            item { Text(stringResource(R.string.symptoms_patterns), style = MaterialTheme.typography.headlineSmall) }
            item {
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        patterns.forEach { correlation ->
                            SymptomPatternRow(correlation)
                        }
                        Text(
                            text = stringResource(R.string.symptoms_correlation_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { Text(stringResource(R.string.symptoms_last_30), style = MaterialTheme.typography.headlineSmall) }
        }

        items(recent, key = { it.id }) { log ->
            Surface(
                shape = BentoSmall,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val symptomLabel = SymptomKind.entries
                            .firstOrNull { it.code == log.symptomCode }
                            ?.let { stringResource(it.label) }
                            ?: log.symptomCode
                        Text(
                            text = stringResource(R.string.symptoms_log_line, symptomLabel, log.severity),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = buildString {
                                append(
                                    log.timestamp.atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("d.M. HH:mm")),
                                )
                                if (log.systolic != null && log.diastolic != null) {
                                    append(" · BP ${log.systolic}/${log.diastolic}")
                                }
                                log.heartRate?.let { append(" · HR $it") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        log.note?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { onDelete(log) }) { Text(stringResource(R.string.common_delete)) }
                }
            }
        }
    }
}

/**
 * One pattern: the sentence states the direction, the dots show the two means, and the
 * counts are printed so a pattern based on four entries is not mistaken for a law.
 */
@Composable
private fun SymptomPatternRow(correlation: SymptomCorrelation) {
    val label = SymptomKind.entries.firstOrNull { it.code == correlation.symptomCode }
        ?.let { stringResource(it.label) }
        ?: correlation.symptomCode
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.symptoms_pattern_line, label, correlationSentence(correlation)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        SeverityLine(
            mean = correlation.inWindowMean,
            count = correlation.inWindowCount,
            caption = stringResource(R.string.symptoms_caption_near),
            color = MaterialTheme.colorScheme.primary,
        )
        SeverityLine(
            mean = correlation.outOfWindowMean,
            count = correlation.outOfWindowCount,
            caption = stringResource(R.string.symptoms_caption_other),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun SeverityLine(mean: Double, count: Int, caption: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(5) { index ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < mean.roundToInt()) color
                            else color.copy(alpha = 0.25f),
                        ),
                )
            }
        }
        Text(
            text = stringResource(R.string.symptom_pattern_mean, formatMean(mean), caption, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun correlationSentence(correlation: SymptomCorrelation): String {
    val hours = (correlation.window.toMinutes() / 60).toInt()
    val ratio = String.format(Locale.ROOT, "%.1f", correlation.ratio ?: 1.0)
    return when {
        correlation.isWorseNearDose -> stringResource(R.string.symptoms_worse, ratio, hours)
        correlation.isMilderNearDose -> stringResource(R.string.symptoms_milder, ratio, hours)
        else -> stringResource(R.string.symptoms_no_difference)
    }
}

private fun formatMean(mean: Double): String = String.format(Locale.ROOT, "%.1f", mean)

@Preview(name = "Symptoms light")
@Preview(name = "Symptoms dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SymptomsPreview() {
    MedsyTheme {
        SymptomsScreen(
            draft = SymptomDraft(severity = 4),
            recent = listOf(
                SymptomLog(
                    id = 1,
                    timestamp = Instant.now(),
                    symptomCode = "MOOD",
                    severity = 4,
                    note = "Better after the dose change",
                    systolic = 118,
                    diastolic = 76,
                    heartRate = 68,
                ),
            ),
            onKind = {},
            onSeverity = {},
            onNote = {},
            onSystolic = {},
            onDiastolic = {},
            onHeartRate = {},
            onSave = {},
            onDelete = {},
            patterns = listOf(
                SymptomCorrelation(
                    symptomCode = "HEADACHE",
                    window = java.time.Duration.ofHours(2),
                    inWindowCount = 5,
                    outOfWindowCount = 11,
                    inWindowMean = 4.2,
                    outOfWindowMean = 2.1,
                ),
            ),
        )
    }
}
