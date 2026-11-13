package cz.medsy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.domain.bodymap.BodyMap
import cz.medsy.domain.message.Message
import cz.medsy.domain.message.MessageKey
import cz.medsy.domain.message.message
import cz.medsy.domain.message.messagePlural
import cz.medsy.ui.message.asText
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import cz.medsy.ui.theme.MedsyTheme
import java.time.Duration
import java.time.Instant

/**
 * Body map plus an explicit list of the same sites.
 *
 * The list is not decoration: dragging a finger over a drawing is unusable with a screen
 * reader or a switch device, so every site is also a chip with a real label. Both routes
 * write to the same [onSelect], so the UI can never hold two contradictory selections.
 */
@Composable
fun BodyMapPicker(
    states: List<BodyMap.SiteState>,
    selected: ApplicationSite?,
    recommended: ApplicationSite?,
    onSelect: (ApplicationSite) -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.bodymap_title),
    canvasHeight: Dp = 280.dp,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        BodyMapCanvas(
            states = states,
            selected = selected,
            recommended = recommended,
            onSelect = onSelect,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .height(canvasHeight)
                .width(canvasHeight * 0.68f),
        )

        Spacer(Modifier.height(10.dp))
        Legend()
        Spacer(Modifier.height(10.dp))

        val chosen = states.firstOrNull { it.site == selected }
        // The sentence is assembled as a Message: the site label and the age are both messages
        // themselves, so the Czech "před 3 dny" is chosen by the plural rule, not by this file.
        val chosenLine: Message? = chosen?.let { state ->
            if (state.lastUsed == null) {
                message(MessageKey.BODYMAP_SITE_NEVER_USED, state.site.label)
            } else {
                message(
                    MessageKey.BODYMAP_SITE_USED_AGO,
                    state.site.label,
                    describeAge(state.daysAgo ?: 0L),
                )
            }
        }
        Text(
            text = chosenLine?.asText() ?: stringResource(R.string.bodymap_empty),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected != null && selected == recommended) {
            Text(
                text = stringResource(R.string.site_suggested),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(states, key = { it.site.name }) { state ->
                // Both strings are resolved here, outside `semantics`, because a semantics
                // lambda is not composable and cannot read resources.
                val siteText = state.site.label.asText()
                val chipText = if (state.site == recommended) {
                    stringResource(R.string.bodymap_chip_suggested, siteText)
                } else {
                    siteText
                }
                // The chip is also the screen-reader route to the map, so the age is spoken
                // here; the map itself only has a summary description.
                val ageText = if (state.lastUsed == null) {
                    stringResource(R.string.bodymap_never_used)
                } else {
                    stringResource(R.string.bodymap_used_ago, describeAge(state.daysAgo ?: 0L).asText())
                }
                val description = stringResource(R.string.bodymap_chip_a11y, chipText, ageText)
                FilterChip(
                    selected = state.site == selected,
                    onClick = { onSelect(state.site) },
                    modifier = Modifier.semantics { contentDescription = description },
                    label = { Text(text = chipText) },
                    leadingIcon = {
                        BoxDot(freshnessColor(state.freshness))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            }
        }
    }
}

/**
 * Four swatches explaining the dot colours. Only states that can actually occur for the
 * given form are useful, but a fixed legend keeps the map's meaning stable between forms.
 */
@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LegendEntry(BodyMap.Freshness.NEVER, stringResource(R.string.bodymap_fresh_never))
        LegendEntry(BodyMap.Freshness.FRESH, stringResource(R.string.bodymap_fresh_under_week))
        LegendEntry(BodyMap.Freshness.RECENT, stringResource(R.string.bodymap_fresh_week))
        LegendEntry(BodyMap.Freshness.AGED, stringResource(R.string.bodymap_fresh_old))
    }
}

@Composable
private fun LegendEntry(freshness: BodyMap.Freshness, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BoxDot(freshnessColor(freshness))
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoxDot(color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/**
 * Ages are plural forms, so this returns a [Message] instead of a string: Czech needs "před 1 dnem",
 * "před 3 dny" and "před 5 dny" from the same key.
 */
internal fun describeAge(daysAgo: Long): Message = when {
    daysAgo <= 0L -> message(MessageKey.BODYMAP_AGE_TODAY)
    else -> messagePlural(MessageKey.BODYMAP_AGE_DAYS, daysAgo.toInt(), daysAgo)
}

@Preview(name = "Site picker — light")
@Preview(name = "Site picker — dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BodyMapPickerPreview() {
    val now = Instant.parse("2026-11-11T12:00:00Z")
    val states = BodyMap.states(
        form = MedicationForm.INJECTION_SUBQ,
        history = listOf(
            ApplicationSite.ABDOMEN_UPPER_LEFT to now.minus(Duration.ofDays(3)),
            ApplicationSite.ABDOMEN_UPPER_RIGHT to now.minus(Duration.ofDays(10)),
        ),
        now = now,
    )
    MedsyTheme {
        BodyMapPicker(
            states = states,
            selected = ApplicationSite.ABDOMEN_LOWER_LEFT,
            recommended = ApplicationSite.ABDOMEN_LOWER_LEFT,
            onSelect = {},
            modifier = Modifier.padding(20.dp),
        )
    }
}
