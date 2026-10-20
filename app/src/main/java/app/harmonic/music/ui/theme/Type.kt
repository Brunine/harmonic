package app.harmonic.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.harmonic.music.R

/** Wide, retro-futuristic display face for titles and headings. Body text stays the system font. */
val Orbitron = FontFamily(
    Font(R.font.orbitron_medium, FontWeight.Medium),
    Font(R.font.orbitron_bold, FontWeight.Bold),
)

private val base = Typography()

private fun TextStyle.neon(size: Int, weight: FontWeight = FontWeight.Bold) = copy(
    fontFamily = Orbitron,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = 0.06.em,
)

val HarmonicTypography = base.copy(
    displayLarge = base.displayLarge.neon(48),
    displayMedium = base.displayMedium.neon(40),
    displaySmall = base.displaySmall.neon(32),
    headlineLarge = base.headlineLarge.neon(28),
    headlineMedium = base.headlineMedium.neon(24),
    headlineSmall = base.headlineSmall.neon(20),
    titleLarge = base.titleLarge.neon(18),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
)
