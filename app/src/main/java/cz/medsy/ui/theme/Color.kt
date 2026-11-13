package cz.medsy.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class MedsyPalette { BLOSSOM, NONBINARY, MINT, SAND, OLED_INK }

private val BlossomPrimaryLight = Color(0xFF4C5C92)
private val BlossomOnPrimaryLight = Color(0xFFFFFFFF)
private val BlossomPrimaryContainerLight = Color(0xFFDCE1FF)
private val BlossomOnPrimaryContainerLight = Color(0xFF04164A)
private val BlossomSecondaryLight = Color(0xFF8E4A6B)
private val BlossomOnSecondaryLight = Color(0xFFFFFFFF)
private val BlossomSecondaryContainerLight = Color(0xFFFFD9E5)
private val BlossomOnSecondaryContainerLight = Color(0xFF3A0424)
private val BlossomTertiaryLight = Color(0xFF5B7B6F)
private val BlossomOnTertiaryLight = Color(0xFFFFFFFF)
private val BlossomTertiaryContainerLight = Color(0xFFD5EFE3)
private val BlossomOnTertiaryContainerLight = Color(0xFF12291F)
private val BlossomErrorLight = Color(0xFFBA1A1A)
private val BlossomOnErrorLight = Color(0xFFFFFFFF)
private val BlossomErrorContainerLight = Color(0xFFFFDAD6)
private val BlossomOnErrorContainerLight = Color(0xFF410002)
private val BlossomBackgroundLight = Color(0xFFFFF8F9)
private val BlossomOnBackgroundLight = Color(0xFF1C1B1D)
private val BlossomSurfaceLight = Color(0xFFFFF8F9)
private val BlossomOnSurfaceLight = Color(0xFF1C1B1D)
private val BlossomSurfaceVariantLight = Color(0xFFE5E1E6)
private val BlossomOnSurfaceVariantLight = Color(0xFF46434A)
private val BlossomOutlineLight = Color(0xFF77737B)

private val BlossomPrimaryDark = Color(0xFFB6C4FF)
private val BlossomOnPrimaryDark = Color(0xFF1C2C60)
private val BlossomPrimaryContainerDark = Color(0xFF344477)
private val BlossomOnPrimaryContainerDark = Color(0xFFDCE1FF)
private val BlossomSecondaryDark = Color(0xFFFFB0CD)
private val BlossomOnSecondaryDark = Color(0xFF561D3B)
private val BlossomSecondaryContainerDark = Color(0xFF713352)
private val BlossomOnSecondaryContainerDark = Color(0xFFFFD9E5)
private val BlossomTertiaryDark = Color(0xFFB9D3C7)
private val BlossomOnTertiaryDark = Color(0xFF253F35)
private val BlossomTertiaryContainerDark = Color(0xFF3C574B)
private val BlossomOnTertiaryContainerDark = Color(0xFFD5EFE3)
private val BlossomErrorDark = Color(0xFFFFB4AB)
private val BlossomOnErrorDark = Color(0xFF690005)
private val BlossomErrorContainerDark = Color(0xFF93000A)
private val BlossomOnErrorContainerDark = Color(0xFFFFDAD6)
private val BlossomBackgroundDark = Color(0xFF141315)
private val BlossomOnBackgroundDark = Color(0xFFE6E1E4)
private val BlossomSurfaceDark = Color(0xFF141315)
private val BlossomOnSurfaceDark = Color(0xFFE6E1E4)
private val BlossomSurfaceVariantDark = Color(0xFF46434A)
private val BlossomOnSurfaceVariantDark = Color(0xFFCAC5CD)
private val BlossomOutlineDark = Color(0xFF948F97)

private val NonbinaryPrimaryLight = Color(0xFF5B53A8)
private val NonbinarySecondaryLight = Color(0xFF7A6C00)
private val NonbinaryTertiaryLight = Color(0xFF8A5A96)

private val MintPrimaryLight = Color(0xFF2E6B57)
private val MintSecondaryLight = Color(0xFF4C6359)
private val MintTertiaryLight = Color(0xFF3E6374)

private val SandPrimaryLight = Color(0xFF7A5733)
private val SandSecondaryLight = Color(0xFF6C5B47)
private val SandTertiaryLight = Color(0xFF4B6540)

private val OledPrimaryDark = Color(0xFFB6C4FF)
private val OledSecondaryDark = Color(0xFFFFB0CD)
private val OledTertiaryDark = Color(0xFFB9D3C7)

internal fun blossomLight(): ColorScheme = lightColorScheme(
    primary = BlossomPrimaryLight,
    onPrimary = BlossomOnPrimaryLight,
    primaryContainer = BlossomPrimaryContainerLight,
    onPrimaryContainer = BlossomOnPrimaryContainerLight,
    secondary = BlossomSecondaryLight,
    onSecondary = BlossomOnSecondaryLight,
    secondaryContainer = BlossomSecondaryContainerLight,
    onSecondaryContainer = BlossomOnSecondaryContainerLight,
    tertiary = BlossomTertiaryLight,
    onTertiary = BlossomOnTertiaryLight,
    tertiaryContainer = BlossomTertiaryContainerLight,
    onTertiaryContainer = BlossomOnTertiaryContainerLight,
    error = BlossomErrorLight,
    onError = BlossomOnErrorLight,
    errorContainer = BlossomErrorContainerLight,
    onErrorContainer = BlossomOnErrorContainerLight,
    background = BlossomBackgroundLight,
    onBackground = BlossomOnBackgroundLight,
    surface = BlossomSurfaceLight,
    onSurface = BlossomOnSurfaceLight,
    surfaceVariant = BlossomSurfaceVariantLight,
    onSurfaceVariant = BlossomOnSurfaceVariantLight,
    outline = BlossomOutlineLight,
)

internal fun blossomDark(): ColorScheme = darkColorScheme(
    primary = BlossomPrimaryDark,
    onPrimary = BlossomOnPrimaryDark,
    primaryContainer = BlossomPrimaryContainerDark,
    onPrimaryContainer = BlossomOnPrimaryContainerDark,
    secondary = BlossomSecondaryDark,
    onSecondary = BlossomOnSecondaryDark,
    secondaryContainer = BlossomSecondaryContainerDark,
    onSecondaryContainer = BlossomOnSecondaryContainerDark,
    tertiary = BlossomTertiaryDark,
    onTertiary = BlossomOnTertiaryDark,
    tertiaryContainer = BlossomTertiaryContainerDark,
    onTertiaryContainer = BlossomOnTertiaryContainerDark,
    error = BlossomErrorDark,
    onError = BlossomOnErrorDark,
    errorContainer = BlossomErrorContainerDark,
    onErrorContainer = BlossomOnErrorContainerDark,
    background = BlossomBackgroundDark,
    onBackground = BlossomOnBackgroundDark,
    surface = BlossomSurfaceDark,
    onSurface = BlossomOnSurfaceDark,
    surfaceVariant = BlossomSurfaceVariantDark,
    onSurfaceVariant = BlossomOnSurfaceVariantDark,
    outline = BlossomOutlineDark,
)

internal fun nonbinaryLight(): ColorScheme = blossomLight().copy(
    primary = NonbinaryPrimaryLight,
    secondary = NonbinarySecondaryLight,
    tertiary = NonbinaryTertiaryLight,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF150C63),
    secondaryContainer = Color(0xFFFFE16B),
    onSecondaryContainer = Color(0xFF211B00),
    tertiaryContainer = Color(0xFFFFD7EE),
    onTertiaryContainer = Color(0xFF38093F),
)

internal fun nonbinaryDark(): ColorScheme = blossomDark().copy(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2C2478),
    primaryContainer = Color(0xFF443C90),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFE6C84A),
    onSecondary = Color(0xFF3A3000),
    secondaryContainer = Color(0xFF544700),
    onSecondaryContainer = Color(0xFFFFE16B),
    tertiary = Color(0xFFF0B4DE),
    onTertiary = Color(0xFF4C2049),
    tertiaryContainer = Color(0xFF663660),
    onTertiaryContainer = Color(0xFFFFD7EE),
)

internal fun mintLight(): ColorScheme = blossomLight().copy(
    primary = MintPrimaryLight,
    secondary = MintSecondaryLight,
    tertiary = MintTertiaryLight,
    primaryContainer = Color(0xFFB4F1D8),
    onPrimaryContainer = Color(0xFF00201A),
    secondaryContainer = Color(0xFFD0E8DC),
    onSecondaryContainer = Color(0xFF0A1F17),
    tertiaryContainer = Color(0xFFC2E8FB),
    onTertiaryContainer = Color(0xFF001F2A),
)

internal fun mintDark(): ColorScheme = blossomDark().copy(
    primary = Color(0xFF99D7BF),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF145141),
    onPrimaryContainer = Color(0xFFB4F1D8),
    secondary = Color(0xFFB4CCC0),
    onSecondary = Color(0xFF20352C),
    secondaryContainer = Color(0xFF364B42),
    onSecondaryContainer = Color(0xFFD0E8DC),
    tertiary = Color(0xFFA6CCDF),
    onTertiary = Color(0xFF0B3446),
    tertiaryContainer = Color(0xFF254B5D),
    onTertiaryContainer = Color(0xFFC2E8FB),
)

internal fun sandLight(): ColorScheme = blossomLight().copy(
    primary = SandPrimaryLight,
    secondary = SandSecondaryLight,
    tertiary = SandTertiaryLight,
    primaryContainer = Color(0xFFFFDDB8),
    onPrimaryContainer = Color(0xFF2C1700),
    secondaryContainer = Color(0xFFF1E0C8),
    onSecondaryContainer = Color(0xFF251A0A),
    tertiaryContainer = Color(0xFFCCEBC0),
    onTertiaryContainer = Color(0xFF0B2007),
)

internal fun sandDark(): ColorScheme = blossomDark().copy(
    primary = Color(0xFFEFC08C),
    onPrimary = Color(0xFF472A09),
    primaryContainer = Color(0xFF60401F),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFFD5C4AC),
    onSecondary = Color(0xFF3A2F20),
    secondaryContainer = Color(0xFF514535),
    onSecondaryContainer = Color(0xFFF1E0C8),
    tertiary = Color(0xFFB1CFA5),
    onTertiary = Color(0xFF1E361A),
    tertiaryContainer = Color(0xFF344D2F),
    onTertiaryContainer = Color(0xFFCCEBC0),
)

internal fun oledDark(): ColorScheme = blossomDark().copy(
    primary = OledPrimaryDark,
    secondary = OledSecondaryDark,
    tertiary = OledTertiaryDark,
    background = Color(0xFF000000),
    onBackground = Color(0xFFECECEF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFECECEF),
    surfaceVariant = Color(0xFF23232A),
    onSurfaceVariant = Color(0xFFCFCCD2),
    // The pure-black background used to sit on top of a container ramp that was almost black
    // too (0x0A, 0x10, 0x16), so cards, the stock table and the bottom bar all blended into
    // one surface. The ramp now steps away from black far enough to read as separate layers.
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF15151B),
    surfaceContainer = Color(0xFF1B1B22),
    surfaceContainerHigh = Color(0xFF222229),
    surfaceContainerHighest = Color(0xFF2A2A32),
    outline = Color(0xFF9A96A0),
    outlineVariant = Color(0xFF3C3C44),
)

fun medsyLightScheme(palette: MedsyPalette): ColorScheme = when (palette) {
    MedsyPalette.BLOSSOM -> blossomLight()
    MedsyPalette.NONBINARY -> nonbinaryLight()
    MedsyPalette.MINT -> mintLight()
    MedsyPalette.SAND -> sandLight()
    MedsyPalette.OLED_INK -> blossomLight()
}

fun medsyDarkScheme(palette: MedsyPalette): ColorScheme = when (palette) {
    MedsyPalette.BLOSSOM -> blossomDark()
    MedsyPalette.NONBINARY -> nonbinaryDark()
    MedsyPalette.MINT -> mintDark()
    MedsyPalette.SAND -> sandDark()
    MedsyPalette.OLED_INK -> oledDark()
}

object MedsyStatusColors {
    val good = Color(0xFF3F8F63)
    val warning = Color(0xFFC98A22)
    val critical = Color(0xFFC63B3B)
    val trough = Color(0xFF6C6AAE)
    val peak = Color(0xFFC05B8E)
}
