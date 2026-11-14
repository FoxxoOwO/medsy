package cz.medsy.ui.medications

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.domain.model.SideEffect
import cz.medsy.domain.model.SideEffectFrequency
import cz.medsy.domain.model.SideEffectProfile

/**
 * The common undesirable effects, grouped by how often the SPC says they happen.
 *
 * Deliberately read-only and offline: the numbers come from a bundled asset, so the card is the
 * same on a train as at home. It is also *not* a leaflet: the disclaimer is part of the card, not a
 * dialog, because a user skimming for "is this normal?" should see the limit of the claim and the
 * PIL button (right above it) at the same time.
 */
@Composable
fun SideEffectCard(
    profile: SideEffectProfile?,
    modifier: Modifier = Modifier,
) {
    if (profile == null) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.meds_detail_side_effects),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.meds_detail_side_effects_source,
                    profile.atcCode,
                    profile.substance,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.isGroupLevel) {
                Text(
                    text = stringResource(
                        R.string.meds_detail_side_effects_group,
                        profile.atcCode,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            profile.byFrequency().forEach { (band, effects) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(band.labelRes()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    effects.forEach { effect ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Text(effect, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.meds_detail_side_effects_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@StringRes
private fun SideEffectFrequency.labelRes(): Int = when (this) {
    SideEffectFrequency.VERY_COMMON -> R.string.side_effect_very_common
    SideEffectFrequency.COMMON -> R.string.side_effect_common
    SideEffectFrequency.UNCOMMON -> R.string.side_effect_uncommon
    SideEffectFrequency.RARE -> R.string.side_effect_rare
    SideEffectFrequency.VERY_RARE -> R.string.side_effect_very_rare
}

@Preview(showBackground = true)
@Composable
private fun SideEffectCardPreview() {
    MaterialTheme {
        SideEffectCard(
            profile = SideEffectProfile(
                atcCode = "G03CA03",
                substance = "Estradiol",
                effects = listOf(
                    SideEffect(SideEffectFrequency.VERY_COMMON, "Citlivost nebo napětí prsou"),
                    SideEffect(SideEffectFrequency.COMMON, "Bolest hlavy, nevolnost, nadýmání"),
                    SideEffect(SideEffectFrequency.UNCOMMON, "Změny nálady, úzkost"),
                ),
            ),
        )
    }
}
