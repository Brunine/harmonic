package app.harmonic.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.remote.OnlinePlaylist
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.importPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

class OnlinePlaylistViewModel(private val c: AppContainer, private val url: String) : ViewModel() {
    val playlist = MutableStateFlow<OnlinePlaylist?>(null)
    val error = MutableStateFlow<String?>(null)
    val loadingMore = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            runCatching { c.youtube.playlist(url) }
                .onSuccess { playlist.value = it }
                .onFailure { error.value = it.message ?: "Couldn't load playlist" }
        }
    }

    fun loadMore() {
        val p = playlist.value ?: return
        if (loadingMore.value || !Page.isValid(p.nextPage)) return
        viewModelScope.launch {
            loadingMore.value = true
            runCatching { c.youtube.playlistMore(url, p.nextPage!!) }.onSuccess { (tracks, next) ->
                playlist.value = p.copy(tracks = (p.tracks + tracks).distinctBy { it.id }, nextPage = next)
            }
            loadingMore.value = false
        }
    }

    /** Loads every page first so "play", "save" and "download" act on the whole list. */
    private suspend fun allTracks(): List<Track> {
        val p = playlist.value ?: return emptyList()
        if (!Page.isValid(p.nextPage)) return p.tracks
        val full = c.youtube.playlistAll(url)
        playlist.value = full
        return full.tracks
    }

    fun playAll(shuffle: Boolean) = viewModelScope.launch {
        runCatching { allTracks() }.onSuccess { c.playerConnection.play(it, shuffle = shuffle) }
    }

    fun downloadAll(onDone: (Int) -> Unit) = viewModelScope.launch {
        runCatching { allTracks() }.onSuccess {
            c.downloads.download(it)
            onDone(it.size)
        }
    }

    fun save(onDone: (String) -> Unit) = c.appScope.launch(Dispatchers.Main) {
        runCatching { c.importPlaylist(url) }
            .onSuccess { (_, count) -> onDone("Saved $count songs to your library") }
            .onFailure { onDone("Couldn't save: ${it.message}") }
    }
}

@Composable
fun OnlinePlaylistScreen(url: String, onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val vm: OnlinePlaylistViewModel = viewModel { OnlinePlaylistViewModel(c, url) }
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, playlist?.tracks?.size) { if (nearEnd) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        val p = playlist
        when {
            p == null && error != null -> EmptyState(
                Icons.Filled.ErrorOutline, "Couldn't load", error.orEmpty(), Modifier.padding(padding),
            )
            p == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(p.thumbnailUrl, Modifier.size(120.dp), corner = 10.dp)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3)
                                Text(p.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { vm.playAll(false) }) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Text("Play")
                            }
                            FilledTonalButton(onClick = { vm.playAll(true) }) {
                                Icon(Icons.Filled.Shuffle, null)
                            }
                            FilledTonalButton(onClick = { vm.save(actions.showMessage) }) {
                                Icon(Icons.Filled.LibraryAdd, "Save to library")
                            }
                            FilledTonalButton(onClick = {
                                vm.downloadAll { n -> actions.showMessage("Downloading $n songs") }
                            }) {
                                Icon(Icons.Filled.Download, "Download all")
                            }
                        }
                    }
                }
                itemsIndexed(p.tracks, key = { _, t -> t.id }) { i, t ->
                    TrackRow(
                        t,
                        onClick = { c.playerConnection.play(p.tracks, i) },
                        onMore = { actions.openTrackMenu(t, emptyList()) },
                    )
                }
                if (loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}
