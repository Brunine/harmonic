package app.harmonic.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.harmonic.music.data.model.source
import app.harmonic.music.ui.LocalContainer

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val connection = LocalContainer.current.playerConnection
    val track by connection.current.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val current = track ?: return
    // A slow 1s tick is plenty for a thin progress line.
    val progress by remember(connection) { connection.progress(1_000) }
        .collectAsStateWithLifecycle(0L to 0L)
    val start = MaterialTheme.colorScheme.primary
    val end = MaterialTheme.colorScheme.tertiary
    val track2 = MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen),
    ) {
        val (pos, dur) = progress
        val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        // Neon progress line along the top edge
        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(track2)
            drawLine(
                Brush.horizontalGradient(listOf(start, end), endX = size.width),
                Offset(0f, size.height / 2),
                Offset(size.width * fraction, size.height / 2),
                strokeWidth = size.height,
            )
        }
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(current.thumbnailUrl, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(current.source)
                    Text(
                        current.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            NeonPlayButton(state.isPlaying, state.isBuffering, connection::togglePlayPause, size = 40.dp)
            IconButton(onClick = connection::next, enabled = state.hasNext) {
                Icon(Icons.Filled.SkipNext, "Next")
            }
        }
    }
}
