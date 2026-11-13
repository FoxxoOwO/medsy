package cz.medsy.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.toPath
import cz.medsy.domain.model.MedicationForm
import cz.medsy.ui.theme.MedsyMorph

/**
 * Dose confirmation button. Resting: a pill/squircle per medication form.
 * Confirmed: morphs into a starburst checkmark over a bouncy spring.
 */
@Composable
fun MorphingDoseButton(
    form: MedicationForm,
    confirmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    contentDescription: String? = null,
) {
    val from = MedsyMorph.baseFor(form)
    val to = MedsyMorph.checkBurst
    val morph = remember(from, to) { Morph(from, to) }
    val progress = remember { Animatable(if (confirmed) 1f else 0f) }
    val fill = if (confirmed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primary
    val contentColor = if (confirmed) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val interaction = remember { MutableInteractionSource() }
    val path = remember { android.graphics.Path() }

    LaunchedEffect(confirmed) {
        progress.animateTo(
            targetValue = if (confirmed) 1f else 0f,
            animationSpec = MedsyMorph.bouncySpring(),
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription?.let { this.contentDescription = it } }
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Checkbox,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            path.rewind()
            morph.toPath(progress.value.coerceIn(0f, 1f), path)
            val composePath = path.asComposePath()
            scale(
                scaleX = this.size.width / 2f,
                scaleY = this.size.height / 2f,
                pivot = center,
            ) {
                drawPath(composePath, color = fill)
            }
        }
        if (confirmed) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "+",
                    color = contentColor,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}

@Composable
fun FormShape(
    form: MedicationForm,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    color: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val polygon = remember(form) { MedsyMorph.baseFor(form) }
    val path = remember { android.graphics.Path() }
    val composePath = remember(polygon) {
        val raw = android.graphics.Path().also { polygon.toPath(it) }
        raw.asComposePath()
    }
    Canvas(modifier = modifier.size(size)) {
        scale(scaleX = this.size.width / 2f, scaleY = this.size.height / 2f, pivot = center) {
            drawPath(composePath, color = color)
        }
    }
}

@Composable
fun MorphingProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    strokeWidth: Dp = 8.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val indicator = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        drawArc(
            color = indicator,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
    }
}

