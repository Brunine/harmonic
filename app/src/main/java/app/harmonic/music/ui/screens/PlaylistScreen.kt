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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.data.db.PlaylistEntry
import app.harmonic.music.ui.ExtraAction
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalDownloadStates
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TextInputDialog
import app.harmonic.music.ui.components.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val playlist = c.db.playlistDao().observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val entries = c.db.playlistDao().entries(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<PlaylistEntry>())

    fun remove(entryId: Long) = viewModelScope.launch(Dispatchers.IO) { c.db.playlistDao().removeEntry(entryId) }
    fun rename(name: String) = viewModelScope.launch(Dispatchers.IO) { c.db.playlistDao().rename(id, name.trim()) }
    fun delete() = c.appScope.launch(Dispatchers.IO) { c.db.playlistDao().delete(id) }
}

@Composable
fun PlaylistScreen(id: Long, onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val vm: PlaylistViewModel = viewModel { PlaylistViewModel(c, id) }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val downloads = LocalDownloadStates.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val tracks = entries.map { it.song.toTrack() }
    val downloadedCount = tracks.count { downloads[it.id]?.isDone == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete playlist") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(tracks.firstOrNull()?.thumbnailUrl, Modifier.size(120.dp), corner = 10.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                playlist?.name.orEmpty(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${tracks.size} songs • $downloadedCount offline",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { c.playerConnection.play(tracks) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text("Play")
                        }
                        FilledTonalButton(onClick = { c.playerConnection.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Filled.Shuffle, "Shuffle")
                        }
                        FilledTonalButton(
                            onClick = {
                                c.downloads.download(tracks)
                                actions.showMessage("Downloading ${tracks.size - downloadedCount} songs")
                            },
                            enabled = downloadedCount < tracks.size,
                        ) {
                            Icon(Icons.Filled.Download, "Download all")
                        }
                    }
                }
            }
            if (tracks.isEmpty()) {
                item {
                    EmptyState(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        "Empty playlist",
                        "Use “Add to playlist” from any song’s menu, online or downloaded.",
                    )
                }
            }
            itemsIndexed(entries, key = { _, e -> e.entryId }) { i, e ->
                val track = tracks[i]
                TrackRow(
                    track,
                    onClick = { c.playerConnection.play(tracks, i) },
                    onMore = {
                        actions.openTrackMenu(
                            track,
                            listOf(ExtraAction("Remove from playlist", Icons.Filled.RemoveCircleOutline) { vm.remove(e.entryId) }),
                        )
                    },
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            "Rename playlist", "Name", "Save",
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
            title = { Text("Delete “${playlist?.name.orEmpty()}”?") },
            text = { Text("Songs stay in your library and downloads are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    vm.delete()
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}
