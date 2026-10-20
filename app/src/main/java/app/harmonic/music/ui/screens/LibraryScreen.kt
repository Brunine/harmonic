package app.harmonic.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.harmonic.music.data.db.PlaylistSummary
import app.harmonic.music.data.model.Track
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.UiActions
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TextInputDialog
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.createPlaylist
import app.harmonic.music.ui.importPlaylist
import app.harmonic.music.ui.extractUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val playlists = c.db.playlistDao().summaries().state(emptyList())
    val downloads = c.db.songDao().downloaded().map { l -> l.map { it.toTrack() } }.state(emptyList())
    val liked = c.db.songDao().liked().map { l -> l.map { it.toTrack() } }.state(emptyList())
    val history = c.db.songDao().recent(200).map { l -> l.map { it.toTrack() } }.state(emptyList())

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { c.db.songDao().clearHistory() }
}

private enum class LibraryTab(val label: String) { PLAYLISTS("Playlists"), DOWNLOADS("Downloads"), LIKED("Liked"), HISTORY("History") }

@Composable
fun LibraryScreen() {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val vm: LibraryViewModel = viewModel { LibraryViewModel(c) }
    var tab by rememberSaveable { mutableStateOf(LibraryTab.PLAYLISTS) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { actions.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                LibraryTab.entries.forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label, maxLines = 1) })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                when (tab) {
                    LibraryTab.PLAYLISTS -> playlistsTab(playlists, actions, onCreate = { creating = true }, onImport = { importing = true })
                    LibraryTab.DOWNLOADS -> tracksTab(
                        downloads, c, actions,
                        empty = { EmptyState(Icons.Filled.DownloadForOffline, "No downloads", "Downloaded songs play offline, with no data use.") },
                    )
                    LibraryTab.LIKED -> tracksTab(
                        liked, c, actions,
                        empty = { EmptyState(Icons.Filled.FavoriteBorder, "No liked songs", "Tap ♡ on the player to save songs here.") },
                    )
                    LibraryTab.HISTORY -> tracksTab(
                        history, c, actions,
                        empty = { EmptyState(Icons.Filled.History, "No history", "Songs you play will show up here.") },
                        headerExtra = {
                            IconButton(onClick = { vm.clearHistory() }) { Icon(Icons.Filled.DeleteSweep, "Clear history") }
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        TextInputDialog("New playlist", "Name", "Create", onDismiss = { creating = false }) { name ->
            creating = false
            c.appScope.launch(Dispatchers.Main) { c.createPlaylist(name) }
        }
    }
    if (importing) {
        TextInputDialog(
            "Import playlist or album",
            "YouTube, SoundCloud, Deezer or Apple Music link",
            "Import",
            onDismiss = { importing = false },
        ) { text ->
            importing = false
            val url = extractUrl(text) ?: text.trim()
            actions.showMessage("Importing…")
            c.appScope.launch(Dispatchers.Main) {
                runCatching { c.importPlaylist(url) }
                    .onSuccess { (_, n) -> actions.showMessage("Imported $n songs") }
                    .onFailure { actions.showMessage("Import failed: ${it.message}") }
            }
        }
    }
}

private fun LazyListScope.playlistsTab(
    playlists: List<PlaylistSummary>,
    actions: UiActions,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    item {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onCreate, label = { Text("New playlist") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
            AssistChip(onClick = onImport, label = { Text("Import link") }, leadingIcon = { Icon(Icons.Filled.Link, null) })
        }
    }
    if (playlists.isEmpty()) {
        item {
            EmptyState(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                "No playlists",
                "Mix songs from any source and your downloads, or import a playlist from YouTube Music, SoundCloud, Deezer or Apple Music.",
            )
        }
    }
    items(playlists, key = { it.playlist.id }) { p ->
        ListItem(
            headlineContent = { Text(p.playlist.name) },
            supportingContent = { Text("${p.songCount} songs") },
            leadingContent = { Artwork(p.thumbnailUrl, Modifier.size(56.dp)) },
            modifier = Modifier.clickable { actions.navigate(Routes.playlist(p.playlist.id)) },
        )
    }
}

private fun LazyListScope.tracksTab(
    tracks: List<Track>,
    c: AppContainer,
    actions: UiActions,
    empty: @Composable () -> Unit,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    if (tracks.isEmpty()) {
        item { empty() }
        return
    }
    item {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${tracks.size} songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            headerExtra?.invoke()
            FilledTonalButton(onClick = { c.playerConnection.play(tracks, shuffle = true) }) {
                Icon(Icons.Filled.Shuffle, "Shuffle")
            }
            FilledTonalButton(onClick = { c.playerConnection.play(tracks) }) {
                Icon(Icons.Filled.PlayArrow, "Play all")
            }
        }
    }
    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
        TrackRow(
            t,
            onClick = { c.playerConnection.play(tracks, i) },
            onMore = { actions.openTrackMenu(t, emptyList()) },
        )
    }
}
