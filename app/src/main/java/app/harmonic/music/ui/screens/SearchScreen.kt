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
import androidx.compose.material3.OutlinedTextField
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
import app.harmonic.music.data.remote.SearchFilter
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
import org.schabi.newpipe.extractor.Page

class SearchViewModel(private val c: AppContainer) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var filter by mutableStateOf(SearchFilter.SONGS)
        private set
    var submitted by mutableStateOf<String?>(null)
        private set

    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val results = MutableStateFlow<List<SearchResult>>(emptyList())
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private var nextPage: Page? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

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
        nextPage = null
        searchJob = viewModelScope.launch {
            loading.value = true
            runCatching { c.youtube.search(text, filter) }
                .onSuccess {
                    results.value = it.items
                    nextPage = it.nextPage
                }
                .onFailure { error.value = it.message ?: "Search failed" }
            loading.value = false
        }
    }

    fun selectFilter(f: SearchFilter) {
        if (f == filter) return
        filter = f
        submitted?.let { search(it) }
    }

    fun loadMore() {
        val page = nextPage
        val text = submitted
        if (loading.value || text == null || !Page.isValid(page)) return
        searchJob = viewModelScope.launch {
            loading.value = true
            runCatching { c.youtube.searchMore(text, filter, page!!) }
                .onSuccess { more ->
                    results.value = results.value + more.items
                    nextPage = more.nextPage
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

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SearchFilter.entries) { f ->
                FilterChip(selected = vm.filter == f, onClick = { vm.selectFilter(f) }, label = { Text(f.label) })
            }
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
                    "Search YouTube Music",
                    "Results play as audio only. Tap a song to start a radio of similar music.",
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
