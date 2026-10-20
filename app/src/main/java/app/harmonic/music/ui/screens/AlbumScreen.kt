package app.harmonic.music.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.remote.AlbumPage
import app.harmonic.music.data.remote.AlbumTrack
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.downloadMessage
import app.harmonic.music.ui.importError
import app.harmonic.music.ui.importPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * [id] is an album browse id ("MPREb_...") or an album playlist id ("OLAK5uy_...").
 * If YouTube Music's album page can't be read, the album is shown from its playlist instead
 * (same songs in album order, without the year).
 */
class AlbumViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val album = MutableStateFlow<AlbumPage?>(null)
    val error = MutableStateFlow<String?>(null)

    init {
        load()
    }

    fun load() {
        error.value = null
        viewModelScope.launch {
            runCatching { c.browser.album(id) }
                .recoverCatching { e -> if (id.startsWith("MPRE")) throw e else fromPlaylist() }
                .onSuccess { album.value = it }
                .onFailure { error.value = it.message.orEmpty() }
        }
    }

    private suspend fun fromPlaylist(): AlbumPage {
        val url = "https://music.youtube.com/playlist?list=$id"
        val p = c.catalog.collectionAll(url)
        return AlbumPage(
            browseId = id,
            title = p.title,
            artist = p.author,
            artistId = p.tracks.firstOrNull()?.artistId,
            year = null,
            subtitle = "",
            details = "",
            thumbnailUrl = p.thumbnailUrl,
            playlistUrl = url,
            tracks = p.tracks.mapIndexed { i, t -> AlbumTrack(i + 1, t) },
        )
    }

    fun play(shuffle: Boolean, start: Int = 0) {
        val tracks = album.value?.tracks?.map { it.track } ?: return
        c.playerConnection.play(tracks, start, shuffle)
    }

    fun download(onDone: (Int, Int) -> Unit) {
        val tracks = album.value?.tracks?.map { it.track } ?: return
        val skipped = c.downloads.download(tracks)
        onDone(tracks.size - skipped, skipped)
    }

    fun save(onDone: (Result<Int>) -> Unit) {
        val url = album.value?.playlistUrl ?: return
        c.appScope.launch(Dispatchers.Main) { onDone(runCatching { c.importPlaylist(url).second }) }
    }
}

@Composable
fun AlbumScreen(id: String, onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val res = LocalContext.current.resources
    val vm: AlbumViewModel = viewModel(key = "album-$id") { AlbumViewModel(c, id) }
    val album by vm.album.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        val a = album
        when {
            a == null && error != null -> Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    Icons.Filled.ErrorOutline,
                    stringResource(R.string.load_failed),
                    error?.ifEmpty { null } ?: stringResource(R.string.check_connection),
                )
                TextButton(onClick = vm::load, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.retry))
                }
            }
            a == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Artwork(a.thumbnailUrl, Modifier.size(140.dp), corner = 10.dp, placeholder = Icons.Filled.Album)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    a.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    a.artist,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = if (a.artistId != null) {
                                        Modifier.clickable { actions.navigate(Routes.artist(a.artistId)) }
                                    } else {
                                        Modifier
                                    },
                                )
                                // "Album • 2013" from YouTube, or just the year in the fallback.
                                val line = a.subtitle.ifEmpty { a.year?.toString().orEmpty() }
                                if (line.isNotEmpty()) {
                                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (a.details.isNotEmpty()) {
                                    Text(a.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { vm.play(shuffle = false) }, enabled = a.tracks.isNotEmpty()) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Text(stringResource(R.string.play))
                            }
                            FilledTonalButton(onClick = { vm.play(shuffle = true) }, enabled = a.tracks.isNotEmpty()) {
                                Icon(Icons.Filled.Shuffle, stringResource(R.string.shuffle))
                            }
                            if (a.playlistUrl != null) {
                                FilledTonalButton(onClick = {
                                    vm.save { result ->
                                        actions.showMessage(
                                            result.fold(
                                                { n -> res.getQuantityString(R.plurals.saved_songs, n, n) },
                                                { res.importError(it) },
                                            ),
                                        )
                                    }
                                }) {
                                    Icon(Icons.Filled.LibraryAdd, stringResource(R.string.save_to_library))
                                }
                            }
                            FilledTonalButton(
                                onClick = { vm.download { count, skipped -> actions.showMessage(res.downloadMessage(count, skipped)) } },
                                enabled = a.tracks.isNotEmpty(),
                            ) {
                                Icon(Icons.Filled.Download, stringResource(R.string.download_all))
                            }
                        }
                    }
                }
                itemsIndexed(a.tracks, key = { _, t -> t.track.id }) { i, entry ->
                    TrackRow(
                        entry.track,
                        onClick = { vm.play(shuffle = false, start = i) },
                        onMore = { actions.openTrackMenu(entry.track, emptyList()) },
                        leadingIndex = entry.number ?: (i + 1),
                        showSource = false,
                    )
                }
            }
        }
    }
}
