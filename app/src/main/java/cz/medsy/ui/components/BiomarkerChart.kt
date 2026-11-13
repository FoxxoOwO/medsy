package cz.medsy.ui.components

import cz.medsy.ui.message.asText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class ChartPoint(
    val timestamp: Instant,
    val value: Double,
    val label: String? = null,
)

data class TargetBand(
    val min: Double?,
    val max: Double?,
    val label: String,
    val color: Color,
    /** The band that decides the status colour; drawn last so it stays visible. */
    val isPrimary: Boolean = true,
)

data class MilestonePin(
    val timestamp: Instant,
    val label: cz.medsy.domain.message.Message? = null,
    /** Sentence stored before the message layer existed; used when [label] is null. */
    val legacyLabel: String = "",
    val color: Color = Color.Unspecified,
)

/**
 * Line chart with cubic Bézier interpolation, a shaded target band and vertical
 * milestone pins. Tapping selects the nearest point.
 */
@Composable
fun BiomarkerChart(
    points: List<ChartPoint>,
    bands: List<TargetBand> = emptyList(),
    milestones: List<MilestonePin> = emptyList(),
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 220.dp,
    unitSuffix: String = "",
    onPointSelected: (ChartPoint?) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val surfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val milestoneColor = MaterialTheme.colorScheme.tertiary

    val sorted = remember(points) { points.sortedBy { it.timestamp } }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(sorted) {
                detectTapGestures { offset ->
                    if (sorted.isEmpty()) {
                        onPointSelected(null)
                        return@detectTapGestures
                    }
                    val plot = Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
                    val nearest = sorted.minByOrNull { point ->
                        abs(timeToX(point.timestamp, sorted, plot) - offset.x)
                    }
                    onPointSelected(nearest)
                }
            },
    ) {
        // Labels are drawn with a text measurer, so they must be resolved outside the Canvas.
        val pinTexts = milestones.map { it.label?.asText() ?: it.legacyLabel }

        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (sorted.isEmpty()) {
                drawCenterText(textMeasurer, "No data yet", surfaceVariant)
                return@Canvas
            }
            val plot = plotRect(0f, 0f, size.width, size.height)
            val values = buildList {
                addAll(sorted.map { it.value })
                bands.forEach { band ->
                    band.min?.let(::add)
                    band.max?.let(::add)
                }
            }
            val rawMin = values.minOrNull() ?: 0.0
            val rawMax = values.maxOrNull() ?: 1.0
            val padding = ((rawMax - rawMin) * 0.15).coerceAtLeast(if (rawMax == 0.0) 1.0 else rawMax * 0.05)
            val minValue = rawMin - padding
            val maxValue = rawMax + padding

            // Draw overlays first and the primary last so the anchor band stays visible
            // even where translucent rectangles overlap.
            bands.sortedBy { it.isPrimary }.forEachIndexed { index, band ->
                if (band.min == null && band.max == null) return@forEachIndexed
                val top = band.max?.let { valueToY(it, minValue, maxValue, plot) } ?: plot.top
                val bottom = band.min?.let { valueToY(it, minValue, maxValue, plot) } ?: plot.bottom
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(band.color.copy(alpha = 0.24f), band.color.copy(alpha = 0.10f)),
                    ),
                    topLeft = Offset(plot.left, top.coerceAtLeast(plot.top)),
                    size = Size(plot.width, (bottom - top).coerceAtLeast(0f)),
                )
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                drawLine(
                    color = band.color.copy(alpha = 0.6f),
                    start = Offset(plot.left, top),
                    end = Offset(plot.right, top),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
                if (band.max != null) {
                    drawLine(
                        color = band.color.copy(alpha = 0.6f),
                        start = Offset(plot.left, bottom),
                        end = Offset(plot.right, bottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dash,
                    )
                }
            }

            drawLine(
                color = outline,
                start = Offset(plot.left, plot.bottom),
                end = Offset(plot.right, plot.bottom),
                strokeWidth = 1.dp.toPx(),
            )

            val pinColor = milestoneColor
            for ((index, milestone) in milestones.withIndex()) {
                val x = timeToX(milestone.timestamp, sorted, plot).coerceIn(plot.left, plot.right)
                drawLine(
                    color = (if (milestone.color == Color.Unspecified) pinColor else milestone.color)
                        .copy(alpha = 0.5f),
                    start = Offset(x, plot.top),
                    end = Offset(x, plot.bottom),
                    strokeWidth = 1.5.dp.toPx(),
                )
                // Alternating rows keep two nearby pins from overlapping their labels.
                val pinText = pinTexts.getOrElse(index) { "" }
                if (pinText.isNotBlank()) {
                    val measured = textMeasurer.measure(
                        pinText,
                        TextStyle(fontSize = 8.sp, color = pinColor),
                        maxLines = 1,
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = MILESTONE_LABEL_WIDTH_PX),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    val labelX = (x - measured.size.width / 2f)
                        .coerceIn(plot.left, (plot.right - measured.size.width).coerceAtLeast(plot.left))
                    val labelY = plot.top - 6.dp.toPx() - (index % 2) * 10.dp.toPx()
                    drawText(
                        textMeasurer = textMeasurer,
                        text = pinText,
                        topLeft = Offset(labelX, labelY.coerceAtLeast(0f)),
                        style = TextStyle(fontSize = 8.sp, color = pinColor),
                        maxLines = 1,
                        size = androidx.compose.ui.geometry.Size(measured.size.width.toFloat(), measured.size.height.toFloat()),
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            val path = Path()
            val coordinates = sorted.map { point ->
                Offset(
                    timeToX(point.timestamp, sorted, plot),
                    valueToY(point.value, minValue, maxValue, plot),
                )
            }
            coordinates.forEachIndexed { index, offset ->
                if (index == 0) {
                    path.moveTo(offset.x, offset.y)
                } else {
                    val previous = coordinates[index - 1]
                    val midX = (previous.x + offset.x) / 2f
                    path.cubicTo(midX, previous.y, midX, offset.y, offset.x, offset.y)
                }
            }

            if (coordinates.size > 1) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(coordinates.last().x, plot.bottom)
                    lineTo(coordinates.first().x, plot.bottom)
                    close()
                }
                drawPath(
                    fill,
                    brush = Brush.verticalGradient(
                        listOf(primary.copy(alpha = 0.24f), primary.copy(alpha = 0.0f)),
                        startY = plot.top,
                        endY = plot.bottom,
                    ),
                )
            }

            drawPath(
                path,
                color = primary,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            coordinates.forEach { offset ->
                drawCircle(color = primary, radius = 4.dp.toPx(), center = offset)
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 2.dp.toPx(),
                    center = offset,
                )
            }

            val firstLabel = sorted.first().timestamp.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d.M."))
            val lastLabel = sorted.last().timestamp.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d.M."))
            val labelStyle = TextStyle(fontSize = 10.sp, color = surfaceVariant)
            drawText(
                textMeasurer = textMeasurer,
                text = firstLabel,
                topLeft = Offset(plot.left, plot.bottom + 4.dp.toPx()),
                style = labelStyle,
            )
            val lastWidth = textMeasurer.measure(lastLabel, labelStyle).size.width
            drawText(
                textMeasurer = textMeasurer,
                text = lastLabel,
                topLeft = Offset(plot.right - lastWidth, plot.bottom + 4.dp.toPx()),
                style = labelStyle,
            )

            val maxLabel = formatValue(rawMax, unitSuffix)
            drawText(
                textMeasurer = textMeasurer,
                text = maxLabel,
                topLeft = Offset(plot.left + 2.dp.toPx(), plot.top + 2.dp.toPx()),
                style = labelStyle,
            )
        }
    }
}

/** Roughly 90 dp at the chart's typical density; keeps labels short. */
private const val MILESTONE_LABEL_WIDTH_PX = 240

private fun DrawScope.plotRect(left: Float, top: Float, width: Float, height: Float): Rect {
    val horizontalPadding = 6.dp.toPx()
    val verticalPadding = 18.dp.toPx()
    return Rect(
        left = left + horizontalPadding,
        top = top + verticalPadding,
        right = left + width - horizontalPadding,
        bottom = top + height - verticalPadding,
    )
}

private fun timeToX(timestamp: Instant, points: List<ChartPoint>, plot: Rect): Float {
    if (points.size == 1) return plot.center.x
    val first = points.first().timestamp.toEpochMilli()
    val last = points.last().timestamp.toEpochMilli()
    if (last == first) return plot.center.x
    val fraction = (timestamp.toEpochMilli() - first).toFloat() / (last - first)
    return plot.left + fraction * plot.width
}

private fun valueToY(value: Double, min: Double, max: Double, plot: Rect): Float {
    if (max == min) return plot.center.y
    val fraction = ((value - min) / (max - min)).toFloat()
    return plot.bottom - fraction * plot.height
}

private fun formatValue(value: Double, suffix: String): String {
    val text = if (value >= 100) value.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", value)
    return if (suffix.isBlank()) text else "$text $suffix"
}

private fun DrawScope.drawCenterText(textMeasurer: TextMeasurer, text: String, color: Color) {
    val style = TextStyle(fontSize = 12.sp, color = color)
    val measured = textMeasurer.measure(text, style)
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(
            (size.width - measured.size.width) / 2f,
            (size.height - measured.size.height) / 2f,
        ),
        style = style,
    )
}
