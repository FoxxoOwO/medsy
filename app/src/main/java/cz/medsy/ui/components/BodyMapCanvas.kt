package cz.medsy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cz.medsy.R
import cz.medsy.domain.bodymap.BodyMap
import cz.medsy.domain.bodymap.BodyMapHitTest
import cz.medsy.domain.model.ApplicationSite
import cz.medsy.domain.model.MedicationForm
import cz.medsy.ui.message.asText
import cz.medsy.ui.theme.MedsyTheme
import java.time.Instant

/**
 * Stylised front-facing body with tappable application sites.
 *
 * The drawing is deliberately schematic: it has to be readable at a glance and large
 * enough to hit with a thumb, not anatomically exact. All coordinates come from
 * [BodyMap], so the picture and the rotation algorithm can never disagree about where
 * the left thigh is.
 */
@Composable
fun BodyMapCanvas(
    states: List<BodyMap.SiteState>,
    selected: ApplicationSite?,
    recommended: ApplicationSite?,
    onSelect: (ApplicationSite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline
    val recommendedRing = MaterialTheme.colorScheme.tertiary
    val selectedRing = MaterialTheme.colorScheme.onSurface

    val sites = states.map { it.site }
    // Resolved here because colour lookups are composable and the draw callback is not.
    val dotColors = states.associate { it.site to freshnessColor(it.freshness) }
    // Screen readers get one summary sentence; the chips are the real route into the map.
    val none = stringResource(R.string.bodymap_none)
    val description = stringResource(
        R.string.bodymap_a11y,
        selected?.label?.asText() ?: none,
        recommended?.label?.asText() ?: none,
    )

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(sites, onSelect) {
                detectTapGestures { tap ->
                    if (size.width == 0 || size.height == 0) return@detectTapGestures
                    BodyMapHitTest.nearest(
                        x = tap.x / size.width,
                        y = tap.y / size.height,
                        candidates = sites,
                    )?.let(onSelect)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        fun at(x: Float, y: Float) = Offset(x * w, y * h)

        // Torso, neck and head. The rounded rectangle starts at the shoulders so the
        // deltoid dots at y = 0.30 sit on the arm, not on the chest.
        drawRoundRect(
            color = skin,
            topLeft = at(0.38f, 0.15f),
            size = Size(w * 0.24f, h * 0.43f),
            cornerRadius = CornerRadius(w * 0.055f),
        )
        drawCircle(color = skin, radius = w * 0.058f, center = at(0.5f, 0.085f))

        // Arms: shoulder → elbow → wrist → hand. Patient's left arm is on the right of
        // the screen because the body faces the viewer.
        val armStroke = w * 0.05f
        drawLine(skin, at(0.60f, 0.20f), at(0.70f, 0.44f), strokeWidth = armStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.70f, 0.44f), at(0.77f, 0.63f), strokeWidth = armStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.77f, 0.63f), at(0.84f, 0.72f), strokeWidth = armStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.40f, 0.20f), at(0.30f, 0.44f), strokeWidth = armStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.30f, 0.44f), at(0.23f, 0.63f), strokeWidth = armStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.23f, 0.63f), at(0.16f, 0.72f), strokeWidth = armStroke, cap = StrokeCap.Round)

        // Legs: hip → knee → ankle.
        val legStroke = w * 0.065f
        drawLine(skin, at(0.45f, 0.56f), at(0.42f, 0.78f), strokeWidth = legStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.42f, 0.78f), at(0.44f, 0.94f), strokeWidth = legStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.55f, 0.56f), at(0.58f, 0.78f), strokeWidth = legStroke, cap = StrokeCap.Round)
        drawLine(skin, at(0.58f, 0.78f), at(0.56f, 0.94f), strokeWidth = legStroke, cap = StrokeCap.Round)

        states.forEach { state ->
            val placement = BodyMap.placement(state.site) ?: return@forEach
            val center = at(placement.x, placement.y)
            val dotRadius = w * 0.021f

            drawCircle(
                color = dotColors[state.site] ?: outline,
                radius = dotRadius,
                center = center,
            )
            drawCircle(
                color = outline.copy(alpha = 0.55f),
                radius = dotRadius,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            if (state.site == recommended) {
                drawCircle(
                    color = recommendedRing,
                    radius = dotRadius * 1.9f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            if (state.site == selected) {
                drawCircle(
                    color = selectedRing,
                    radius = dotRadius * 1.35f,
                    center = center,
                    style = Stroke(width = 2.5.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Colour for a site's recency. Shared with the legend so the swatch can never drift
 * away from the dot it explains.
 */
@Composable
fun freshnessColor(freshness: BodyMap.Freshness): Color = when (freshness) {
    BodyMap.Freshness.NEVER -> MaterialTheme.colorScheme.surface
    BodyMap.Freshness.FRESH -> MaterialTheme.colorScheme.errorContainer
    BodyMap.Freshness.RECENT -> MaterialTheme.colorScheme.tertiaryContainer
    BodyMap.Freshness.AGED -> MaterialTheme.colorScheme.primaryContainer
}

@Preview(name = "Body map — light")
@Preview(name = "Body map — dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BodyMapCanvasPreview() {
    val now = Instant.parse("2026-11-11T12:00:00Z")
    val states = BodyMap.states(
        form = MedicationForm.INJECTION_IM,
        history = listOf(
            ApplicationSite.LEFT_DELTOID to now.minusSeconds(86_400L * 2),
            ApplicationSite.RIGHT_VASTUS_LATERALIS to now.minusSeconds(86_400L * 15),
        ),
        now = now,
    )
    MedsyTheme {
        BodyMapCanvas(
            states = states,
            selected = ApplicationSite.LEFT_VASTUS_LATERALIS,
            recommended = ApplicationSite.LEFT_VASTUS_LATERALIS,
            onSelect = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
        )
    }
}
