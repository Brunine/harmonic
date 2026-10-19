package app.harmonic.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.harmonic.music.data.model.formatDuration
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalDownloadStates
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.toggleLike
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val connection = c.playerConnection
    val track by connection.current.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val progress by remember(connection) { connection.progress(500) }.collectAsStateWithLifecycle(0L to 0L)
    var showQueue by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    val current = track
    if (current == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val song by remember(current.id) { c.db.songDao().observe(current.id) }.collectAsState(null)
    val download = LocalDownloadStates.current[current.id]
    val (position, duration) = progress

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
            Text(
                "Now playing",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { actions.openTrackMenu(current, emptyList()) }) { Icon(Icons.Filled.MoreVert, "More") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Artwork(
                current.thumbnailUrl,
                Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
                corner = 16.dp,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    current.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    current.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { c.appScope.launch(Dispatchers.Main) { c.toggleLike(current) } }) {
                val liked = song?.liked == true
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    if (liked) "Unlike" else "Like",
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        val sliderValue = dragPosition ?: if (duration > 0) position.toFloat() / duration else 0f
        Slider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { connection.seekTo((it * duration).toLong()) }
                dragPosition = null
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragPosition?.let { (it * duration).toLong() } ?: position
            Text(formatDuration(shown / 1000), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(duration / 1000), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = connection::toggleShuffle) {
                Icon(
                    Icons.Filled.Shuffle, "Shuffle",
                    tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp))
            }
            Box(contentAlignment = Alignment.Center) {
                FilledIconButton(
                    onClick = connection::togglePlayPause,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        Modifier.size(40.dp),
                    )
                }
                if (state.isBuffering) {
                    CircularProgressIndicator(Modifier.size(80.dp), strokeWidth = 2.dp)
                }
            }
            IconButton(onClick = connection::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp))
            }
            IconButton(onClick = connection::cycleRepeat) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = {
                when {
                    download?.isDone == true -> actions.showMessage("Available offline")
                    download?.isActive == true -> actions.showMessage("Downloading… ${download.percent.toInt()}%")
                    else -> {
                        c.downloads.download(listOf(current))
                        actions.showMessage("Downloading “${current.title}”")
                    }
                }
            }) {
                Icon(
                    if (download?.isDone == true) Icons.Filled.DownloadDone else Icons.Filled.Download,
                    "Download",
                    tint = if (download?.isDone == true) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
            IconButton(onClick = { actions.addToPlaylist(listOf(current)) }) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist")
            }
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue")
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
}

@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val connection = c.playerConnection
    val queue by connection.queue.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 2).coerceAtLeast(0))

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Up next • ${queue.size} songs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState) {
            itemsIndexed(queue) { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackRow(
                        t,
                        onClick = { connection.playAt(i) },
                        onMore = null,
                        modifier = Modifier.weight(1f),
                        isCurrent = i == state.currentIndex,
                    )
                    if (i != state.currentIndex) {
                        IconButton(onClick = { connection.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove") }
                    }
                }
            }
        }
    }
}
