package cz.medsy.ui.bloodwork

import cz.medsy.ui.message.asText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.medsy.ui.theme.BentoMedium
import cz.medsy.ui.theme.BentoSmall
import cz.medsy.ui.theme.MedsyTheme
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import cz.medsy.R

@Composable
fun CustomTargetRoute(
    code: String,
    onFinished: () -> Unit,
    viewModel: CustomTargetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(code) { viewModel.load(code) }
    LaunchedEffect(state.saved, state.removed) {
        if (state.saved || state.removed) onFinished()
    }

    CustomTargetScreen(
        state = state,
        onMin = viewModel::setMin,
        onMax = viewModel::setMax,
        onUnit = viewModel::setInputUnit,
        onSave = viewModel::save,
        onRemove = viewModel::remove,
        onCancel = onFinished,
    )
}

@Composable
fun CustomTargetScreen(
    state: CustomTargetUiState,
    onMin: (String) -> Unit,
    onMax: (String) -> Unit,
    onUnit: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(
                    text = stringResource(R.string.range_custom),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                )
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.errors.isNotEmpty() || state.messageErrors.isNotEmpty()) {
            item {
                Surface(
                    shape = BentoSmall,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Both lists land in one card: validator prose and message-typed
                        // errors must not look like two different problems.
                        (state.errors + state.messageErrors.map { it.asText() }).forEach { error ->
                            Text(
                                text = "• $error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
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
                        state.warnings.forEach { warning ->
                            Text(
                                text = warning,
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
                    Text(
                        text = stringResource(R.string.target_window),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))

                    if (state.conventionalUnit != null) {
                        Text(
                            text = stringResource(R.string.batch_unit),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(state.siUnit, state.conventionalUnit).forEach { unit ->
                                FilterChip(
                                    selected = state.inputUnit == unit,
                                    onClick = { onUnit(unit) },
                                    label = { Text(unit) },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.minText,
                            onValueChange = onMin,
                            label = { Text(stringResource(R.string.target_minimum, state.inputUnit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.maxText,
                            onValueChange = onMax,
                            label = { Text(stringResource(R.string.target_maximum, state.inputUnit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.target_storage_note, state.siUnit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave) { Text(stringResource(R.string.target_save)) }
                Button(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                if (state.existing != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.target_remove)) }
                }
            }
        }
    }
}

@Preview(name = "Custom target light")
@Preview(name = "Custom target dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CustomTargetPreview() {
    MedsyTheme {
        CustomTargetScreen(
            state = CustomTargetUiState(
                code = "E2",
                displayName = "Estradiol",
                siUnit = "pmol/L",
                conventionalUnit = "pg/mL",
                inputUnit = "pmol/L",
                minText = "400",
                maxText = "600",
            ),
            onMin = {},
            onMax = {},
            onUnit = {},
            onSave = {},
            onRemove = {},
            onCancel = {},
        )
    }
}
