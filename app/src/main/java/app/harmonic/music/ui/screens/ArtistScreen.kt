package app.harmonic.music.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.remote.ArtistPage
import app.harmonic.music.data.remote.BrowseItem
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.components.BrowseCard
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.SectionHeader
import app.harmonic.music.ui.components.TrackRow
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ArtistViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    val page = MutableStateFlow<ArtistPage?>(null)
    val error = MutableStateFlow<String?>(null)
    val starting = MutableStateFlow(false)

    init {
        load()
    }

    fun load() {
        error.value = null
        viewModelScope.launch {
            runCatching { c.browser.artist(id) }
                .onSuccess { page.value = it }
                .onFailure { error.value = it.message.orEmpty() }
        }
    }

    /**
     * Shuffle: the artist's full "Songs" playlist when YouTube offers one (first page, up to
     * about 100 songs), otherwise the top songs.
     */
    fun play(shuffle: Boolean) {
        val p = page.value ?: return
        if (starting.value) return
        viewModelScope.launch {
            starting.value = true
            val all = if (shuffle && p.songsPlaylistUrl != null) {
                runCatching { c.catalog.collection(p.songsPlaylistUrl).tracks }.getOrNull()
            } else {
                null
            }
            val tracks = all?.takeIf { it.isNotEmpty() } ?: p.topSongs
            if (tracks.isNotEmpty()) c.playerConnection.play(tracks, shuffle = shuffle)
            starting.value = false
        }
    }
}

@Composable
fun ArtistScreen(id: String, onBack: () -> Unit) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val vm: ArtistViewModel = viewModel(key = "artist-$id") { ArtistViewModel(c, id) }
    val page by vm.page.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        val p = page
        when {
            p == null && error != null -> Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp)) {
                EmptyState(
                    Icons.Filled.ErrorOutline,
                    stringResource(R.string.load_failed),
                    error?.ifEmpty { null } ?: stringResource(R.string.check_connection),
                )
                TextButton(onClick = vm::load, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.retry))
                }
            }
            p == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { ArtistHeader(p) }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { vm.play(shuffle = true) }, enabled = !starting) {
                            Icon(Icons.Filled.Shuffle, null)
                            Text(stringResource(R.string.shuffle))
                        }
                        FilledTonalButton(onClick = { vm.play(shuffle = false) }, enabled = !starting && p.topSongs.isNotEmpty()) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Text(stringResource(R.string.play))
                        }
                    }
                }
                if (p.topSongs.isNotEmpty()) {
                    item {
                        val seeAll = p.songsPlaylistUrl
                        SectionHeader(
                            stringResource(R.string.artist_top_songs),
                            action = if (seeAll != null) {
                                {
                                    TextButton(onClick = { actions.navigate(Routes.online(seeAll)) }) {
                                        Text(stringResource(R.string.see_all))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    itemsIndexed(p.topSongs, key = { _, t -> "top-" + t.id }) { i, t ->
                        TrackRow(
                            t,
                            onClick = { c.playerConnection.play(p.topSongs, i) },
                            onMore = { actions.openTrackMenu(t, emptyList()) },
                            showSource = false,
                        )
                    }
                }
                p.sections.forEachIndexed { index, section ->
                    item(key = "section-$index") {
                        Column {
                            SectionHeader(section.title)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(section.items) { item ->
                                    BrowseCard(item) {
                                        when (item) {
                                            is BrowseItem.Album -> actions.navigate(Routes.album(item.browseId))
                                            is BrowseItem.Playlist -> actions.navigate(Routes.online(item.url))
                                            is BrowseItem.Artist -> actions.navigate(Routes.artist(item.id))
                                            is BrowseItem.Song -> {
                                                val songs = section.items.filterIsInstance<BrowseItem.Song>().map { it.track }
                                                c.playerConnection.play(songs, songs.indexOf(item.track).coerceAtLeast(0))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                p.description?.let { text ->
                    item {
                        Column {
                            SectionHeader(stringResource(R.string.about_artist))
                            var expanded by remember { mutableStateOf(false) }
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .animateContentSize()
                                    .clickable { expanded = !expanded },
                            )
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(4.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
        }
    }
}

/** Full-width picture fading into the background, with the name over it. */
@Composable
private fun ArtistHeader(p: ArtistPage) {
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
        if (p.bannerUrl != null) {
            AsyncImage(
                model = p.bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to background)),
        )
        Text(
            p.name.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
