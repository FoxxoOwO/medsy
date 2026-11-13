package cz.medsy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeConfig(
    val palette: MedsyPalette = MedsyPalette.BLOSSOM,
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

val LocalDiscreetMode: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

@Composable
fun MedsyTheme(
    config: ThemeConfig = ThemeConfig(),
    discreetMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (config.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    // Every one of these branches returns a fresh ColorScheme instance, so computing it during
    // composition made each recomposition of MedsyTheme (any settings change, any lock state
    // flip) invalidate every MaterialTheme read below it — the whole tree recomposed even though
    // the colours were identical. Remembering it keeps the identity stable, and caching the
    // dynamic scheme also avoids re-reading the system palette overlay on each pass.
    val colorScheme: ColorScheme = remember(config.dynamicColor, config.mode, config.palette, dark) {
        when {
            config.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark ->
                dynamicDarkColorScheme(context)
            config.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                dynamicLightColorScheme(context)
            dark -> medsyDarkScheme(config.palette)
            else -> medsyLightScheme(config.palette)
        }
    }

    CompositionLocalProvider(LocalDiscreetMode provides discreetMode) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = MedsyShapes,
            typography = MedsyTypography,
            content = content,
        )
    }
}
