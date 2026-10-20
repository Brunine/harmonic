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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.harmonic.music.AppContainer
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.remote.CatalogFilter
import app.harmonic.music.data.remote.SearchResult
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.components.CollectionRow
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TrackRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var source by mutableStateOf(MusicSource.YOUTUBE)
        private set
    var filter by mutableStateOf(c.catalog.filters(MusicSource.YOUTUBE).first())
        private set
    var submitted by mutableStateOf<String?>(null)
        private set

    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val results = MutableStateFlow<List<SearchResult>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private var next: Any? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun filters(): List<CatalogFilter> = c.catalog.filters(source)

    fun onQueryChange(q: String) {
        query = q
        suggestJob?.cancel()
        if (q.isBlank()) {
            suggestions.value = emptyList()
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
            runCatching { c.catalog.search(src, f, text) }
                .onSuccess {
                    results.value = it.items
                    next = it.next
                }
                .onFailure { error.value = it.message ?: "Search failed" }
            loading.value = false
        }
    }

    fun selectSource(s: MusicSource) {
        if (s == source) return
        source = s
        filter = c.catalog.filters(s).first()
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
        val src = source
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
            placeholder = { Text("Songs, albums, artists, playlists") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Filled.Close, "Clear") }
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
        SecondaryScrollableTabRow(selectedTabIndex = vm.source.ordinal, edgePadding = 16.dp) {
            MusicSource.entries.forEach { s ->
                Tab(selected = vm.source == s, onClick = { vm.selectSource(s) }, text = { Text(s.label) })
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.filters()) { f ->
                FilterChip(selected = vm.filter == f, onClick = { vm.selectFilter(f) }, label = { Text(f.label) })
            }
        }

        if (vm.source.playsViaYouTube) {
            Text(
                "${vm.source.label} songs play using the matching YouTube Music track.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        } else if (vm.source == MusicSource.SOUNDCLOUD) {
            Text(
                "SoundCloud tracks stream directly. Go+ tracks and downloads aren't supported.",
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
                            leadingContent = { Icon(Icons.Filled.History, null) },
                            modifier = Modifier.clickable {
                                focus.clearFocus()
                                vm.search(s)
                            },
                        )
                    }
                }

                error != null && results.isEmpty() -> EmptyState(
                    Icons.Filled.ErrorOutline,
                    "Couldn't search",
                    error ?: "",
                )

                vm.submitted == null -> EmptyState(
                    Icons.Filled.Search,
                    "Search ${vm.source.label}",
                    "Results play as audio only. Pick a source above; each result shows where it came from.",
                )

                !loading && results.isEmpty() -> EmptyState(
                    Icons.Filled.Search,
                    "No results",
                    "Try another filter or source.",
                )

                else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(results) { _, r ->
                        when (r) {
                            is SearchResult.Song -> TrackRow(
                                r.track,
                                onClick = { connection.play(listOf(r.track)) },
                                onMore = { actions.openTrackMenu(r.track, emptyList()) },
                                showSource = true,
                            )
                            is SearchResult.Collection -> CollectionRow(r.collection) {
                                actions.navigate(Routes.online(r.collection.url))
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
