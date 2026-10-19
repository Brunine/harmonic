package app.harmonic.music.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Ember = Color(0xFFFF6B4A)

private val DarkColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF2A0A02),
    secondary = Color(0xFFFFB59F),
    background = Color(0xFF0E0F13),
    surface = Color(0xFF0E0F13),
    surfaceVariant = Color(0xFF23252D),
    surfaceContainer = Color(0xFF16171D),
    surfaceContainerHigh = Color(0xFF1E2027),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC0391B),
    secondary = Color(0xFF8C4A39),
)

@Composable
fun HarmonicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
