package cz.medsy.ui.medications

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.domain.model.Medication
import cz.medsy.domain.model.MedicationForm
import cz.medsy.ui.components.MedicationIcon
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.FloatingPill
import cz.medsy.ui.theme.MedsyTheme
import java.math.BigDecimal
import androidx.compose.ui.res.stringResource
import cz.medsy.R
import cz.medsy.ui.message.asText

@Composable
fun MedicationsRoute(
    onAddMedication: () -> Unit,
    onOpenMedication: (Long) -> Unit,
    onNewSchedule: (Long) -> Unit = {},
    onEditSchedule: (Long, Long) -> Unit = { _, _ -> },
    viewModel: MedicationsViewModel = hiltViewModel(),
) {
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val alerts by viewModel.alertsByMedication.collectAsStateWithLifecycle()
    val schedules by viewModel.schedulesByMedication.collectAsStateWithLifecycle()
    val offsetNotices by viewModel.offsetNotices.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshOffsetNotices() }
    MedicationsScreen(
        medications = medications,
        alertsByMedication = alerts,
        schedulesByMedication = schedules,
        offsetNotices = offsetNotices,
        onDismissOffsetNotices = viewModel::dismissOffsetNotices,
        onAddMedication = onAddMedication,
        onOpenMedication = onOpenMedication,
        onNewSchedule = onNewSchedule,
        onEditSchedule = onEditSchedule,
    )
}

@Composable
fun MedicationsScreen(
    medications: List<Medication>,
    alertsByMedication: Map<Long, List<cz.medsy.domain.calc.InteractionAlert>> = emptyMap(),
    schedulesByMedication: Map<Long, List<cz.medsy.domain.model.Schedule>> = emptyMap(),
    offsetNotices: List<cz.medsy.domain.message.Message> = emptyList(),
    onDismissOffsetNotices: () -> Unit = {},
    onAddMedication: () -> Unit,
    onOpenMedication: (Long) -> Unit,
    onNewSchedule: (Long) -> Unit = {},
    onEditSchedule: (Long, Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.medications_title),
                style = MaterialTheme.typography.displaySmallEmphasized,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (offsetNotices.isNotEmpty()) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.meds_reminders_adjusted),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        offsetNotices.forEach { message ->
                            Text(
                                text = message.asText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        TextButton(onClick = onDismissOffsetNotices) {
                            Text(stringResource(R.string.meds_got_it), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
        }

        if (medications.isEmpty()) {
            item {
                Surface(
                    shape = BentoMedium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(22.dp)) {
                        Text(
                            text = stringResource(R.string.meds_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.meds_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        ExtendedFloatingActionButton(
                            onClick = onAddMedication,
                            shape = FloatingPill,
                            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            text = { Text(stringResource(R.string.meds_scan)) },
                        )
                    }
                }
            }
        }

        items(medications, key = { it.id }) { medication ->
            MedicationCard(
                medication = medication,
                alerts = alertsByMedication[medication.id].orEmpty(),
                schedules = schedulesByMedication[medication.id].orEmpty(),
                onClick = { onOpenMedication(medication.id) },
                onNewSchedule = { onNewSchedule(medication.id) },
                onEditSchedule = { scheduleId -> onEditSchedule(medication.id, scheduleId) },
            )
        }
    }
}

@Composable
private fun MedicationCard(
    medication: Medication,
    alerts: List<cz.medsy.domain.calc.InteractionAlert>,
    schedules: List<cz.medsy.domain.model.Schedule>,
    onClick: () -> Unit,
    onNewSchedule: () -> Unit,
    onEditSchedule: (Long) -> Unit,
) {
    val discreet = cz.medsy.ui.theme.LocalDiscreetMode.current
    Surface(
        shape = BentoSmall,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MedicationIcon(
                form = medication.form,
                size = 46.dp,
            )
            Spacer(Modifier.width(14.dp))
            val display = cz.medsy.domain.discreet.DiscreetDisplay.row(medication, discreet)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = display.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (display.nameMasked) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (display.showHrtBadge) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = BentoSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.meds_group_hrt),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (display.detail.isNotBlank()) {
                    Text(
                        text = display.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (alerts.isNotEmpty()) {
                    val severe = alerts.any {
                        it.severity == cz.medsy.domain.model.InteractionSeverity.SEVERE
                    }
                    Text(
                        text = alerts.first().title.asText() +
                            if (alerts.size > 1) "  +${alerts.size - 1}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (severe) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    )
                }
                if (schedules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.meds_no_schedule),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    schedules.forEach { schedule ->
                        Text(
                            text = schedule.summaryLine(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onNewSchedule) { Text(stringResource(R.string.meds_add_schedule)) }
                    schedules.firstOrNull()?.let { first ->
                        TextButton(onClick = { onEditSchedule(first.id) }) { Text(stringResource(R.string.common_edit)) }
                    }
                }
            }
        }
    }
}

/** One-line description of a schedule for the list, e.g. "every 3.5 d · 5 mg · 08:00". */
@Composable
internal fun cz.medsy.domain.model.Schedule.summaryLine(): String {
    val recurrence = when (scheduleType) {
        cz.medsy.domain.model.ScheduleType.FIXED_DAILY -> stringResource(R.string.meds_rec_daily)
        cz.medsy.domain.model.ScheduleType.INTERVAL_DAYS ->
            stringResource(R.string.meds_rec_interval_days, intervalValue?.stripTrailingZeros()?.toPlainString() ?: "")
        cz.medsy.domain.model.ScheduleType.INTERVAL_HOURS ->
            stringResource(R.string.meds_rec_interval_hours, intervalValue?.stripTrailingZeros()?.toPlainString() ?: "")
        cz.medsy.domain.model.ScheduleType.CYCLICAL -> stringResource(R.string.meds_rec_cyclical)
        cz.medsy.domain.model.ScheduleType.TAPERING -> stringResource(R.string.meds_rec_tapering)
        cz.medsy.domain.model.ScheduleType.PRN -> stringResource(R.string.meds_rec_prn)
    }
    val dose = "${doseAmount.stripTrailingZeros().toPlainString()} $doseUnit"
    val times = timesOfDay.joinToString("/") { it.toString() }
    val active = if (isActive) "" else stringResource(R.string.meds_paused_suffix)
    return "$recurrence · $dose · $times$active"
}

@Preview(name = "Medications light")
@Preview(name = "Medications dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MedicationsPreview() {
    MedsyTheme {
        MedicationsScreen(
            medications = listOf(
                Medication(
                    id = 1,
                    name = "Estradiol Enanthate",
                    activeSubstance = "Estradiolum enanthicum",
                    atcCode = "G03CA03",
                    form = MedicationForm.INJECTION_IM,
                    strengthValue = BigDecimal("5"),
                    strengthUnit = "mg",
                    concentrationMgPerMl = BigDecimal("50"),
                    isHrt = true,
                ),
                Medication(
                    id = 2,
                    name = "Progesterone",
                    form = MedicationForm.TABLET,
                    strengthValue = BigDecimal("200"),
                    strengthUnit = "mg",
                ),
            ),
            alertsByMedication = emptyMap(),
            onAddMedication = {},
            onOpenMedication = {},
        )
    }
}

