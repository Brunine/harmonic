package app.harmonic.music.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.harmonic.music.jam.JamManager
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions
import app.harmonic.music.ui.Routes
import app.harmonic.music.ui.components.Artwork
import app.harmonic.music.ui.components.EmptyState
import app.harmonic.music.ui.components.TrackRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun JamScreen() {
    val c = LocalContainer.current
    val jam = c.jam
    val state by jam.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jam", fontWeight = FontWeight.Bold) },
                actions = {
                    if (state.active) {
                        TextButton(onClick = jam::leave) {
                            Text(if (state.role == JamManager.Role.HOST) "End Jam" else "Leave")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.active -> JamStart(state.error)
                state.role == JamManager.Role.GUEST && !state.connected -> JamDiscovery(state)
                else -> JamSession(state)
            }
        }
    }
}

/** Before a Jam: pick a name, then host or join. Handles permissions and Bluetooth first. */
@Composable
private fun JamStart(error: String?) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(settings.jamName) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askBluetooth by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (JamManager.isBluetoothOn(context)) pending?.invoke()
        pending = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            if (JamManager.isBluetoothOn(context)) {
                pending?.invoke()
                pending = null
            } else {
                askBluetooth = true
            }
        } else {
            permissionDenied = true
            pending = null
        }
    }

    fun begin(action: () -> Unit) {
        c.appScope.launch(Dispatchers.Main) { c.settings.setJamName(name) }
        pending = action
        val missing = JamManager.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        when {
            missing.isNotEmpty() -> permissionLauncher.launch(missing.toTypedArray())
            !JamManager.isBluetoothOn(context) -> askBluetooth = true
            else -> {
                action()
                pending = null
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(
            Icons.Filled.Groups,
            "Listen together",
            "Friends nearby add songs to one shared queue. The host's phone plays it, " +
                "for example through the car. Everyone needs Harmonic and Bluetooth on.",
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text("Your name in the Jam") },
            placeholder = { Text(android.os.Build.MODEL ?: "Phone") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { begin { c.jam.startHosting() } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Groups, null)
            Spacer(Modifier.width(8.dp))
            Text("Start a Jam")
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { begin { c.jam.startSearching() } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PersonAdd, null)
            Spacer(Modifier.width(8.dp))
            Text("Join a Jam")
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (permissionDenied) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Jam needs the “Nearby devices” permission to find friends' phones. " +
                    "You can allow it in Settings → Apps → Harmonic → Permissions.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (askBluetooth) {
        AlertDialog(
            onDismissRequest = {
                askBluetooth = false
                pending = null
            },
            icon = { Icon(Icons.Filled.Bluetooth, null) },
            title = { Text("Turn on Bluetooth") },
            text = { Text("Jam finds and talks to friends' phones over Bluetooth. Music still streams over your data.") },
            confirmButton = {
                TextButton(onClick = {
                    askBluetooth = false
                    runCatching { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { pending = null }
                }) { Text("Turn on") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askBluetooth = false
                    pending = null
                }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun JamDiscovery(state: JamManager.State) {
    val jam = LocalContainer.current.jam
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Looking for Jams nearby…", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask the host to open Jam and tap “Start a Jam”. Keep the phones close together.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        itemsIndexed(state.hosts, key = { _, h -> h.endpointId }) { _, host ->
            ListItem(
                headlineContent = { Text("${host.name}'s Jam") },
                leadingContent = { Icon(Icons.Filled.Groups, null) },
                trailingContent = {
                    if (state.connectingTo == host.endpointId) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Join", color = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.clickable(enabled = state.connectingTo == null) { jam.join(host) },
            )
        }
        state.error?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun JamSession(state: JamManager.State) {
    val c = LocalContainer.current
    val actions = LocalUiActions.current
    val jam = c.jam
    val isHost = state.role == JamManager.Role.HOST
    val canControl = isHost || state.guestsCanControl

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (isHost) "You're hosting" else "In ${state.hostName ?: "the host"}'s Jam",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (isHost) {
                            if (state.members.isEmpty()) "Waiting for friends to join…" else "${state.members.size} joined"
                        } else if (state.playEverywhere) {
                            "Playing on every phone"
                        } else {
                            "Playing on ${state.hostName ?: "the host"}'s phone"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isHost && state.members.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.members.forEach { m ->
                                InputChip(
                                    selected = false,
                                    onClick = { jam.kick(m) },
                                    label = { Text(m.name) },
                                    trailingIcon = { Icon(Icons.Filled.Close, "Remove ${m.name}", Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    if (isHost) {
                        Spacer(Modifier.height(8.dp))
                        SwitchLine(
                            "Play on every phone",
                            "Guests' phones play along (roughly in sync). Uses their data.",
                            state.playEverywhere,
                            jam::setPlayEverywhere,
                        )
                        SwitchLine(
                            "Guests can control playback",
                            "Play, pause, skip and pick songs from their phones",
                            state.guestsCanControl,
                            jam::setGuestsCanControl,
                        )
                    }
                }
            }
        }

        // Now playing
        item {
            val current = state.current
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(current?.thumbnailUrl, Modifier.size(64.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current?.artist.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { jam.control("prev") }, enabled = canControl) {
                    Icon(Icons.Filled.SkipPrevious, "Previous")
                }
                IconButton(onClick = { jam.control("toggle") }, enabled = canControl) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause")
                }
                IconButton(onClick = { jam.control("next") }, enabled = canControl) {
                    Icon(Icons.Filled.SkipNext, "Next")
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Queue • ${state.queueSize} songs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { actions.navigate(Routes.SEARCH) }) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add songs")
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        if (state.queue.isEmpty()) {
            item {
                EmptyState(
                    Icons.Filled.Search,
                    "The queue is empty",
                    if (isHost) "Play something, or let your friends add songs." else "Search for a song and tap it to add it.",
                )
            }
        }

        itemsIndexed(state.queue, key = { i, t -> "${state.queueOffset + i}-${t.id}" }) { i, track ->
            val index = state.queueOffset + i
            val isCurrent = index == state.currentIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackRow(
                    track,
                    onClick = { if (canControl) jam.playAt(index) },
                    onMore = null,
                    modifier = Modifier.weight(1f),
                    isCurrent = isCurrent,
                )
                if (!isCurrent) {
                    IconButton(onClick = { jam.move(index, index - 1) }, enabled = index > state.currentIndex + 1) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                    }
                    IconButton(onClick = { jam.move(index, index + 1) }, enabled = index < state.queueSize - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                    }
                    IconButton(onClick = { jam.remove(index) }) {
                        Icon(Icons.Filled.Close, "Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
