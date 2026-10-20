package app.harmonic.music.ui.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.harmonic.music.R
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.remote.LyricLine
import app.harmonic.music.data.remote.Lyrics
import app.harmonic.music.ui.LocalContainer

private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Loaded(val lyrics: Lyrics) : LyricsUi
    data object Failed : LyricsUi
}

/** Lyrics for the playing song; synced lyrics follow the music and a tap on a line seeks there. */
@Composable
fun LyricsPanel(track: Track, modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    var ui by remember(track.id) { mutableStateOf<LyricsUi>(LyricsUi.Loading) }
    var attempt by remember(track.id) { mutableIntStateOf(0) }

    LaunchedEffect(track.id, attempt) {
        ui = LyricsUi.Loading
        ui = runCatching { c.lyrics.lyrics(track) }
            .fold({ LyricsUi.Loaded(it) }, { LyricsUi.Failed })
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val state = ui) {
                LyricsUi.Loading -> CircularProgressIndicator()
                LyricsUi.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Filled.CloudOff, stringResource(R.string.lyrics_failed), stringResource(R.string.check_connection))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                }
                is LyricsUi.Loaded -> when (val lyrics = state.lyrics) {
                    is Lyrics.Synced -> SyncedLyrics(lyrics.lines)
                    is Lyrics.Plain -> PlainLyrics(lyrics.text)
                    Lyrics.Instrumental -> EmptyState(
                        Icons.Filled.MusicNote,
                        stringResource(R.string.lyrics_instrumental),
                        stringResource(R.string.lyrics_instrumental_body),
                    )
                    Lyrics.NotFound -> EmptyState(
                        Icons.Filled.Lyrics,
                        stringResource(R.string.lyrics_not_found),
                        stringResource(R.string.lyrics_not_found_body),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>) {
    val connection = LocalContainer.current.playerConnection
    val progress by remember(connection) { connection.progress(200) }.collectAsStateWithLifecycle(0L to 0L)
    val position = progress.first
    val current = remember(position, lines) { currentLine(lines, position) }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastTouch by remember { mutableLongStateOf(0L) }
    // Remember when the user last started or stopped scrolling by hand.
    LaunchedEffect(dragged) { if (dragged || lastTouch > 0) lastTouch = SystemClock.elapsedRealtime() }

    // Keep the current line about a third of the way down, unless the user is reading around.
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        if (dragged || SystemClock.elapsedRealtime() - lastTouch < USER_SCROLL_PAUSE_MS) return@LaunchedEffect
        val offset = -listState.layoutInfo.viewportSize.height / 3
        listState.animateScrollToItem(current, offset)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 120.dp),
    ) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == current -> MaterialTheme.colorScheme.primary
                    i < current -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                label = "lyricColor",
            )
            Text(
                line.text.ifBlank { "♪" },
                color = color,
                style = if (i == current) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { connection.seekTo(line.timeMs) }
                    .padding(vertical = 8.dp),
            )
        }
        item { Credit() }
    }
}

@Composable
private fun PlainLyrics(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            stringResource(R.string.lyrics_not_synced),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Credit()
    }
}

@Composable
private fun Credit() {
    Text(
        stringResource(R.string.lyrics_credit),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    )
}

/** Index of the last line that has started, or -1 before the first one. */
private fun currentLine(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

private const val USER_SCROLL_PAUSE_MS = 3_000L
