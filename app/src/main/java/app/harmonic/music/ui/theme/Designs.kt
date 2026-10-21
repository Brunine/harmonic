package app.harmonic.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.harmonic.music.R
import app.harmonic.music.data.prefs.ThemePalette

/**
 * The app's looks. Each has its own title font and player backdrop; colours come from
 * [designScheme] (or, for the plain colour palettes, from their key colour with Synthwave's
 * font and backdrop).
 */
enum class Design { SYNTHWAVE, CYBER_NOIR, NEO_GOTHIC, CYBERPUNK, MATRIX }

val ThemePalette.design: Design
    get() = when (this) {
        ThemePalette.CYBER_NOIR -> Design.CYBER_NOIR
        ThemePalette.NEO_GOTHIC -> Design.NEO_GOTHIC
        ThemePalette.CYBERPUNK -> Design.CYBERPUNK
        ThemePalette.MATRIX -> Design.MATRIX
        else -> Design.SYNTHWAVE
    }

/** The design in use, for drawings such as the player backdrop. */
val LocalDesign = staticCompositionLocalOf { Design.SYNTHWAVE }

// -------------------------------------------------------------------------------------------
// Fonts (all SIL Open Font License, see licenses/)
// -------------------------------------------------------------------------------------------

private val BebasNeue = FontFamily(Font(R.font.bebas_neue, FontWeight.Normal), Font(R.font.bebas_neue, FontWeight.Bold))
private val Cinzel = FontFamily(Font(R.font.cinzel_semibold, FontWeight.Medium), Font(R.font.cinzel_bold, FontWeight.Bold))
private val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_semibold, FontWeight.Medium),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)
private val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal), Font(R.font.share_tech_mono, FontWeight.Bold))

/** Display face, size adjustment (some faces run small or large) and letter spacing. */
private data class TitleFace(val family: FontFamily, val sizeDelta: Int, val spacing: TextUnit)

private fun Design.face(): TitleFace = when (this) {
    Design.SYNTHWAVE -> TitleFace(Orbitron, 0, 0.06.em)
    // Tall condensed capitals: a little bigger reads the same.
    Design.CYBER_NOIR -> TitleFace(BebasNeue, 4, 0.05.em)
    Design.NEO_GOTHIC -> TitleFace(Cinzel, 0, 0.04.em)
    Design.CYBERPUNK -> TitleFace(ChakraPetch, 1, 0.03.em)
    Design.MATRIX -> TitleFace(ShareTechMono, 0, 0.02.em)
}

private val base = Typography()

/**
 * Titles and headings in the design's display face; body text always stays in the system
 * font for readability. [systemFont] uses the system font for titles too.
 */
fun typographyFor(design: Design, systemFont: Boolean): Typography {
    if (systemFont) {
        fun TextStyle.plain(size: Int) = copy(fontWeight = FontWeight.Bold, fontSize = size.sp)
        return base.copy(
            displayLarge = base.displayLarge.plain(48),
            displayMedium = base.displayMedium.plain(40),
            displaySmall = base.displaySmall.plain(32),
            headlineLarge = base.headlineLarge.plain(28),
            headlineMedium = base.headlineMedium.plain(24),
            headlineSmall = base.headlineSmall.plain(20),
            titleLarge = base.titleLarge.plain(18),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
    val face = design.face()
    fun TextStyle.display(size: Int) = copy(
        fontFamily = face.family,
        fontWeight = FontWeight.Bold,
        fontSize = (size + face.sizeDelta).sp,
        letterSpacing = face.spacing,
    )
    return base.copy(
        displayLarge = base.displayLarge.display(48),
        displayMedium = base.displayMedium.display(40),
        displaySmall = base.displaySmall.display(32),
        headlineLarge = base.headlineLarge.display(28),
        headlineMedium = base.headlineMedium.display(24),
        headlineSmall = base.headlineSmall.display(20),
        titleLarge = base.titleLarge.display(18),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
    )
}

// -------------------------------------------------------------------------------------------
// Colours (dark; in light mode these designs use a scheme built from their key colour)
// -------------------------------------------------------------------------------------------

/** Two colours that sum the design up, for the settings swatch. */
fun designSwatch(palette: ThemePalette): List<Color>? = when (palette) {
    ThemePalette.SYNTHWAVE -> listOf(Color(0xFFFF2E97), Color(0xFF2DE2E6))
    ThemePalette.CYBER_NOIR -> listOf(Color(0xFFE0263A), Color(0xFFD9DEE5))
    ThemePalette.NEO_GOTHIC -> listOf(Color(0xFFB0213A), Color(0xFFC9A86A))
    ThemePalette.CYBERPUNK -> listOf(Color(0xFFFCEE0A), Color(0xFF00F0FF))
    ThemePalette.MATRIX -> listOf(Color(0xFF00FF41), Color(0xFFB9FFE8))
    else -> null
}

/**
 * Dark schemes for the designs. The neon gradient used across the app runs from primary to
 * tertiary, so each pair is that design's signature.
 */
fun designScheme(design: Design, pureBlack: Boolean): ColorScheme? {
    fun bg(color: Long) = if (pureBlack) Color.Black else Color(color)
    return when (design) {
        Design.SYNTHWAVE -> null

        // Black-and-white film with one colour left in: red.
        Design.CYBER_NOIR -> darkColorScheme(
            primary = Color(0xFFE0263A), onPrimary = Color.White,
            primaryContainer = Color(0xFF4A0D16), onPrimaryContainer = Color(0xFFFFD9DC),
            secondary = Color(0xFF9DA3AD), onSecondary = Color(0xFF15171A),
            secondaryContainer = Color(0xFF2A2D33), onSecondaryContainer = Color(0xFFE2E5EA),
            tertiary = Color(0xFFD9DEE5), onTertiary = Color(0xFF16181C),
            tertiaryContainer = Color(0xFF3A3F47), onTertiaryContainer = Color(0xFFF2F4F7),
            background = bg(0xFF09090A), onBackground = Color(0xFFE9E9EA),
            surface = bg(0xFF09090A), onSurface = Color(0xFFE9E9EA),
            surfaceVariant = Color(0xFF222326), onSurfaceVariant = Color(0xFFB0B2B8),
            outline = Color(0xFF6E7178), outlineVariant = Color(0xFF2E3034),
            surfaceTint = Color(0xFFE0263A), inversePrimary = Color(0xFF9E0F22),
            surfaceContainerLowest = bg(0xFF050505), surfaceContainerLow = bg(0xFF0F0F11),
            surfaceContainer = Color(0xFF141416), surfaceContainerHigh = Color(0xFF1B1B1E),
            surfaceContainerHighest = Color(0xFF232327),
        )

        // Soot, wrought iron, cathedral glass: crimson and old gold.
        Design.NEO_GOTHIC -> darkColorScheme(
            primary = Color(0xFFC8304A), onPrimary = Color(0xFF2B0008),
            primaryContainer = Color(0xFF4E0E1B), onPrimaryContainer = Color(0xFFFFDADF),
            secondary = Color(0xFF8C857C), onSecondary = Color(0xFF1C1A17),
            secondaryContainer = Color(0xFF2E2A26), onSecondaryContainer = Color(0xFFE8E1D7),
            tertiary = Color(0xFFC9A86A), onTertiary = Color(0xFF2A1F05),
            tertiaryContainer = Color(0xFF4A3B1C), onTertiaryContainer = Color(0xFFF6E5C2),
            background = bg(0xFF0E0B0B), onBackground = Color(0xFFEDE3DA),
            surface = bg(0xFF0E0B0B), onSurface = Color(0xFFEDE3DA),
            surfaceVariant = Color(0xFF29211F), onSurfaceVariant = Color(0xFFC2B5A8),
            outline = Color(0xFF7A6E63), outlineVariant = Color(0xFF3A302B),
            surfaceTint = Color(0xFFC8304A), inversePrimary = Color(0xFF8E1428),
            surfaceContainerLowest = bg(0xFF080606), surfaceContainerLow = bg(0xFF141010),
            surfaceContainer = Color(0xFF1A1514), surfaceContainerHigh = Color(0xFF221B19),
            surfaceContainerHighest = Color(0xFF2B2320),
        )

        // Hazard yellow, electric cyan, a splash of magenta on night city black.
        Design.CYBERPUNK -> darkColorScheme(
            primary = Color(0xFFFCEE0A), onPrimary = Color(0xFF1A1800),
            primaryContainer = Color(0xFF4A4600), onPrimaryContainer = Color(0xFFFFF9B0),
            secondary = Color(0xFFFF2A6D), onSecondary = Color(0xFF3B0016),
            secondaryContainer = Color(0xFF5C0F2E), onSecondaryContainer = Color(0xFFFFD9E3),
            tertiary = Color(0xFF00F0FF), onTertiary = Color(0xFF00363A),
            tertiaryContainer = Color(0xFF004A52), onTertiaryContainer = Color(0xFFB3FBFF),
            background = bg(0xFF0A0A12), onBackground = Color(0xFFEDEDF5),
            surface = bg(0xFF0A0A12), onSurface = Color(0xFFEDEDF5),
            surfaceVariant = Color(0xFF1F2030), onSurfaceVariant = Color(0xFFB9BBD0),
            outline = Color(0xFF6F7290), outlineVariant = Color(0xFF2C2E42),
            surfaceTint = Color(0xFFFCEE0A), inversePrimary = Color(0xFF6E6800),
            surfaceContainerLowest = bg(0xFF05050A), surfaceContainerLow = bg(0xFF10101A),
            surfaceContainer = Color(0xFF151521), surfaceContainerHigh = Color(0xFF1C1C2B),
            surfaceContainerHighest = Color(0xFF242436),
        )

        // Phosphor green code on black, with Y2K chrome-mint highlights.
        Design.MATRIX -> darkColorScheme(
            primary = Color(0xFF00FF41), onPrimary = Color(0xFF00210A),
            primaryContainer = Color(0xFF00451A), onPrimaryContainer = Color(0xFFB5FFC4),
            secondary = Color(0xFF6FD08C), onSecondary = Color(0xFF00210E),
            secondaryContainer = Color(0xFF103D22), onSecondaryContainer = Color(0xFFC8F5D4),
            tertiary = Color(0xFFB9FFE8), onTertiary = Color(0xFF00372A),
            tertiaryContainer = Color(0xFF0F4F40), onTertiaryContainer = Color(0xFFDDFFF4),
            background = bg(0xFF000500), onBackground = Color(0xFFD7FFE0),
            surface = bg(0xFF000500), onSurface = Color(0xFFD7FFE0),
            surfaceVariant = Color(0xFF0F2214), onSurfaceVariant = Color(0xFF9FD3AA),
            outline = Color(0xFF4F8A5E), outlineVariant = Color(0xFF173320),
            surfaceTint = Color(0xFF00FF41), inversePrimary = Color(0xFF006E1D),
            surfaceContainerLowest = bg(0xFF000300), surfaceContainerLow = bg(0xFF030D05),
            surfaceContainer = Color(0xFF071209), surfaceContainerHigh = Color(0xFF0B190E),
            surfaceContainerHighest = Color(0xFF102114),
        )
    }
}
