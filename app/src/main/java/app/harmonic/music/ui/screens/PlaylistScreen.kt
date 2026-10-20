package app.harmonic.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.db.PlaylistEntry
import app.harmonic.music.ui.ExtraAction
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalDownloadStates
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TextInputDialog
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.downloadMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class PlaylistViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val playlist = c.db.playlistDao().observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val entries = c.db.playlistDao().entries(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PlaylistEntry>())

    fun remove(entryId: Long) = viewModelScope.launch(Dispatchers.IO) { c.db.playlistDao().removeEntry(entryId) }
    fun rename(name: String) = viewModelScope.launch(Dispatchers.IO) { c.db.playlistDao().rename(id, name.trim()) }
    fun delete() = c.appScope.launch(Dispatchers.IO) { c.db.playlistDao().delete(id) }
    fun saveOrder(entryIds: List<Long>) = c.appScope.launch(Dispatchers.IO) { c.db.playlistDao().reorder(entryIds) }
}

@Composable
fun PlaylistScreen(id: Long, onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val res = LocalContext.current.resources
    val haptics = LocalHapticFeedback.current
    val vm: PlaylistViewModel = viewModel { PlaylistViewModel(c, id) }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val saved by vm.entries.collectAsStateWithLifecycle()
    val downloads = LocalDownloadStates.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // Local copy so dragging feels instant; saved to the database when the drag ends.
    var entries by remember { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (!dragging) entries = saved }

    val tracks = entries.map { it.song.toTrack() }
    val downloadedCount = tracks.count { downloads[it.id]?.isDone == true }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIdx = entries.indexOfFirst { it.entryId == from.key }
        val toIdx = entries.indexOfFirst { it.entryId == to.key }
        if (fromIdx >= 0 && toIdx >= 0) {
            entries = entries.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_playlist)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            state = listState,
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.firstOrNull()?.thumbnailUrl, Modifier.size(120.dp), corner = 10.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(playlist?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                pluralStringResource(R.plurals.songs_offline, tracks.size, tracks.size, downloadedCount),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { c.playerConnection.play(tracks) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text(stringResource(R.string.play))
                        }
                        FilledTonalButton(onClick = { c.playerConnection.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle))
                        }
                        FilledTonalButton(
                            onClick = {
                                val skipped = c.downloads.download(tracks)
                                actions.showMessage(res.downloadMessage(tracks.size - downloadedCount - skipped, skipped))
                            },
                            enabled = downloadedCount < tracks.size,
                        ) {
                            Icon(Icons.Filled.Download, stringResource(R.string.download_all))
                        }
                    }
                    if (tracks.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.drag_to_reorder),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (tracks.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        stringResource(R.string.empty_playlist),
                        stringResource(R.string.empty_playlist_body),
                    )
                }
            }
            items(entries, key = { it.entryId }) { e ->
                ReorderableItem(reorderState, key = e.entryId) { isDragging ->
                    val track = e.song.toTrack()
                    Surface(
                        color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                        shadowElevation = if (isDragging) 8.dp else 0.dp,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TrackRow(
                                track,
                                onClick = { c.playerConnection.play(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) },
                                onMore = {
                                    actions.openTrackMenu(
                                        track,
                                        listOf(
                                            ExtraAction(res.getString(R.string.remove_from_playlist), Icons.Filled.RemoveCircleOutline) {
                                                vm.remove(e.entryId)
                                            },
                                        ),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        dragging = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        dragging = false
                                        vm.saveOrder(entries.map { it.entryId })
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

    if (renaming) {
        TextInputDialog(
            stringResource(R.string.rename_playlist),
            stringResource(R.string.name),
            stringResource(R.string.save),
            initial = playlist?.name.orEmpty(),
            onDismiss = { renaming = false },
        ) { name ->
            renaming = false
            vm.rename(name)
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_playlist_title, playlist?.name.orEmpty())) },
            text = { Text(stringResource(R.string.delete_playlist_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    vm.delete()
                    onBack()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
