package app.harmonic.music.jam

import app.harmonic.music.R
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.harmonic.music.AppContainer
import app.harmonic.music.data.model.Track
import app.harmonic.music.jam.JamProtocol.message
import app.harmonic.music.playback.PlaybackService
import app.harmonic.music.playback.toMediaItem
import app.harmonic.music.playback.toTrack
import app.harmonic.music.playback.trackId
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Jam: friends nearby share one queue.
 *
 * One phone hosts (usually the one connected to the car) and its normal player queue *is* the
 * Jam queue. Guests find it with Nearby Connections (Bluetooth, upgrading to Wi-Fi only when
 * that would not disturb an existing Wi-Fi link such as wireless Android Auto), then add,
 * reorder and remove songs. Only small JSON messages travel between phones.
 *
 * Optionally every phone plays along ("play everywhere"): guests mirror the host's queue and
 * position, corrected for clock offset. This is approximate (a few hundred ms), so it suits a
 * room more than a car.
 */
class JamManager(private val context: Context, private val app: AppContainer) {

    enum class Role { NONE, HOST, GUEST }

    data class Member(val endpointId: String, val name: String)
    /** [code] is the host's Jam code, when it advertises one (Harmonic 1.7.0 and later). */
    data class FoundHost(val endpointId: String, val name: String, val code: String? = null)

    data class State(
        val role: Role = Role.NONE,
        /** Guest: looking for hosts (not connected yet). */
        val searching: Boolean = false,
        val connectingTo: String? = null,
        val hosts: List<FoundHost> = emptyList(),
        /** Host: connected guests. */
        val members: List<Member> = emptyList(),
        val hostName: String? = null,
        val connected: Boolean = false,
        /** Window of the shared queue; [queueOffset] is the absolute index of its first item. */
        val queue: List<Track> = emptyList(),
        val queueOffset: Int = 0,
        val queueSize: Int = 0,
        val currentIndex: Int = -1,
        val isPlaying: Boolean = false,
        val playEverywhere: Boolean = false,
        val guestsCanControl: Boolean = true,
        val error: String? = null,
        /** Host: this Jam's code, shown as a QR code for guests to scan. */
        val code: String? = null,
        /** Guest: join the host with this code as soon as it is found. */
        val joinCode: String? = null,
        /** Not in a Jam yet: a code from a scanned QR / opened link, waiting for the user's OK. */
        val invite: String? = null,
    ) {
        val current: Track? get() = queue.getOrNull(currentIndex - queueOffset)
        val active: Boolean get() = role != Role.NONE
    }

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Connected to a host as a guest. */
    val isGuest: Boolean get() = _state.value.role == Role.GUEST && hostEndpoint != null

    private var hostEndpoint: String? = null
    private val pendingNames = HashMap<String, String>()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var broadcastJob: Job? = null
    private var tickJob: Job? = null
    private var pingJob: Job? = null

    // Clock sync (guest): hostClock ~= localClock + clockOffset
    private var clockOffset = 0L
    private var bestRtt = Long.MAX_VALUE
    private var lastStateMessage: JSONObject? = null
    private var mirroring = false
    private var lastSeekAt = 0L

    private val myName: String
        get() = app.settings.current.jamName.ifBlank { Build.MODEL ?: "Phone" }

    // ---------------------------------------------------------------------------------------
    // Hosting
    // ---------------------------------------------------------------------------------------

    fun startHosting() {
        if (_state.value.active) return
        val code = JamQr.newCode()
        _state.value = State(role = Role.HOST, hostName = myName, connected = true, code = code)
        connectController {
            val options = AdvertisingOptions.Builder()
                .setStrategy(STRATEGY)
                // Never drop the phone's current Wi-Fi (e.g. wireless Android Auto) to upgrade.
                .setConnectionType(ConnectionType.NON_DISRUPTIVE)
                .build()
            // The code rides along in the advertised name so guests can find this host by it.
            client.startAdvertising("$myName$CODE_SEPARATOR$code", SERVICE_ID, hostLifecycle, options)
                .addOnFailureListener { fail(it) }
            scheduleBroadcast()
            startTicking()
        }
    }

    private val hostLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val name = pendingNames.remove(endpointId) ?: context.getString(R.string.jam_guest)
            if (result.status.isSuccess) {
                _state.update { s -> s.copy(members = s.members.filter { it.endpointId != endpointId } + Member(endpointId, name)) }
                app.playerConnection.showMessage(context.getString(R.string.jam_joined, name))
                buildStateMessage()?.let { send(listOf(endpointId), it) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            val member = _state.value.members.firstOrNull { it.endpointId == endpointId }
            _state.update { s -> s.copy(members = s.members.filter { it.endpointId != endpointId }) }
            member?.let { app.playerConnection.showMessage(context.getString(R.string.jam_left, it.name)) }
        }
    }

    fun setPlayEverywhere(enabled: Boolean) {
        _state.update { it.copy(playEverywhere = enabled) }
        scheduleBroadcast()
    }

    fun setGuestsCanControl(enabled: Boolean) {
        _state.update { it.copy(guestsCanControl = enabled) }
        scheduleBroadcast()
    }

    fun kick(member: Member) {
        client.disconnectFromEndpoint(member.endpointId)
        _state.update { s -> s.copy(members = s.members.filter { it.endpointId != member.endpointId }) }
    }

    private fun handleGuestMessage(endpointId: String, msg: JSONObject) {
        val c = controller ?: return
        val s = _state.value
        val who = s.members.firstOrNull { it.endpointId == endpointId }?.name ?: context.getString(R.string.jam_a_guest)
        when (msg.optString("t")) {
            JamProtocol.ADD -> {
                val tracks = JamProtocol.tracksFromJson(msg.optJSONArray("tracks"))
                applyAdd(c, tracks, msg.optBoolean("next"))
                if (tracks.isNotEmpty()) {
                    val what = if (tracks.size == 1) {
                        "“${tracks[0].title}”"
                    } else {
                        context.resources.getQuantityString(R.plurals.songs_count, tracks.size, tracks.size)
                    }
                    app.playerConnection.showMessage(context.getString(R.string.jam_added_by, who, what))
                }
            }
            JamProtocol.MOVE -> applyMove(c, msg.optInt("from"), msg.optInt("to"), msg.optString("id"))
            JamProtocol.REMOVE -> applyRemove(c, msg.optInt("index"), msg.optString("id"))
            JamProtocol.PLAY_AT -> if (s.guestsCanControl) applyPlayAt(c, msg.optInt("index"), msg.optString("id"))
            JamProtocol.CONTROL -> if (s.guestsCanControl) applyControl(c, msg.optString("a"))
            JamProtocol.PING -> send(
                listOf(endpointId),
                message(JamProtocol.PONG) {
                    put("t0", msg.optLong("t0"))
                    put("ht", SystemClock.elapsedRealtime())
                },
            )
        }
    }

    // Queue operations applied on the host's player ------------------------------------------

    private fun applyAdd(c: MediaController, tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        val items = tracks.map { it.toMediaItem(context) }
        val wasEmpty = c.mediaItemCount == 0
        if (next && !wasEmpty) {
            c.addMediaItems(min(c.currentMediaItemIndex + 1, c.mediaItemCount), items)
        } else {
            c.addMediaItems(items)
        }
        if (wasEmpty) {
            c.prepare()
            c.play()
        }
    }

    /** Index sanity check: the queue may have changed since the guest saw it. */
    private fun locate(c: MediaController, index: Int, id: String): Int {
        if (index in 0 until c.mediaItemCount && c.getMediaItemAt(index).trackId() == id) return index
        for (i in 0 until c.mediaItemCount) if (c.getMediaItemAt(i).trackId() == id) return i
        return -1
    }

    private fun applyMove(c: MediaController, from: Int, to: Int, id: String) {
        val real = locate(c, from, id)
        if (real < 0) return
        c.moveMediaItem(real, to.coerceIn(0, c.mediaItemCount - 1))
    }

    private fun applyRemove(c: MediaController, index: Int, id: String) {
        val real = locate(c, index, id)
        if (real < 0 || real == c.currentMediaItemIndex) return
        c.removeMediaItem(real)
    }

    private fun applyPlayAt(c: MediaController, index: Int, id: String) {
        val real = locate(c, index, id)
        if (real < 0) return
        c.seekToDefaultPosition(real)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    private fun applyControl(c: MediaController, action: String) {
        when (action) {
            "toggle" -> if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
            "next" -> c.seekToNext()
            "prev" -> c.seekToPrevious()
        }
    }

    // Broadcasting the host's queue -------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (_state.value.role == Role.HOST) scheduleBroadcast()
        }
    }

    /** Coalesces bursts of player events into one message. */
    private fun scheduleBroadcast() {
        if (_state.value.role != Role.HOST) return
        broadcastJob?.cancel()
        broadcastJob = scope.launch {
            delay(250)
            val msg = buildStateMessage() ?: return@launch
            applyState(msg)
            send(_state.value.members.map { it.endpointId }, msg)
        }
    }

    /** While everyone plays along, a periodic state keeps guests' positions in sync. */
    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(5_000)
                val s = _state.value
                if (s.playEverywhere && s.isPlaying && s.members.isNotEmpty()) scheduleBroadcast()
            }
        }
    }

    private fun buildStateMessage(): JSONObject? {
        val c = controller ?: return null
        val count = c.mediaItemCount
        val current = if (count == 0) -1 else c.currentMediaItemIndex
        val start = max(0, current - 3)
        var end = min(count, start + 100)
        val s = _state.value
        var msg: JSONObject
        do {
            val tracks = (start until end).map { c.getMediaItemAt(it).toTrack() }
            msg = message(JamProtocol.STATE) {
                put("q", JamProtocol.tracksToJson(tracks))
                put("o", start)
                put("n", count)
                put("i", current)
                put("p", c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING))
                put("pos", c.currentPosition)
                put("ht", SystemClock.elapsedRealtime())
                put("ev", s.playEverywhere)
                put("gc", s.guestsCanControl)
                put("hn", s.hostName ?: myName)
            }
            val fits = msg.toString().length <= JamProtocol.MAX_MESSAGE_CHARS || end - start <= 5
            // Too big for one message: send a shorter window of the queue.
            if (!fits) end = start + (end - start) * 2 / 3
        } while (!fits)
        return msg
    }

    // ---------------------------------------------------------------------------------------
    // Joining
    // ---------------------------------------------------------------------------------------

    fun startSearching(joinCode: String? = null) {
        if (_state.value.active) return
        _state.value = State(role = Role.GUEST, searching = true, joinCode = joinCode)
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { fail(it) }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val host = parseHost(endpointId, info.endpointName)
            _state.update { s -> s.copy(hosts = s.hosts.filter { it.endpointId != endpointId } + host) }
            joinIfWanted()
        }

        override fun onEndpointLost(endpointId: String) {
            _state.update { s -> s.copy(hosts = s.hosts.filter { it.endpointId != endpointId }) }
        }
    }

    /**
     * True when a Jam can start right away, without asking anything: permissions were granted
     * before and Bluetooth is on. Used by the car's Jam button, where nothing can be asked.
     */
    fun canStartWithoutAsking(): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED } &&
            isBluetoothOn(context)

    /** Joins the Jam with [code] (from a QR code): searches, then connects when it's found. */
    fun joinByCode(code: String) {
        val s = _state.value
        when {
            !s.active -> startSearching(joinCode = code)
            s.role == Role.GUEST && hostEndpoint == null -> {
                _state.update { it.copy(joinCode = code, error = null) }
                joinIfWanted()
            }
        }
        _state.update { it.copy(invite = null) }
    }

    /** A code from a scanned QR code or an opened harmonic://jam link, before joining. */
    fun setInvite(code: String?) {
        if (_state.value.active) return
        _state.update { it.copy(invite = code) }
    }

    private fun joinIfWanted() {
        val s = _state.value
        val code = s.joinCode ?: return
        if (hostEndpoint != null || s.connectingTo != null) return
        s.hosts.firstOrNull { it.code == code }?.let { join(it) }
    }

    private fun parseHost(endpointId: String, endpointName: String): FoundHost {
        val i = endpointName.lastIndexOf(CODE_SEPARATOR)
        val code = if (i >= 0) JamQr.parse(endpointName.substring(i + CODE_SEPARATOR.length)) else null
        val name = if (code != null) endpointName.substring(0, i) else endpointName
        return FoundHost(endpointId, name, code)
    }

    fun join(host: FoundHost) {
        if (_state.value.role != Role.GUEST || hostEndpoint != null) return
        _state.update { it.copy(connectingTo = host.endpointId, hostName = host.name, error = null) }
        val options = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .build()
        client.requestConnection(myName, host.endpointId, guestLifecycle, options)
            .addOnFailureListener { e ->
                _state.update { it.copy(connectingTo = null) }
                fail(e)
            }
    }

    private val guestLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                hostEndpoint = endpointId
                client.stopDiscovery()
                _state.update { it.copy(searching = false, connectingTo = null, connected = true, error = null) }
                startPinging()
            } else {
                _state.update { it.copy(connectingTo = null, error = context.getString(R.string.jam_join_failed)) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId != hostEndpoint) return
            stopMirroring()
            cleanup()
            _state.value = State(error = context.getString(R.string.jam_ended))
        }
    }

    private fun handleHostMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            JamProtocol.STATE -> {
                lastStateMessage = msg
                applyState(msg)
                if (msg.optBoolean("ev")) mirror() else stopMirroring()
            }
            JamProtocol.PONG -> {
                val now = SystemClock.elapsedRealtime()
                val t0 = msg.optLong("t0")
                val rtt = now - t0
                // Trust the fastest round trips: they have the least asymmetric delay.
                if (rtt <= bestRtt + 30) {
                    clockOffset = msg.optLong("ht") - (t0 + rtt / 2)
                    bestRtt = min(bestRtt, rtt)
                }
            }
        }
    }

    private fun startPinging() {
        pingJob?.cancel()
        bestRtt = Long.MAX_VALUE
        pingJob = scope.launch {
            var n = 0
            while (isActive) {
                hostEndpoint?.let { host ->
                    send(listOf(host), message(JamProtocol.PING) { put("t0", SystemClock.elapsedRealtime()) })
                }
                // A quick burst first for a good initial estimate, then occasional refreshes.
                delay(if (n++ < 5) 400 else 15_000)
                if (n == 40) bestRtt = Long.MAX_VALUE // allow re-estimation if conditions changed
            }
        }
    }

    // Play along (guest) --------------------------------------------------------------------

    private fun hostNow() = SystemClock.elapsedRealtime() + clockOffset

    private fun mirror() {
        val msg = lastStateMessage ?: return
        val c = controller
        if (c == null) {
            connectController { mirror() }
            return
        }
        mirroring = true
        val queue = JamProtocol.tracksFromJson(msg.optJSONArray("q"))
        val index = msg.optInt("i", -1) - msg.optInt("o")
        val target = queue.getOrNull(index) ?: return
        val hostPlaying = msg.optBoolean("p")
        val elapsed = if (hostPlaying) hostNow() - msg.optLong("ht") else 0L
        val expected = (msg.optLong("pos") + elapsed).coerceAtLeast(0)
        val now = SystemClock.elapsedRealtime()

        if (c.mediaItemCount == 0 || c.currentMediaItem?.trackId() != target.id) {
            c.setMediaItems(queue.map { it.toMediaItem(context) }, index, expected)
            c.prepare()
            lastSeekAt = now
        } else if (c.playbackState == Player.STATE_READY && now - lastSeekAt > 4_000) {
            if (abs(c.currentPosition - expected) > 350) {
                c.seekTo(expected)
                lastSeekAt = now
            }
        }
        c.playWhenReady = hostPlaying
    }

    private fun stopMirroring() {
        if (!mirroring) return
        mirroring = false
        controller?.pause()
    }

    // ---------------------------------------------------------------------------------------
    // Actions from the UI (host applies directly, guest asks the host)
    // ---------------------------------------------------------------------------------------

    fun add(tracks: List<Track>, next: Boolean) {
        val list = tracks.take(50)
        when {
            _state.value.role == Role.HOST -> controller?.let { applyAdd(it, list, next) }
            isGuest -> sendToHost(message(JamProtocol.ADD) {
                put("tracks", JamProtocol.tracksToJson(list))
                put("next", next)
            })
        }
    }

    fun move(from: Int, to: Int) {
        val id = trackAt(from)?.id ?: return
        when {
            _state.value.role == Role.HOST -> controller?.let { applyMove(it, from, to, id) }
            isGuest -> sendToHost(message(JamProtocol.MOVE) {
                put("from", from)
                put("to", to)
                put("id", id)
            })
        }
    }

    fun remove(index: Int) {
        val id = trackAt(index)?.id ?: return
        when {
            _state.value.role == Role.HOST -> controller?.let { applyRemove(it, index, id) }
            isGuest -> sendToHost(message(JamProtocol.REMOVE) {
                put("index", index)
                put("id", id)
            })
        }
    }

    fun playAt(index: Int) {
        val id = trackAt(index)?.id ?: return
        when {
            _state.value.role == Role.HOST -> controller?.let { applyPlayAt(it, index, id) }
            isGuest -> sendToHost(message(JamProtocol.PLAY_AT) {
                put("index", index)
                put("id", id)
            })
        }
    }

    /** "toggle", "next" or "prev". */
    fun control(action: String) {
        when {
            _state.value.role == Role.HOST -> controller?.let { applyControl(it, action) }
            isGuest -> sendToHost(message(JamProtocol.CONTROL) { put("a", action) })
        }
    }

    val canControl: Boolean
        get() = _state.value.role == Role.HOST || (isGuest && _state.value.guestsCanControl)

    /** Guests: tapping a song adds it to the Jam instead of playing it on this phone. */
    fun interceptPlay(tracks: List<Track>, startIndex: Int): Boolean {
        if (!isGuest) return false
        val track = tracks.getOrNull(startIndex) ?: return true
        add(listOf(track), next = false)
        app.playerConnection.showMessage(context.getString(R.string.jam_added_one, track.title))
        return true
    }

    fun interceptQueue(tracks: List<Track>, next: Boolean): Boolean {
        if (!isGuest) return false
        add(tracks, next)
        app.playerConnection.showMessage(
            if (tracks.size == 1) {
                context.getString(R.string.jam_added_one, tracks[0].title)
            } else {
                context.resources.getQuantityString(R.plurals.jam_added_many, tracks.size, tracks.size)
            },
        )
        return true
    }

    fun leave() {
        when (_state.value.role) {
            Role.HOST -> {
                client.stopAdvertising()
                client.stopAllEndpoints()
            }
            Role.GUEST -> {
                client.stopDiscovery()
                hostEndpoint?.let { client.disconnectFromEndpoint(it) }
                stopMirroring()
            }
            Role.NONE -> Unit
        }
        cleanup()
        _state.value = State()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    private fun trackAt(absoluteIndex: Int): Track? {
        val s = _state.value
        return s.queue.getOrNull(absoluteIndex - s.queueOffset)
    }

    private fun applyState(msg: JSONObject) {
        _state.update { s ->
            s.copy(
                queue = JamProtocol.tracksFromJson(msg.optJSONArray("q")),
                queueOffset = msg.optInt("o"),
                queueSize = msg.optInt("n"),
                currentIndex = msg.optInt("i", -1),
                isPlaying = msg.optBoolean("p"),
                playEverywhere = msg.optBoolean("ev"),
                guestsCanControl = msg.optBoolean("gc", true),
                hostName = msg.optString("hn").ifEmpty { s.hostName },
            )
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val msg = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
            if (_state.value.role == Role.HOST) handleGuestMessage(endpointId, msg) else handleHostMessage(msg)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun send(to: List<String>, msg: JSONObject) {
        if (to.isEmpty()) return
        client.sendPayload(to, Payload.fromBytes(msg.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun sendToHost(msg: JSONObject) {
        hostEndpoint?.let { send(listOf(it), msg) }
    }

    private fun connectController(onReady: () -> Unit) {
        controller?.let {
            onReady()
            return
        }
        val future = controllerFuture ?: MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync().also { controllerFuture = it }
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (controller == null) {
                controller = c
                c.addListener(playerListener)
            }
            if (_state.value.active) onReady()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun cleanup() {
        broadcastJob?.cancel()
        tickJob?.cancel()
        pingJob?.cancel()
        hostEndpoint = null
        lastStateMessage = null
        mirroring = false
        pendingNames.clear()
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
    }

    private fun fail(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        val text = when (code) {
            ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING,
            ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING,
            -> return
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            -> context.getString(R.string.jam_need_permission)
            ConnectionsStatusCodes.STATUS_RADIO_ERROR ->
                context.getString(R.string.jam_need_bluetooth)
            else -> e.message ?: context.getString(R.string.jam_error)
        }
        _state.update { it.copy(error = text) }
    }

    companion object {
        private const val SERVICE_ID = "app.harmonic.music.jam"
        /** Between the host's name and its Jam code in the advertised name: "Bruno #K7QX". */
        private const val CODE_SEPARATOR = " #"
        private val STRATEGY = Strategy.P2P_STAR

        /** Runtime permissions Nearby Connections needs on this Android version. */
        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun isBluetoothOn(context: Context): Boolean = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)
    }
}
