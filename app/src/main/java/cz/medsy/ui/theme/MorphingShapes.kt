package cz.medsy.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.pill
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import cz.medsy.domain.model.MedicationForm

object MedsyMorph {

    private const val OUTER = 1f
    private const val INNER = 0.8f

    val pill: RoundedPolygon = RoundedPolygon.pill()
    val squircle: RoundedPolygon = RoundedPolygon.rectangle(
        width = 2f,
        height = 2f,
        rounding = CornerRounding(radius = 0.62f, smoothing = 0.75f),
    )
    val hexagon: RoundedPolygon = RoundedPolygon(
        numVertices = 6,
        radius = OUTER,
        centerX = 0f,
        centerY = 0f,
        rounding = CornerRounding(0.22f),
    )
    val circle: RoundedPolygon = RoundedPolygon.circle()
    val checkBurst: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 8,
        radius = OUTER,
        innerRadius = 0.72f,
        rounding = CornerRounding(0.32f, 0.6f),
        innerRounding = CornerRounding(0.32f, 0.6f),
    )
    val starburst: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 12,
        radius = OUTER,
        innerRadius = 0.78f,
        rounding = CornerRounding(0.18f),
        innerRounding = CornerRounding(0.18f),
    )
    val droplet: RoundedPolygon = RoundedPolygon(
        numVertices = 3,
        radius = OUTER,
        centerX = 0f,
        centerY = 0f,
        rounding = CornerRounding(0.5f, 0.35f),
    )

    fun baseFor(form: MedicationForm): RoundedPolygon = when (form) {
        MedicationForm.TABLET, MedicationForm.CAPSULE -> pill
        MedicationForm.INJECTION_IM, MedicationForm.INJECTION_SUBQ -> hexagon
        MedicationForm.PATCH -> squircle
        MedicationForm.GEL, MedicationForm.SPRAY, MedicationForm.DROPS -> droplet
        MedicationForm.SUBLINGUAL -> circle
    }

    fun shapeFor(form: MedicationForm): Shape = PolygonShape(baseFor(form))

    fun bouncySpring() = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )
}

private const val BOUNDS_LEFT = 0
private const val BOUNDS_TOP = 1
private const val BOUNDS_RIGHT = 2
private const val BOUNDS_BOTTOM = 3

private fun unitMatrix(bounds: FloatArray, target: Size): Matrix {
    val width = bounds[BOUNDS_RIGHT] - bounds[BOUNDS_LEFT]
    val height = bounds[BOUNDS_BOTTOM] - bounds[BOUNDS_TOP]
    if (width <= 0f || height <= 0f) return Matrix()
    val scale = minOf(target.width / width, target.height / height)
    val centerX = (bounds[BOUNDS_LEFT] + bounds[BOUNDS_RIGHT]) / 2f
    val centerY = (bounds[BOUNDS_TOP] + bounds[BOUNDS_BOTTOM]) / 2f
    return Matrix().apply {
        translate(target.width / 2f, target.height / 2f)
        scale(scale, scale)
        translate(-centerX, -centerY)
    }
}

class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val raw = android.graphics.Path().also { polygon.toPath(it) }
        val path = raw.asComposePath()
        path.transform(unitMatrix(polygon.calculateBounds(), size))
        return Outline.Generic(path)
    }
}

class MorphShape(
    private val morph: Morph,
    private val progress: () -> Float,
) : Shape {

    constructor(from: RoundedPolygon, to: RoundedPolygon, progress: () -> Float) :
        this(Morph(from, to), progress)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val t = progress().coerceIn(0f, 1f)
        val raw = android.graphics.Path().also { morph.toPath(t, it) }
        val path = raw.asComposePath()
        path.transform(unitMatrix(morph.calculateBounds(), size))
        return Outline.Generic(path)
    }
}
