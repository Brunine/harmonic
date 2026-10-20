package app.harmonic.music.ui.components

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.canDownload
import app.harmonic.music.data.model.shareUrl
import app.harmonic.music.ui.ExtraAction
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalDownloadStates
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.addToPlaylist
import app.harmonic.music.ui.createPlaylist
import app.harmonic.music.ui.toggleLike
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun TrackMenuSheet(track: Track, extras: List<ExtraAction>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalUiActions.current
    val connection = container.playerConnection
    val context = LocalContext.current
    val song by remember(track.id) { container.db.songDao().observe(track.id) }.collectAsState(null)
    val download = LocalDownloadStates.current[track.id]
    val inJam = container.jam.isGuest

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.thumbnailUrl, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(track.artist, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            fun act(block: () -> Unit) {
                block()
                onDismiss()
            }

            MenuItem(if (inJam) "Play next in the Jam" else "Play next", Icons.Filled.SkipNext) {
                act { connection.playNext(listOf(track)) }
            }
            MenuItem(if (inJam) "Add to the Jam" else "Add to queue", Icons.AutoMirrored.Filled.QueueMusic) {
                act { connection.addToQueue(listOf(track)) }
            }
            if (!inJam) {
                MenuItem("Start radio", Icons.Filled.Radio) {
                    act {
                        container.appScope.launch(Dispatchers.Main) {
                            val related = runCatching { container.catalog.related(track) }.getOrDefault(emptyList())
                            connection.play(listOf(track) + related.filter { it.id != track.id })
                        }
                    }
                }
            }
            MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { act { actions.addToPlaylist(listOf(track)) } }
            val liked = song?.liked == true
            MenuItem(
                if (liked) "Remove from liked" else "Like",
                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            ) { act { container.appScope.launch(Dispatchers.Main) { container.toggleLike(track) } } }
            when {
                download?.isDone == true ->
                    MenuItem("Remove download", Icons.Filled.Delete) { act { container.downloads.remove(track.id) } }
                download?.isActive == true ->
                    MenuItem("Cancel download (${download.percent.toInt()}%)", Icons.Filled.Delete) {
                        act { container.downloads.remove(track.id) }
                    }
                track.canDownload ->
                    MenuItem("Download", Icons.Filled.Download) {
                        act {
                            container.downloads.download(listOf(track))
                            actions.showMessage("Downloading “${track.title}”")
                        }
                    }
                else -> Unit
            }
            MenuItem("Share", Icons.Filled.Share) {
                act {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, track.shareUrl())
                    context.startActivity(Intent.createChooser(send, null))
                }
            }
            extras.forEach { extra -> MenuItem(extra.label, extra.icon) { act(extra.onClick) } }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalUiActions.current
    val playlists by remember { container.db.playlistDao().summaries() }.collectAsState(emptyList())
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        TextInputDialog(
            title = "New playlist",
            label = "Name",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                container.appScope.launch(Dispatchers.Main) {
                    container.createPlaylist(name, tracks)
                    actions.showMessage("Added to $name")
                }
                onDismiss()
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tracks.size == 1) "Add to playlist" else "Add ${tracks.size} songs to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("New playlist") },
                        leadingContent = { Icon(Icons.Filled.Add, null) },
                        modifier = Modifier.fillMaxWidth().clickable { creating = true },
                    )
                }
                items(playlists, key = { it.playlist.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.playlist.name) },
                        supportingContent = { Text("${p.songCount} songs") },
                        leadingContent = { Artwork(p.thumbnailUrl, Modifier.size(40.dp)) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            container.appScope.launch(Dispatchers.Main) {
                                container.addToPlaylist(p.playlist.id, tracks)
                                actions.showMessage("Added to ${p.playlist.name}")
                            }
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
