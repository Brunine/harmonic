package app.harmonic.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.remote.LibraryPlaylist
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.likeAll
import app.harmonic.music.ui.saveOrReplacePlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where an import is: playlist [index] of [total], [songs] songs read from it so far. */
data class ImportProgress(val index: Int, val total: Int, val title: String, val songs: Int)

data class ImportResult(val playlists: Int, val songs: Int, val liked: Int, val failed: Int)

class AccountLibraryViewModel(private val c: AppContainer) : ViewModel() {
    val playlists = MutableStateFlow<List<LibraryPlaylist>?>(null)
    val error = MutableStateFlow<String?>(null)
    val selected = MutableStateFlow<Set<String>>(emptySet())
    val progress = MutableStateFlow<ImportProgress?>(null)

    init {
        load()
    }

    fun load() {
        error.value = null
        viewModelScope.launch {
            runCatching { c.browser.libraryPlaylists() }
                .onSuccess { list ->
                    playlists.value = list
                    selected.value = list.map { it.id }.toSet()
                }
                .onFailure { error.value = it.message.orEmpty() }
        }
    }

    fun toggle(id: String) {
        selected.value = selected.value.let { if (id in it) it - id else it + id }
    }

    /**
     * Liked music becomes likes in Harmonic; every other playlist becomes (or refreshes) a local
     * playlist with the same name. Runs in the app scope so leaving the screen doesn't stop it.
     */
    fun import(likedTitle: String, onDone: (ImportResult) -> Unit) {
        val chosen = playlists.value.orEmpty().filter { it.id in selected.value }
        if (chosen.isEmpty() || progress.value != null) return
        c.appScope.launch(Dispatchers.Main) {
            var playlistsDone = 0
            var songs = 0
            var liked = 0
            var failed = 0
            chosen.forEachIndexed { i, p ->
                val title = p.title.ifEmpty { likedTitle }
                progress.value = ImportProgress(i + 1, chosen.size, title, 0)
                runCatching {
                    val tracks = c.browser.libraryPlaylistTracks(p.id) { n ->
                        progress.value = ImportProgress(i + 1, chosen.size, title, n)
                    }
                    if (p.isLikedMusic) {
                        liked += c.likeAll(tracks)
                    } else {
                        c.saveOrReplacePlaylist(title, tracks, "https://music.youtube.com/playlist?list=${p.id}")
                        playlistsDone++
                    }
                    songs += tracks.size
                }.onFailure { failed++ }
            }
            progress.value = null
            onDone(ImportResult(playlistsDone, songs, liked, failed))
        }
    }
}

@Composable
fun AccountLibraryScreen(onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val res = LocalContext.current.resources
    val vm: AccountLibraryViewModel = viewModel { AccountLibraryViewModel(c) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val likedTitle = stringResource(R.string.liked_music)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.yt_library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
        bottomBar = {
            val list = playlists
            if (list != null && list.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    val p = progress
                    if (p != null) {
                        Text(
                            stringResource(R.string.yt_library_importing, p.index, p.total, p.title, p.songs),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        Button(
                            onClick = {
                                vm.import(likedTitle) { r ->
                                    val parts = buildList {
                                        if (r.playlists > 0) add(res.getQuantityString(R.plurals.yt_imported_playlists, r.playlists, r.playlists))
                                        if (r.liked > 0) add(res.getQuantityString(R.plurals.yt_imported_likes, r.liked, r.liked))
                                        if (r.failed > 0) add(res.getQuantityString(R.plurals.yt_import_failed, r.failed, r.failed))
                                    }
                                    actions.showMessage(parts.joinToString(" • ").ifEmpty { res.getString(R.string.yt_import_nothing) })
                                }
                            },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(pluralStringResource(R.plurals.yt_import_button, selected.size, selected.size))
                        }
                    }
                }
            }
        },
    ) { padding ->
        val list = playlists
        when {
            list == null && error != null -> Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    Icons.Filled.ErrorOutline,
                    stringResource(R.string.load_failed),
                    error?.ifEmpty { null } ?: stringResource(R.string.check_connection),
                )
                TextButton(onClick = vm::load, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.retry))
                }
            }
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Text(
                        stringResource(R.string.yt_library_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(list, key = { it.id }) { p ->
                    val enabled = progress == null
                    ListItem(
                        headlineContent = { Text(if (p.isLikedMusic) likedTitle else p.title) },
                        supportingContent = {
                            Text(if (p.isLikedMusic) stringResource(R.string.liked_music_desc) else p.subtitle)
                        },
                        leadingContent = {
                            Artwork(
                                p.thumbnailUrl,
                                Modifier.size(48.dp),
                                placeholder = if (p.isLikedMusic) Icons.Filled.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                            )
                        },
                        trailingContent = {
                            Checkbox(checked = p.id in selected, onCheckedChange = { vm.toggle(p.id) }, enabled = enabled)
                        },
                        modifier = Modifier.clickable(enabled = enabled) { vm.toggle(p.id) },
                    )
                }
            }
        }
    }
}
