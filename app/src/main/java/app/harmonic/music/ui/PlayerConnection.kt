package app.harmonic.music.ui

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.harmonic.music.data.model.Track
import app.harmonic.music.jam.JamManager
import app.harmonic.music.playback.PlaybackService
import app.harmonic.music.playback.toMediaItem
import app.harmonic.music.playback.toTrack
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

/**
 * The UI's handle on the playback service. Connected while the activity is visible and
 * released in onStop, so the app process holds nothing extra in the background.
 */
class PlayerConnection(private val context: Context, private val jam: () -> JamManager) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    /** Short messages for a snackbar: playback errors, Jam events. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            syncQueue(c)
            syncState(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) syncQueue(player)
            syncState(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _messages.tryEmit(error.cause?.message ?: error.message ?: "Playback error")
        }
    }

    private fun syncQueue(p: Player) {
        _queue.value = List(p.mediaItemCount) { p.getMediaItemAt(it).toTrack() }
    }

    private fun syncState(p: Player) {
        _current.value = p.currentMediaItem?.toTrack()
        _state.value = PlaybackUiState(
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING && p.playWhenReady,
            currentIndex = p.currentMediaItemIndex,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
        )
    }

    /** Position and duration, polled only while a screen is collecting it. */
    fun progress(intervalMs: Long = 500): Flow<Pair<Long, Long>> = flow {
        while (true) {
            val c = controller
            if (c != null) {
                val duration = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                emit(c.currentPosition to duration)
            }
            delay(intervalMs)
        }
    }

    // While this phone is a Jam guest, "play" and "queue" actions add to the shared Jam queue.

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        if (jam().interceptPlay(tracks, startIndex)) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
            c.setMediaItems(tracks.map { it.toMediaItem(context) }, start, C.TIME_UNSET)
            c.prepare()
            c.play()
        }
    }

    fun playNext(tracks: List<Track>) {
        if (jam().interceptQueue(tracks, next = true)) return
        playNextLocal(tracks)
    }

    private fun playNextLocal(tracks: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) {
            play(tracks)
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toMediaItem(context) })
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (jam().interceptQueue(tracks, next = false)) return
        addToQueueLocal(tracks)
    }

    private fun addToQueueLocal(tracks: List<Track>) = withController { c ->
        if (c.mediaItemCount == 0) play(tracks) else c.addMediaItems(tracks.map { it.toMediaItem(context) })
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun playAt(index: Int) = withController { c ->
        c.seekToDefaultPosition(index)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun moveItem(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
