package cz.medsy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.medsy.R
import cz.medsy.ui.TopLevelDestination
import cz.medsy.ui.theme.FloatingPill
import cz.medsy.ui.theme.MedsyTheme

/**
 * The floating pill navigation bar.
 *
 * Each destination gets an equal share of the width instead of its intrinsic label width:
 * the six Czech labels ("Nastavení", "Přehled") together are wider than a phone, so a
 * content-sized bar pushed the last item off the screen and clipped its label. Equal shares
 * keep every destination on screen and let a long label ellipsize instead of being cut.
 */
@Composable
fun MedsyBottomBar(
    destinations: List<TopLevelDestination>,
    selectedRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .fillMaxWidth(),
        shape = FloatingPill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        // The outline belongs to the same shape as the container; drawing it as a separate
        // modifier left the stroke hanging outside the bar, across the labels.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .padding(horizontal = 6.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                BottomBarItem(
                    destination = destination,
                    selected = destination.route == selectedRoute,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .height(BAR_HEIGHT - 8.dp)
            .clip(RoundedCornerShape(percent = 50))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(28.dp)
                .clip(FloatingPill)
                .background(if (selected) colors.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(destination.label),
            style = MaterialTheme.typography.labelSmall,
            // Six short Czech labels share the width, so the default 0.5sp tracking of
            // labelSmall is the difference between "Nastavení" fitting and being clipped.
            letterSpacing = 0.sp,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 1.dp, end = 1.dp, top = 3.dp),
        )
    }
}

/** Tall enough for the 20dp icon plus its label; the labels used to be clipped. */
private val BAR_HEIGHT = 64.dp

@Preview(name = "Bottom bar light", showBackground = true)
@Preview(
    name = "Bottom bar dark",
    showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MedsyBottomBarPreview() {
    MedsyTheme {
        MedsyBottomBar(
            destinations = TopLevelDestination.entries,
            selectedRoute = TopLevelDestination.DASHBOARD.route,
            onSelect = {},
        )
    }
}
