package cz.medsy.ui.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.domain.model.MedicationTranslation
import cz.medsy.ui.theme.BentoSmall

/**
 * Machine translation of the medication's own text, with the original kept in place above it.
 *
 * The card never hides the Czech text: a translation is a reading aid, and the app's job is to be
 * right about the medication, so the cached copy is labelled as automatic and can be deleted.
 */
@Composable
fun MedicationTranslationCard(
    translations: List<MedicationTranslation>,
    translating: String?,
    failed: Boolean,
    onTranslate: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(TranslationTarget.ENGLISH.language) }
    val current = translations.firstOrNull { it.language == selected }
    val busy = translating == selected

    Surface(
        shape = BentoSmall,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.translation_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.translation_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TranslationTarget.entries.forEach { target ->
                    FilterChip(
                        selected = selected == target.language,
                        enabled = translating == null,
                        onClick = { selected = target.language },
                        label = { Text(stringResource(target.labelRes)) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (current != null) {
                Text(text = current.name, style = MaterialTheme.typography.titleMedium)
                current.activeSubstance?.takeIf { it.isNotBlank() }?.let { substance ->
                    Text(
                        text = substance,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Spacer(Modifier.height(6.dp))
                    Text(text = notes, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.translation_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onTranslate(selected) }, enabled = !busy) {
                        Text(stringResource(R.string.translation_refresh))
                    }
                    TextButton(onClick = { onRemove(selected) }, enabled = !busy) {
                        Text(
                            text = stringResource(R.string.translation_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).padding(start = 4.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { onTranslate(selected) }, enabled = translating == null) {
                        Text(stringResource(R.string.translation_action))
                    }
                    if (busy) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (failed && translating == null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.translation_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
