package app.harmonic.music.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import app.harmonic.music.data.prefs.ThemeMode
import app.harmonic.music.data.prefs.ThemePalette

private val DEFAULT_SEED: Int = 0xFFFF6B4A.toInt()

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * @param albumSeed key colour from the current song's cover, used by [ThemePalette.ALBUM].
 */
@Composable
fun HarmonicTheme(
    mode: ThemeMode,
    palette: ThemePalette,
    pureBlack: Boolean,
    albumSeed: Int?,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(mode)
    val context = LocalContext.current
    val black = pureBlack && dark

    val scheme = when (palette) {
        ThemePalette.WALLPAPER ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                if (black) s.withBlackBackground() else s
            } else {
                seededScheme(DEFAULT_SEED, dark, black, mono = false)
            }
        ThemePalette.ALBUM -> seededScheme(albumSeed ?: DEFAULT_SEED, dark, black, mono = false)
        else -> seededScheme(palette.seed!!.toInt(), dark, black, mono = palette == ThemePalette.MONO)
    }

    // Album colours change with every song: fade the key colours instead of flashing.
    val animated = if (palette == ThemePalette.ALBUM) scheme.animated() else scheme
    MaterialTheme(colorScheme = animated, content = content)
}

@Composable
private fun ColorScheme.animated(): ColorScheme {
    val spec = tween<Color>(durationMillis = 600)
    val primary by animateColorAsState(primary, spec, label = "primary")
    val primaryContainer by animateColorAsState(primaryContainer, spec, label = "primaryContainer")
    val secondaryContainer by animateColorAsState(secondaryContainer, spec, label = "secondaryContainer")
    val background by animateColorAsState(background, spec, label = "background")
    val surface by animateColorAsState(surface, spec, label = "surface")
    val surfaceContainer by animateColorAsState(surfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(surfaceContainerHigh, spec, label = "surfaceContainerHigh")
    return copy(
        primary = primary,
        primaryContainer = primaryContainer,
        secondaryContainer = secondaryContainer,
        background = background,
        surface = surface,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
    )
}

private fun ColorScheme.withBlackBackground() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
)

/** A colour from hue (0-360), saturation and lightness (0-1). */
private fun hsl(h: Float, s: Float, l: Float): Color =
    Color(ColorUtils.HSLToColor(floatArrayOf((h % 360 + 360) % 360, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/**
 * Builds a full Material 3 scheme from one key colour, using fixed lightness steps per role
 * (the same idea as Material's tonal palettes, simplified to HSL).
 */
fun seededScheme(seed: Int, dark: Boolean, pureBlack: Boolean, mono: Boolean): ColorScheme {
    val out = FloatArray(3)
    ColorUtils.colorToHSL(seed, out)
    val h = out[0]
    val s = if (mono) 0f else out[1].coerceIn(0.4f, 0.9f)
    val neutral = if (mono) 0f else 0.10f
    val tertiaryHue = h + 60

    return if (dark) {
        val bg = if (pureBlack) Color.Black else hsl(h, neutral, 0.06f)
        darkColorScheme(
            primary = hsl(h, s, 0.72f),
            onPrimary = hsl(h, s, 0.15f),
            primaryContainer = hsl(h, s * 0.8f, 0.27f),
            onPrimaryContainer = hsl(h, s, 0.90f),
            secondary = hsl(h, s * 0.35f, 0.74f),
            onSecondary = hsl(h, s * 0.35f, 0.17f),
            secondaryContainer = hsl(h, s * 0.3f, 0.25f),
            onSecondaryContainer = hsl(h, s * 0.3f, 0.90f),
            tertiary = hsl(tertiaryHue, s * 0.5f, 0.74f),
            onTertiary = hsl(tertiaryHue, s * 0.5f, 0.17f),
            tertiaryContainer = hsl(tertiaryHue, s * 0.45f, 0.27f),
            onTertiaryContainer = hsl(tertiaryHue, s * 0.5f, 0.90f),
            background = bg,
            onBackground = hsl(h, neutral, 0.91f),
            surface = bg,
            onSurface = hsl(h, neutral, 0.91f),
            surfaceVariant = hsl(h, neutral * 1.4f, 0.22f),
            onSurfaceVariant = hsl(h, neutral * 1.4f, 0.78f),
            surfaceTint = hsl(h, s, 0.72f),
            inverseSurface = hsl(h, neutral, 0.90f),
            inverseOnSurface = hsl(h, neutral, 0.15f),
            inversePrimary = hsl(h, s, 0.40f),
            outline = hsl(h, neutral, 0.56f),
            outlineVariant = hsl(h, neutral, 0.28f),
            surfaceBright = hsl(h, neutral, 0.22f),
            surfaceDim = bg,
            surfaceContainerLowest = if (pureBlack) Color.Black else hsl(h, neutral, 0.04f),
            surfaceContainerLow = hsl(h, neutral, if (pureBlack) 0.04f else 0.09f),
            surfaceContainer = hsl(h, neutral, if (pureBlack) 0.07f else 0.11f),
            surfaceContainerHigh = hsl(h, neutral, if (pureBlack) 0.11f else 0.15f),
            surfaceContainerHighest = hsl(h, neutral, if (pureBlack) 0.15f else 0.19f),
        )
    } else {
        val bg = hsl(h, neutral * 2f, 0.98f)
        lightColorScheme(
            primary = hsl(h, s, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hsl(h, s, 0.90f),
            onPrimaryContainer = hsl(h, s, 0.14f),
            secondary = hsl(h, s * 0.3f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(h, s * 0.4f, 0.90f),
            onSecondaryContainer = hsl(h, s * 0.3f, 0.14f),
            tertiary = hsl(tertiaryHue, s * 0.5f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(tertiaryHue, s * 0.5f, 0.90f),
            onTertiaryContainer = hsl(tertiaryHue, s * 0.5f, 0.14f),
            background = bg,
            onBackground = hsl(h, neutral, 0.11f),
            surface = bg,
            onSurface = hsl(h, neutral, 0.11f),
            surfaceVariant = hsl(h, neutral * 2f, 0.90f),
            onSurfaceVariant = hsl(h, neutral * 1.5f, 0.30f),
            surfaceTint = hsl(h, s, 0.40f),
            inverseSurface = hsl(h, neutral, 0.19f),
            inverseOnSurface = hsl(h, neutral, 0.95f),
            inversePrimary = hsl(h, s, 0.80f),
            outline = hsl(h, neutral, 0.48f),
            outlineVariant = hsl(h, neutral, 0.80f),
            surfaceBright = bg,
            surfaceDim = hsl(h, neutral * 2f, 0.87f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, neutral * 2f, 0.96f),
            surfaceContainer = hsl(h, neutral * 2f, 0.94f),
            surfaceContainerHigh = hsl(h, neutral * 2f, 0.92f),
            surfaceContainerHighest = hsl(h, neutral * 2f, 0.90f),
        )
    }
}

/** For the settings preview swatches. */
fun paletteSwatch(palette: ThemePalette): Color? = palette.seed?.let { Color(it.toInt()) }
