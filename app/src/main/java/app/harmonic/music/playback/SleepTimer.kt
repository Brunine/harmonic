package app.harmonic.music.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stops the music after a while or at the end of the current song. The last 10 seconds fade out
 * so it never cuts off abruptly. Lives in the app container; the playback service attaches its
 * player while it runs.
 */
class SleepTimer(private val volume: VolumeMixer) {
    sealed interface State {
        data object Off : State
        /** [endsAt] is in [SystemClock.elapsedRealtime] milliseconds. */
        data class Running(val endsAt: Long) : State
        data object EndOfSong : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var job: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // "End of song" pauses through pauseAtEndOfMediaItems; then the timer is done.
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM &&
                _state.value == State.EndOfSong
            ) {
                cancel()
            }
        }
    }

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(listener)
    }

    fun detach() {
        player?.removeListener(listener)
        cancel()
        player = null
    }

    fun startMinutes(minutes: Int) {
        cancel()
        val endsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.value = State.Running(endsAt)
        job = scope.launch {
            delay((endsAt - SystemClock.elapsedRealtime() - FADE_MS).coerceAtLeast(0))
            fadeOutAndPause()
            cancel()
        }
    }

    fun startEndOfSong() {
        cancel()
        player?.pauseAtEndOfMediaItems = true
        _state.value = State.EndOfSong
    }

    fun cancel() {
        job?.cancel()
        job = null
        volume.fade = 1f
        player?.pauseAtEndOfMediaItems = false
        _state.value = State.Off
    }

    private suspend fun fadeOutAndPause() {
        val p = player ?: return
        val steps = 20
        for (i in 1..steps) {
            volume.fade = 1f - i.toFloat() / steps
            delay(FADE_MS / steps)
        }
        p.pause()
        volume.fade = 1f
    }

    private companion object {
        const val FADE_MS = 10_000L
    }
}
