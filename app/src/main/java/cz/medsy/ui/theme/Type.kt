package cz.medsy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Default = Typography()

private val TighterLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun TextStyle.expressiveEmphasis(): TextStyle = copy(
    fontWeight = FontWeight.SemiBold,
    lineHeightStyle = TighterLineHeight,
)

private fun TextStyle.expressiveDisplay(): TextStyle = copy(
    fontWeight = FontWeight.Medium,
    letterSpacing = (-1).sp,
    lineHeightStyle = TighterLineHeight,
)

val MedsyTypography: Typography = Typography(
    displayLarge = Default.displayLarge.expressiveDisplay(),
    displayMedium = Default.displayMedium.expressiveDisplay(),
    displaySmall = Default.displaySmall.expressiveDisplay(),
    headlineLarge = Default.headlineLarge.expressiveEmphasis(),
    headlineMedium = Default.headlineMedium.expressiveEmphasis(),
    headlineSmall = Default.headlineSmall.expressiveEmphasis(),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Default.bodyLarge.copy(lineHeightStyle = TighterLineHeight),
    bodyMedium = Default.bodyMedium.copy(lineHeightStyle = TighterLineHeight),
    bodySmall = Default.bodySmall,
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Default.labelMedium,
    labelSmall = Default.labelSmall,
    displayLargeEmphasized = Default.displayLarge.expressiveDisplay().copy(fontWeight = FontWeight.SemiBold),
    displayMediumEmphasized = Default.displayMedium.expressiveDisplay().copy(fontWeight = FontWeight.SemiBold),
    displaySmallEmphasized = Default.displaySmall.expressiveDisplay().copy(fontWeight = FontWeight.SemiBold),
    headlineLargeEmphasized = Default.headlineLarge.expressiveEmphasis(),
    headlineMediumEmphasized = Default.headlineMedium.expressiveEmphasis(),
    headlineSmallEmphasized = Default.headlineSmall.expressiveEmphasis(),
    titleLargeEmphasized = Default.titleLarge.expressiveEmphasis(),
    titleMediumEmphasized = Default.titleMedium.expressiveEmphasis(),
    titleSmallEmphasized = Default.titleSmall.expressiveEmphasis(),
    bodyLargeEmphasized = Default.bodyLarge.expressiveEmphasis(),
    bodyMediumEmphasized = Default.bodyMedium.expressiveEmphasis(),
    bodySmallEmphasized = Default.bodySmall.expressiveEmphasis(),
    labelLargeEmphasized = Default.labelLarge.expressiveEmphasis(),
    labelMediumEmphasized = Default.labelMedium.expressiveEmphasis(),
    labelSmallEmphasized = Default.labelSmall.expressiveEmphasis(),
)

val MedsyFontFamily: FontFamily = FontFamily.SansSerif
