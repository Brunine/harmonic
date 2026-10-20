package app.harmonic.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.SectionHeader
import app.harmonic.music.ui.components.TrackRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val recent: StateFlow<List<Track>> = c.db.songDao().recent(20)
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mostPlayed: StateFlow<List<Track>> = c.db.songDao().mostPlayed(10)
        .map { list -> list.map { it.toTrack() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val quickPicks = MutableStateFlow<List<Track>>(emptyList())
    val loadingPicks = MutableStateFlow(false)
    /** On mobile data recommendations are loaded only on request. */
    val picksNeedTap = MutableStateFlow(false)

    init {
        if (c.isMetered()) picksNeedTap.value = true else loadQuickPicks()
    }

    fun loadQuickPicks() {
        if (loadingPicks.value) return
        picksNeedTap.value = false
        viewModelScope.launch {
            val seed = c.db.songDao().recentOnce(1).firstOrNull() ?: return@launch
            loadingPicks.value = true
            quickPicks.value = runCatching { c.catalog.related(seed.toTrack()).take(12) }.getOrDefault(emptyList())
            loadingPicks.value = false
        }
    }
}

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val vm: HomeViewModel = viewModel { HomeViewModel(c) }
    val recent by vm.recent.collectAsStateWithLifecycle()
    val mostPlayed by vm.mostPlayed.collectAsStateWithLifecycle()
    val picks by vm.quickPicks.collectAsStateWithLifecycle()
    val loadingPicks by vm.loadingPicks.collectAsStateWithLifecycle()
    val picksNeedTap by vm.picksNeedTap.collectAsStateWithLifecycle()
    val connection = c.playerConnection

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Harmonic", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { actions.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (recent.isEmpty() && picks.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Filled.LibraryMusic,
                        "Nothing here yet",
                        "Search for a song, album or artist to start listening. Your history and recommendations will show up here.",
                    )
                }
            }

            if (recent.isNotEmpty() && (picks.isNotEmpty() || loadingPicks || picksNeedTap)) {
                item {
                    SectionHeader("Quick picks") {
                        when {
                            picksNeedTap -> TextButton(onClick = vm::loadQuickPicks) { Text("Load") }
                            picks.isNotEmpty() -> IconButton(onClick = { connection.play(picks, shuffle = true) }) {
                                Icon(Icons.Filled.Shuffle, "Shuffle")
                            }
                        }
                    }
                }
                if (loadingPicks) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                itemsIndexed(picks, key = { _, t -> "pick-${t.id}" }) { i, t ->
                    TrackRow(t, onClick = { connection.play(picks, i) }, onMore = { actions.openTrackMenu(t, emptyList()) })
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                        items(recent, key = { it.id }) { t ->
                            Column(
                                Modifier
                                    .width(140.dp)
                                    .padding(horizontal = 4.dp)
                                    .clickable { connection.play(listOf(t)) },
                            ) {
                                Artwork(t.thumbnailUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    t.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (mostPlayed.isNotEmpty()) {
                item { SectionHeader("Your top songs") }
                itemsIndexed(mostPlayed, key = { _, t -> "top-${t.id}" }) { i, t ->
                    TrackRow(
                        t,
                        onClick = { connection.play(mostPlayed, i) },
                        onMore = { actions.openTrackMenu(t, emptyList()) },
                        leadingIndex = i + 1,
                    )
                }
            }
        }
    }
}
