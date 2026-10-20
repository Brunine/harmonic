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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.harmonic.music.R
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.formatDuration
import app.harmonic.music.data.model.source
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalDownloadStates
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.components.NeonArtwork
import app.harmonic.music.ui.components.NeonPlayButton
import app.harmonic.music.ui.components.NeonSeekBar
import app.harmonic.music.ui.components.RetroBackdrop
import app.harmonic.music.ui.components.SleepTimerDialog
import app.harmonic.music.ui.components.SleepTimerIcon
import app.harmonic.music.ui.components.SourceLine
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.components.rememberSleepRemaining
import app.harmonic.music.ui.toggleLike
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val res = LocalContext.current.resources
    val connection = c.playerConnection
    val track by connection.current.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()
    val progress by remember(connection) { connection.progress(500) }.collectAsStateWithLifecycle(0L to 0L)
    val sleepState by c.sleepTimer.state.collectAsStateWithLifecycle()
    val sleepRemaining = rememberSleepRemaining(sleepState)
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }

    val current = track
    if (current == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val song by remember(current.id) { c.db.songDao().observe(current.id) }.collectAsState(null)
    val download = LocalDownloadStates.current[current.id]
    val (position, duration) = progress

    Box(Modifier.fillMaxSize()) {
        RetroBackdrop(Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.close)) }
                SourceLine(current.source, Modifier.weight(1f))
                IconButton(onClick = { actions.openTrackMenu(current, emptyList()) }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.more))
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                NeonArtwork(
                    current.thumbnailUrl,
                    Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true),
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current.title.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { c.appScope.launch(Dispatchers.Main) { c.toggleLike(current) } }) {
                    val liked = song?.liked == true
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        stringResource(if (liked) R.string.unlike else R.string.like),
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            val fraction = dragPosition ?: if (duration > 0) position.toFloat() / duration else 0f
            NeonSeekBar(
                fraction = fraction,
                enabled = duration > 0,
                onDrag = { dragPosition = it },
                onRelease = {
                    connection.seekTo((it * duration).toLong())
                    dragPosition = null
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = dragPosition?.let { (it * duration).toLong() } ?: position
                Text(formatDuration(shown / 1000), style = MaterialTheme.typography.labelMedium)
                Text(formatDuration(duration / 1000), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = connection::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle, stringResource(R.string.shuffle),
                        tint = if (state.shuffle) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp))
                }
                NeonPlayButton(state.isPlaying, state.isBuffering, connection::togglePlayPause)
                IconButton(onClick = connection::next, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.next), Modifier.size(36.dp))
                }
                IconButton(onClick = connection::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        stringResource(R.string.repeat),
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    when {
                        download?.isDone == true -> actions.showMessage(res.getString(R.string.available_offline))
                        download?.isActive == true ->
                            actions.showMessage(res.getString(R.string.downloading_percent, download.percent.toInt()))
                        else -> {
                            if (c.downloads.download(listOf(current)) > 0) {
                                actions.showMessage(res.getString(R.string.soundcloud_no_download))
                            } else {
                                actions.showMessage(res.getString(R.string.downloading_title, current.title))
                            }
                        }
                    }
                }) {
                    Icon(
                        if (download?.isDone == true) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        stringResource(R.string.download),
                        tint = if (download?.isDone == true) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                    )
                }
                IconButton(onClick = { actions.addToPlaylist(listOf(current)) }) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showSleep = true }) { SleepTimerIcon() }
                    sleepRemaining?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue))
                }
            }
        }
    }

    if (showQueue) QueueSheet(onDismiss = { showQueue = false })
    if (showSleep) SleepTimerDialog(onDismiss = { showSleep = false })
}

/** A queue row with a key that stays unique even when the same song is queued twice. */
private data class QueueEntry(val index: Int, val track: Track)

@Composable
private fun QueueSheet(onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val connection = c.playerConnection
    val haptics = LocalHapticFeedback.current
    val queue by connection.queue.collectAsStateWithLifecycle()
    val state by connection.state.collectAsStateWithLifecycle()

    var entries by remember { mutableStateOf(queue.mapIndexed { i, t -> QueueEntry(i, t) }) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(queue) { if (!dragging) entries = queue.mapIndexed { i, t -> QueueEntry(i, t) } }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 2).coerceAtLeast(0))
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = entries.indexOfFirst { it.index == from.key }
        val toIdx = entries.indexOfFirst { it.index == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            entries = entries.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            pluralStringResource(R.plurals.up_next, queue.size, queue.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = listState) {
            items(entries, key = { it.index }) { entry ->
                ReorderableItem(reorderState, key = entry.index) { isDragging ->
                    val isCurrent = entry.index == state.currentIndex
                    Surface(
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                        shadowElevation = if (isDragging) 8.dp else 0.dp,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TrackRow(
                                entry.track,
                                onClick = { connection.playAt(entry.index) },
                                onMore = null,
                                modifier = Modifier.weight(1f),
                                isCurrent = isCurrent,
                            )
                            if (!isCurrent) {
                                IconButton(onClick = { connection.removeAt(entry.index) }) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.remove))
                                }
                            }
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        dragging = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        dragging = false
                                        // Apply the drop to the player: from where it was to where it is now.
                                        val to = entries.indexOf(entry)
                                        if (to >= 0 && to != entry.index) connection.moveItem(entry.index, to)
                                    },
                                ),
                            ) {
                                Icon(Icons.Filled.DragHandle, stringResource(R.string.reorder))
                            }
                        }
                    }
                }
            }
        }
    }
}
