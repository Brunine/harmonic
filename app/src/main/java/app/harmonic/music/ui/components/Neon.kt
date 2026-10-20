package app.harmonic.music.ui.components

import app.harmonic.music.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.harmonic.music.data.model.MusicSource

/** The neon gradient used across the app: primary (pink) to tertiary (cyan). */
@Composable
fun neonBrush(): Brush = Brush.horizontalGradient(
    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
)

/** Coloured glow around a shape (coloured shadows need Android 9+; older phones get a plain shadow). */
fun Modifier.neonGlow(color: Color, shape: Shape, elevation: Dp = 24.dp): Modifier =
    shadow(elevation = elevation, shape = shape, ambientColor = color, spotColor = color, clip = false)

/** "HARMONIC" in the display face, filled with the neon gradient. */
@Composable
fun HarmonicLogo(modifier: Modifier = Modifier) {
    Text(
        "HARMONIC",
        modifier = modifier,
        style = MaterialTheme.typography.titleLarge.copy(brush = neonBrush()),
    )
}

/** Short neon bar under section titles. */
@Composable
fun NeonUnderline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 36.dp, height = 3.dp)
            .clip(CircleShape)
            .background(neonBrush()),
    )
}

/**
 * Synthwave night: a striped sun sinking behind a horizon, with a perspective grid below.
 * Drawn once (no animation), so it costs no battery while the player is open.
 */
@Composable
fun RetroBackdrop(modifier: Modifier = Modifier) {
    val sky = MaterialTheme.colorScheme.background
    val glow = MaterialTheme.colorScheme.secondaryContainer
    val sunTop = MaterialTheme.colorScheme.tertiary
    val sunBottom = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val horizon = size.height * 0.58f
        drawRect(Brush.verticalGradient(0f to sky, 0.55f to glow.copy(alpha = 0.55f), 0.58f to sky))

        // Sun with horizontal stripes cut out of its lower half
        val radius = size.width * 0.34f
        val center = Offset(size.width / 2f, horizon - radius * 0.35f)
        clipRect(bottom = horizon) {
            drawCircle(
                Brush.verticalGradient(
                    listOf(sunTop.copy(alpha = 0.55f), sunBottom.copy(alpha = 0.55f)),
                    startY = center.y - radius,
                    endY = center.y + radius,
                ),
                radius,
                center,
            )
            var y = center.y
            var gap = 3f
            while (y < horizon) {
                drawRect(sky, topLeft = Offset(0f, y), size = Size(size.width, gap))
                y += gap + 10f
                gap += 2f
            }
        }

        // Ground and perspective grid
        drawRect(sky, topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
        drawLine(grid.copy(alpha = 0.8f), Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = 2f)
        val depth = size.height - horizon
        var step = 6f
        var y = horizon + step
        while (y < size.height) {
            val fade = ((y - horizon) / depth).coerceIn(0f, 1f)
            drawLine(grid.copy(alpha = 0.15f + 0.35f * fade), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
            step *= 1.35f
            y += step
        }
        val vanishing = Offset(size.width / 2f, horizon)
        for (i in -10..10) {
            val x = size.width / 2f + i * size.width / 7f
            drawLine(grid.copy(alpha = 0.35f), vanishing, Offset(x, size.height), strokeWidth = 1.5f)
        }
    }
}

/**
 * Seek bar with a neon gradient fill and a glowing thumb. [fraction] is 0..1.
 * Dragging calls [onDrag] continuously and [onRelease] once at the end.
 */
@Composable
fun NeonSeekBar(
    fraction: Float,
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val start = MaterialTheme.colorScheme.primary
    val end = MaterialTheme.colorScheme.tertiary
    var dragValue by remember { mutableFloatStateOf(0f) }
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos -> release((pos.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { pos ->
                        dragValue = (pos.x / size.width).coerceIn(0f, 1f)
                        drag(dragValue)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                        drag(dragValue)
                    },
                    onDragEnd = { release(dragValue) },
                    onDragCancel = { release(dragValue) },
                )
            },
    ) {
        val y = size.height / 2f
        val x = size.width * fraction.coerceIn(0f, 1f)
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        val brush = Brush.horizontalGradient(listOf(start, end), startX = 0f, endX = size.width)
        drawLine(brush, Offset(0f, y), Offset(x, y), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        if (enabled) {
            drawCircle(end.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = Offset(x, y))
            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** Big round play button filled with the neon gradient. */
@Composable
fun NeonPlayButton(isPlaying: Boolean, buffering: Boolean, onClick: () -> Unit, size: Dp = 76.dp) {
    val glow = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .neonGlow(glow, CircleShape, elevation = 20.dp)
                .clip(CircleShape)
                .background(neonBrush())
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (isPlaying) R.string.pause else R.string.play),
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        }
        if (buffering) {
            CircularProgressIndicator(
                Modifier.size(size + 10.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Cover art framed by a thin neon gradient border and a soft glow. */
@Composable
fun NeonArtwork(url: String?, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Box(
        modifier
            .neonGlow(MaterialTheme.colorScheme.primary, shape, elevation = 32.dp)
            .border(2.dp, neonBrush(), shape)
            .clip(shape),
    ) {
        Artwork(url, Modifier.matchParentSize(), corner = 14.dp)
    }
}

/** Colour that identifies each music source in labels. */
fun MusicSource.color(): Color = when (this) {
    MusicSource.YOUTUBE -> Color(0xFFFF3D3D)
    MusicSource.SOUNDCLOUD -> Color(0xFFFF7A1A)
    MusicSource.DEEZER -> Color(0xFFA238FF)
    MusicSource.APPLE -> Color(0xFFFA2D6A)
}

/** "PLAYING FROM DEEZER · VIA YOUTUBE MUSIC" style line. */
@Composable
fun SourceLine(source: MusicSource, modifier: Modifier = Modifier) {
    val text = if (source.playsViaYouTube) {
        stringResource(R.string.playing_from_via, source.label)
    } else {
        stringResource(R.string.playing_from, source.label)
    }.uppercase()
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = source.color(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
