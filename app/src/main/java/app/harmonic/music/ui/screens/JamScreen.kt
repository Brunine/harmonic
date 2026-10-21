package app.harmonic.music.ui.screens

import app.harmonic.music.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.harmonic.music.jam.JamManager
import app.harmonic.music.jam.JamQr
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
                title = { Text(stringResource(R.string.tab_jam)) },
                actions = {
                    if (state.active) {
                        TextButton(onClick = jam::leave) {
                            Text(stringResource(if (state.role == JamManager.Role.HOST) R.string.jam_end else R.string.jam_leave))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.active -> JamStart(state)
                state.role == JamManager.Role.GUEST && !state.connected -> JamDiscovery(state)
                else -> JamSession(state)
            }
        }
    }
}

/** Before a Jam: pick a name, then host or join. Handles permissions and Bluetooth first. */
@Composable
private fun JamStart(state: JamManager.State) {
    val error = state.error
    val c = LocalContainer.current
    val actions = LocalUiActions.current
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
            stringResource(R.string.jam_intro_title),
            stringResource(R.string.jam_intro_body),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text(stringResource(R.string.jam_your_name)) },
            placeholder = { Text(android.os.Build.MODEL ?: stringResource(R.string.phone)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { begin { c.jam.startHosting() } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Groups, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.jam_start))
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { begin { c.jam.startSearching() } }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PersonAdd, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.jam_join))
        }
        Spacer(Modifier.height(8.dp))
        val invalidCode = stringResource(R.string.jam_qr_invalid)
        OutlinedButton(
            onClick = {
                begin {
                    scanJamCode(context, onCode = { c.jam.joinByCode(it) }, onInvalid = { actions.showMessage(invalidCode) })
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.QrCodeScanner, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.jam_scan))
        }
        // A QR code scanned with the phone's camera, or a harmonic://jam link, opened the app.
        state.invite?.let { code ->
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.jam_invite_title, code), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.jam_invite_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { c.jam.setInvite(null) }) { Text(stringResource(R.string.cancel)) }
                        TextButton(onClick = { begin { c.jam.joinByCode(code) } }) { Text(stringResource(R.string.join)) }
                    }
                }
            }
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (permissionDenied) {
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.jam_permission_denied),
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
            title = { Text(stringResource(R.string.bt_title)) },
            text = { Text(stringResource(R.string.bt_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askBluetooth = false
                    runCatching { bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { pending = null }
                }) { Text(stringResource(R.string.turn_on)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askBluetooth = false
                    pending = null
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun JamDiscovery(state: JamManager.State) {
    val jam = LocalContainer.current.jam
    val actions = LocalUiActions.current
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.jam_looking), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.joinCode != null) {
                    stringResource(R.string.jam_looking_code, state.joinCode)
                } else {
                    stringResource(R.string.jam_looking_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val invalidCode = stringResource(R.string.jam_qr_invalid)
            OutlinedButton(onClick = {
                scanJamCode(context, onCode = { jam.joinByCode(it) }, onInvalid = { actions.showMessage(invalidCode) })
            }) {
                Icon(Icons.Filled.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.jam_scan))
            }
            Spacer(Modifier.height(16.dp))
        }
        itemsIndexed(state.hosts, key = { _, h -> h.endpointId }) { _, host ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.jam_of, host.name)) },
                leadingContent = { Icon(Icons.Filled.Groups, null) },
                trailingContent = {
                    if (state.connectingTo == host.endpointId) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.join), color = MaterialTheme.colorScheme.primary)
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
                        if (isHost) stringResource(R.string.jam_hosting) else stringResource(R.string.jam_in, state.hostName ?: stringResource(R.string.the_host)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (isHost) {
                            if (state.members.isEmpty()) stringResource(R.string.jam_waiting) else pluralStringResource(R.plurals.jam_joined_count, state.members.size, state.members.size)
                        } else if (state.playEverywhere) {
                            stringResource(R.string.jam_playing_everywhere)
                        } else {
                            stringResource(R.string.jam_playing_on, state.hostName ?: stringResource(R.string.the_host))
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
                                    trailingIcon = { Icon(Icons.Filled.Close, stringResource(R.string.remove_person, m.name), Modifier.size(16.dp)) },
                                )
                            }
                        }
                    }
                    if (isHost && state.code != null) {
                        Spacer(Modifier.height(12.dp))
                        JamCode(state.code)
                    }
                    if (isHost) {
                        Spacer(Modifier.height(8.dp))
                        SwitchLine(
                            stringResource(R.string.jam_play_everywhere),
                            stringResource(R.string.jam_play_everywhere_desc),
                            state.playEverywhere,
                            jam::setPlayEverywhere,
                        )
                        SwitchLine(
                            stringResource(R.string.jam_guest_control),
                            stringResource(R.string.jam_guest_control_desc),
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
                        current?.title ?: stringResource(R.string.nothing_playing),
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
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous))
                }
                IconButton(onClick = { jam.control("toggle") }, enabled = canControl) {
                    Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, stringResource(R.string.play_pause))
                }
                IconButton(onClick = { jam.control("next") }, enabled = canControl) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.next))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.jam_queue, state.queueSize, state.queueSize),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { actions.navigate(Routes.SEARCH) }) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_songs))
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }

        if (state.queue.isEmpty()) {
            item {
                EmptyState(
                    Icons.Filled.Search,
                    stringResource(R.string.queue_empty),
                    stringResource(if (isHost) R.string.queue_empty_host else R.string.queue_empty_guest),
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
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { jam.move(index, index + 1) }, enabled = index < state.queueSize - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down))
                    }
                    IconButton(onClick = { jam.remove(index) }) {
                        Icon(Icons.Filled.Close, stringResource(R.string.remove))
                    }
                }
            }
        }
    }
}

/** The host's QR code and code: friends scan it in Jam → Scan code. */
@Composable
private fun JamCode(code: String) {
    val qr = remember(code) { JamQr.bitmap(code, 480).asImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            qr,
            contentDescription = stringResource(R.string.jam_qr_description, code),
            modifier = Modifier.size(132.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.jam_code_label), style = MaterialTheme.typography.labelMedium)
            Text(code, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.jam_code_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Google's code scanner: no camera permission needed, the scan screen is Google Play's. */
private fun scanJamCode(context: Context, onCode: (String) -> Unit, onInvalid: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode ->
            val code = JamQr.parse(barcode.rawValue)
            if (code != null) onCode(code) else onInvalid()
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
