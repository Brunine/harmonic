package app.harmonic.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.harmonic.music.ui.theme.Design
import app.harmonic.music.ui.theme.LocalDesign
import kotlin.random.Random

/**
 * The player's background for the current design. Every backdrop is a still picture drawn
 * once (fixed random seeds, no animation), so it costs no battery while the player is open.
 */
@Composable
fun ThemeBackdrop(modifier: Modifier = Modifier) {
    when (LocalDesign.current) {
        Design.SYNTHWAVE -> RetroBackdrop(modifier)
        Design.CYBER_NOIR -> NoirBackdrop(modifier)
        Design.NEO_GOTHIC -> GothicBackdrop(modifier)
        Design.CYBERPUNK -> CyberpunkBackdrop(modifier)
        Design.MATRIX -> MatrixBackdrop(modifier)
    }
}

/** Rain at night under a street lamp, light through blinds, one red neon sign. */
@Composable
private fun NoirBackdrop(modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val red = MaterialTheme.colorScheme.primary
    val silver = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(bg, Color(0xFF16171A), bg)))
        // Street lamp glow, top right
        drawCircle(
            Brush.radialGradient(
                listOf(silver.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 0.85f, size.height * 0.08f),
                radius = size.width * 0.75f,
            ),
            radius = size.width * 0.75f,
            center = Offset(size.width * 0.85f, size.height * 0.08f),
        )
        // Red neon sign reflected on wet glass
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color.Transparent, red.copy(alpha = 0.35f), Color.Transparent)),
            topLeft = Offset(size.width * 0.05f, size.height * 0.30f),
            size = Size(size.width * 0.5f, size.height * 0.012f),
            cornerRadius = CornerRadius(8f),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, red.copy(alpha = 0.10f), Color.Transparent),
                startY = size.height * 0.24f,
                endY = size.height * 0.40f,
            ),
        )
        // Rain
        val rnd = Random(7)
        repeat(170) {
            val x = rnd.nextFloat() * size.width * 1.2f
            val y = rnd.nextFloat() * size.height
            val len = 18f + rnd.nextFloat() * 46f
            drawLine(
                silver.copy(alpha = 0.05f + rnd.nextFloat() * 0.16f),
                Offset(x, y),
                Offset(x - len * 0.22f, y + len),
                strokeWidth = 1.2f,
            )
        }
        // Light through venetian blinds across the lower half
        val top = size.height * 0.55f
        var y = top
        var i = 0
        while (y < size.height) {
            val band = 14f + i * 1.5f
            drawRect(silver.copy(alpha = 0.035f), topLeft = Offset(0f, y), size = Size(size.width, band))
            y += band * 2.2f
            i++
        }
    }
}

/** A cathedral's pointed-arch window with a rose window, over riveted iron girders. */
@Composable
private fun GothicBackdrop(modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val crimson = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.colorScheme.tertiary
    val iron = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(0f to bg, 0.7f to crimson.copy(alpha = 0.10f), 1f to bg))

        val cx = size.width / 2f
        val w = size.width * 0.62f
        val bottom = size.height * 0.80f
        val spring = size.height * 0.36f
        val apex = size.height * 0.06f
        val stroke = Stroke(width = 3f)

        // Stained glass glow inside the arch, then the stone outline
        val window = pointedArch(cx, w, apex, spring, bottom)
        drawPath(window, Brush.verticalGradient(listOf(crimson.copy(alpha = 0.16f), gold.copy(alpha = 0.06f))))
        drawPath(window, gold.copy(alpha = 0.40f), style = stroke)

        // Three lancets
        val lancetW = w / 3.2f
        for (k in -1..1) {
            val lx = cx + k * w / 3f
            drawPath(pointedArch(lx, lancetW, spring + size.height * 0.04f, spring + size.height * 0.12f, bottom), gold.copy(alpha = 0.22f), style = Stroke(2f))
        }

        // Rose window with tracery spokes
        val roseCenter = Offset(cx, apex + (spring - apex) * 0.62f)
        val roseR = w * 0.22f
        drawCircle(gold.copy(alpha = 0.35f), roseR, roseCenter, style = stroke)
        drawCircle(gold.copy(alpha = 0.25f), roseR * 0.35f, roseCenter, style = Stroke(2f))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val inner = Offset(roseCenter.x + (roseR * 0.35f * Math.cos(a)).toFloat(), roseCenter.y + (roseR * 0.35f * Math.sin(a)).toFloat())
            val outer = Offset(roseCenter.x + (roseR * Math.cos(a)).toFloat(), roseCenter.y + (roseR * Math.sin(a)).toFloat())
            drawLine(gold.copy(alpha = 0.25f), inner, outer, strokeWidth = 1.5f)
        }

        // Iron girder with X trusses and rivets along the bottom
        val girderTop = size.height * 0.86f
        val girderBottom = size.height * 0.96f
        drawRect(iron.copy(alpha = 0.9f), topLeft = Offset(0f, girderTop), size = Size(size.width, 3f))
        drawRect(iron.copy(alpha = 0.9f), topLeft = Offset(0f, girderBottom), size = Size(size.width, 3f))
        val bay = size.width / 6f
        for (b in 0 until 6) {
            val x0 = b * bay
            drawLine(iron, Offset(x0, girderTop), Offset(x0 + bay, girderBottom), strokeWidth = 2f)
            drawLine(iron, Offset(x0, girderBottom), Offset(x0 + bay, girderTop), strokeWidth = 2f)
            drawCircle(gold.copy(alpha = 0.30f), 2.5f, Offset(x0 + 6f, girderTop + 1.5f))
            drawCircle(gold.copy(alpha = 0.30f), 2.5f, Offset(x0 + 6f, girderBottom + 1.5f))
        }
    }
}

/** Pointed (Gothic) arch: straight sides up to [spring], two curves meeting at [apex]. */
private fun pointedArch(cx: Float, width: Float, apex: Float, spring: Float, bottom: Float): Path = Path().apply {
    val left = cx - width / 2f
    val right = cx + width / 2f
    moveTo(left, bottom)
    lineTo(left, spring)
    cubicTo(left, spring - (spring - apex) * 0.55f, cx - width * 0.18f, apex + (spring - apex) * 0.15f, cx, apex)
    cubicTo(cx + width * 0.18f, apex + (spring - apex) * 0.15f, right, spring - (spring - apex) * 0.55f, right, spring)
    lineTo(right, bottom)
    close()
}

/** Night city skyline with lit windows, glitch bars and a hazard stripe. */
@Composable
private fun CyberpunkBackdrop(modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val yellow = MaterialTheme.colorScheme.primary
    val magenta = MaterialTheme.colorScheme.secondary
    val cyan = MaterialTheme.colorScheme.tertiary
    val building = MaterialTheme.colorScheme.surfaceContainerLowest
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(0f to bg, 0.62f to magenta.copy(alpha = 0.18f), 0.66f to bg))

        // Glitch bars
        val rnd = Random(2077)
        repeat(7) {
            val y = size.height * (0.06f + rnd.nextFloat() * 0.45f)
            val x = rnd.nextFloat() * size.width * 0.6f
            val w = size.width * (0.15f + rnd.nextFloat() * 0.4f)
            val c = if (it % 2 == 0) cyan else magenta
            drawRect(c.copy(alpha = 0.18f), topLeft = Offset(x, y), size = Size(w, 3f + rnd.nextFloat() * 5f))
            drawRect(c.copy(alpha = 0.10f), topLeft = Offset(x + 8f, y + 6f), size = Size(w * 0.6f, 2f))
        }

        // Skyline
        val ground = size.height * 0.93f
        var x = -10f
        while (x < size.width) {
            val w = size.width * (0.07f + rnd.nextFloat() * 0.10f)
            val h = size.height * (0.18f + rnd.nextFloat() * 0.32f)
            val top = ground - h
            drawRect(building, topLeft = Offset(x, top), size = Size(w, h))
            drawRect(cyan.copy(alpha = 0.25f), topLeft = Offset(x, top), size = Size(w, 1.5f))
            // Lit windows
            var wy = top + 10f
            while (wy < ground - 10f) {
                var wx = x + 5f
                while (wx < x + w - 8f) {
                    if (rnd.nextFloat() < 0.22f) {
                        val c = if (rnd.nextFloat() < 0.7f) yellow else cyan
                        drawRect(c.copy(alpha = 0.25f + rnd.nextFloat() * 0.35f), topLeft = Offset(wx, wy), size = Size(4f, 3f))
                    }
                    wx += 9f
                }
                wy += 11f
            }
            x += w + 3f
        }

        // Hazard stripe along the bottom edge
        clipRect(top = ground, bottom = size.height) {
            drawRect(yellow.copy(alpha = 0.85f), topLeft = Offset(0f, ground), size = Size(size.width, size.height - ground))
            val stripe = 22f
            var sx = -size.height
            while (sx < size.width + size.height) {
                val path = Path().apply {
                    moveTo(sx, size.height)
                    lineTo(sx + stripe, size.height)
                    lineTo(sx + stripe + (size.height - ground), ground)
                    lineTo(sx + (size.height - ground), ground)
                    close()
                }
                drawPath(path, bg.copy(alpha = 0.9f))
                sx += stripe * 2
            }
        }
    }
}

/** Digital rain: columns of glyphs falling, bright at the head and fading behind. */
@Composable
private fun MatrixBackdrop(modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val green = MaterialTheme.colorScheme.primary
    val mint = MaterialTheme.colorScheme.tertiary
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val glyphs = remember(measurer) {
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        GLYPHS.map { measurer.measure(it.toString(), style) }
    }
    Canvas(modifier) {
        drawRect(bg)
        val cell = glyphs.first().size
        val colW = cell.width.toFloat() * 1.25f
        val rowH = cell.height.toFloat() * 0.95f
        val columns = (size.width / colW).toInt() + 1
        val rows = (size.height / rowH).toInt() + 1
        val rnd = Random(1999)
        for (c in 0 until columns) {
            if (rnd.nextFloat() < 0.35f) continue
            val head = rnd.nextInt(rows)
            val trail = 6 + rnd.nextInt(18)
            for (k in 0..trail) {
                val r = head - k
                if (r < 0) break
                val glyph = glyphs[rnd.nextInt(glyphs.size)]
                val color = if (k == 0) mint else green
                val alpha = if (k == 0) 0.9f else (0.55f * (1f - k.toFloat() / trail)).coerceAtLeast(0.05f)
                drawText(glyph, color = color, topLeft = Offset(c * colW, r * rowH), alpha = alpha)
            }
        }
        vignette(bg)
    }
}

private fun DrawScope.vignette(bg: Color) {
    drawRect(Brush.verticalGradient(0f to bg.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to bg.copy(alpha = 0.8f)))
}

/** Half-width katakana, digits and a few symbols, as in the films. */
private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789:=*+-<>"
