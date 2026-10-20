package app.harmonic.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.remote.CatalogFilter
import app.harmonic.music.data.remote.SearchResult
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.components.ArtistRow
import app.harmonic.music.ui.components.CollectionRow
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TrackRow
import app.harmonic.music.ui.components.color
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val ALL_FILTER = CatalogFilter(R.string.filter_songs, "all")

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    /** null = all sources at once. */
    var source by mutableStateOf<MusicSource?>(null)
        private set
    var filter by mutableStateOf(ALL_FILTER)
        private set
    var submitted by mutableStateOf<String?>(null)
        private set

    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val results = MutableStateFlow<List<SearchResult>>(emptyList())
    val loading = MutableStateFlow(false)
    /** Error text from the service, or "" when the reason is unknown. */
    val error = MutableStateFlow<String?>(null)

    private var next: Any? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun filters(): List<CatalogFilter> = source?.let { c.catalog.filters(it) } ?: listOf(ALL_FILTER)

    fun onQueryChange(q: String) {
        query = q
        suggestJob?.cancel()
        if (q.isBlank()) {
            suggestions.value = emptyList()
            submitted = null
            results.value = emptyList()
            error.value = null
            return
        }
        suggestJob = viewModelScope.launch {
            delay(300) // debounce: one small request per pause in typing
            suggestions.value = c.youtube.suggestions(q)
        }
    }

    fun search(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        submitted = text
        c.searchHistory.add(text)
        suggestJob?.cancel()
        suggestions.value = emptyList()
        searchJob?.cancel()
        results.value = emptyList()
        error.value = null
        next = null
        val src = source
        val f = filter
        searchJob = viewModelScope.launch {
            loading.value = true
            runCatching { if (src == null) c.catalog.searchAll(text) else c.catalog.search(src, f, text) }
                .onSuccess {
                    results.value = it.items
                    next = it.next
                }
                .onFailure { error.value = it.message.orEmpty() }
            loading.value = false
        }
    }

    fun selectSource(s: MusicSource?) {
        if (s == source) return
        source = s
        filter = s?.let { c.catalog.filters(it).first() } ?: ALL_FILTER
        submitted?.let { search(it) }
    }

    fun selectFilter(f: CatalogFilter) {
        if (f == filter) return
        filter = f
        submitted?.let { search(it) }
    }

    fun loadMore() {
        val token = next ?: return
        val text = submitted ?: return
        if (loading.value) return
        val src = source ?: return
        val f = filter
        searchJob = viewModelScope.launch {
            loading.value = true
            runCatching { c.catalog.search(src, f, text, token) }
                .onSuccess { more ->
                    results.value = results.value + more.items
                    next = more.next
                }
            loading.value = false
        }
    }
}

@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val vm: SearchViewModel = viewModel { SearchViewModel(c) }
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val history by c.searchHistory.items.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val connection = c.playerConnection

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, results.size) { if (nearEnd && results.isNotEmpty()) vm.loadMore() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.clear))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                vm.search()
            }),
        )

        // Where results come from
        val tabs: List<MusicSource?> = listOf(null) + MusicSource.entries
        SecondaryScrollableTabRow(selectedTabIndex = tabs.indexOf(vm.source), edgePadding = 16.dp) {
            tabs.forEach { s ->
                Tab(
                    selected = vm.source == s,
                    onClick = { vm.selectSource(s) },
                    text = {
                        Text(
                            s?.label ?: stringResource(R.string.all_sources),
                            color = s?.color() ?: Color.Unspecified,
                        )
                    },
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.filters()) { f ->
                FilterChip(
                    selected = vm.filter == f,
                    onClick = { vm.selectFilter(f) },
                    label = { Text(stringResource(f.label)) },
                )
            }
        }

        val src = vm.source
        val hint = when {
            src == null -> stringResource(R.string.search_hint_all)
            src.playsViaYouTube -> stringResource(R.string.search_hint_matched, src.label)
            src == MusicSource.SOUNDCLOUD -> stringResource(R.string.search_hint_soundcloud)
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        val showSuggestions = suggestions.isNotEmpty() && vm.query != vm.submitted
        Box(Modifier.fillMaxSize()) {
            when {
                showSuggestions -> LazyColumn {
                    items(suggestions) { s ->
                        ListItem(
                            headlineContent = { Text(s) },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.TrendingUp, null) },
                            modifier = Modifier.clickable {
                                focus.clearFocus()
                                vm.search(s)
                            },
                        )
                    }
                }

                error != null && results.isEmpty() -> EmptyState(
                    Icons.Filled.ErrorOutline,
                    stringResource(R.string.search_failed),
                    error?.ifEmpty { null } ?: stringResource(R.string.check_connection),
                )

                vm.submitted == null && vm.query.isBlank() && history.isNotEmpty() -> LazyColumn {
                    item {
                        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                            Text(
                                stringResource(R.string.recent_searches),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.align(Alignment.CenterStart),
                            )
                            TextButton(onClick = { c.searchHistory.clear() }, modifier = Modifier.align(Alignment.CenterEnd)) {
                                Text(stringResource(R.string.clear_all))
                            }
                        }
                    }
                    items(history, key = { it }) { h ->
                        ListItem(
                            headlineContent = { Text(h) },
                            leadingContent = { Icon(Icons.Filled.History, null) },
                            trailingContent = {
                                IconButton(onClick = { c.searchHistory.remove(h) }) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.remove))
                                }
                            },
                            modifier = Modifier.clickable {
                                focus.clearFocus()
                                vm.search(h)
                            },
                        )
                    }
                }

                vm.submitted == null -> EmptyState(
                    Icons.Filled.Search,
                    src?.let { stringResource(R.string.search_title_source, it.label) }
                        ?: stringResource(R.string.search_title_all),
                    stringResource(R.string.search_empty_body),
                )

                !loading && results.isEmpty() -> EmptyState(
                    Icons.Filled.Search,
                    stringResource(R.string.no_results),
                    stringResource(R.string.no_results_body),
                )

                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(results) { _, r ->
                        when (r) {
                            is SearchResult.Song -> TrackRow(
                                r.track,
                                onClick = { connection.play(listOf(r.track)) },
                                onMore = { actions.openTrackMenu(r.track, emptyList()) },
                            )
                            is SearchResult.Collection -> CollectionRow(r.collection) {
                                // YouTube Music albums get the album page (track numbers, year).
                                val albumId = r.collection.url.substringAfter("list=", "").substringBefore('&')
                                if (r.collection.isAlbum && r.collection.source == MusicSource.YOUTUBE && albumId.isNotEmpty()) {
                                    actions.navigate(Routes.album(albumId))
                                } else {
                                    actions.navigate(Routes.online(r.collection.url))
                                }
                            }
                            is SearchResult.Artist -> ArtistRow(r.artist) {
                                actions.navigate(Routes.artist(r.artist.id))
                            }
                        }
                    }
                    if (loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
            if (loading && results.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}
