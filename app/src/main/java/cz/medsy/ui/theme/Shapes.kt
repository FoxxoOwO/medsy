package cz.medsy.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val MedsyShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(36.dp),
    largeIncreased = RoundedCornerShape(30.dp),
    extraLargeIncreased = RoundedCornerShape(42.dp),
    extraExtraLarge = RoundedCornerShape(52.dp),
)

val BentoSmall = RoundedCornerShape(20.dp)
val BentoMedium = RoundedCornerShape(26.dp)
val BentoLarge = RoundedCornerShape(34.dp)
val FloatingPill = RoundedCornerShape(percent = 50)
val SheetTop = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
