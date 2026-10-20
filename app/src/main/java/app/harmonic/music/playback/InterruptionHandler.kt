package app.harmonic.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import app.harmonic.music.data.prefs.AppSettings
import app.harmonic.music.data.prefs.InterruptAction

/**
 * Decides what happens to the music when something else needs the sound, following the user's
 * choices in Settings (Pause / Lower volume / Keep playing) for each situation:
 *
 *  - CALL:         phone or internet call ringing or in progress (audio mode)
 *  - RECORDING:    another app records (voice message, video), seen through recording callbacks
 *  - NAVIGATION:   short "may duck" interruptions (navigation, Assistant, notifications)
 *  - OTHER_SOUND:  other short interruptions (playing a voice message, a video preview)
 *  - OTHER_MUSIC:  another app starts playing for good
 *
 * Replaces ExoPlayer's built-in audio focus handling. Focus is requested with
 * "will pause when ducked", so Android hands ducking decisions to us instead of ducking
 * automatically, which is what makes "Keep playing" possible for navigation prompts.
 */
class InterruptionHandler(
    context: Context,
    private val player: Player,
    private val volume: VolumeMixer,
    private val settings: () -> AppSettings,
) {
    enum class Situation { CALL, RECORDING, NAVIGATION, OTHER_SOUND, OTHER_MUSIC }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(context)

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ onFocusChange(it) }, handler)
        .build()

    private var hasFocus = false
    /** Last focus loss we got (an AUDIOFOCUS_LOSS_* constant), 0 when we hold focus. */
    private var focusLoss = 0
    private var recordingActive = false
    /** We paused the music ourselves and may resume it when the interruption ends. */
    private var pausedByUs = false
    private var lowered = false
    /** Guards against reacting to our own play/pause changes. */
    private var applying = false

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (applying) return
            if (playWhenReady) {
                // The user pressed play: they want music now, even mid-interruption.
                pausedByUs = false
                requestFocus()
            } else {
                // The user paused: forget any interruption and let other apps have the sound.
                pausedByUs = false
                setLowered(false)
                abandonFocus()
            }
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val active = configs.any { it.clientAudioSource.isUserRecording() }
            if (active != recordingActive) {
                recordingActive = active
                evaluate()
            }
        }
    }

    private val modeListener: Any? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { evaluate() }
        } else {
            null
        }

    fun start() {
        player.addListener(playerListener)
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.addOnModeChangedListener(executor, modeListener as AudioManager.OnModeChangedListener)
        }
        if (player.playWhenReady) requestFocus()
    }

    fun release() {
        player.removeListener(playerListener)
        audioManager.unregisterAudioRecordingCallback(recordingCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener)
        }
        abandonFocus()
    }

    // ---------------------------------------------------------------------------------------

    private fun requestFocus() {
        if (hasFocus) return
        val result = audioManager.requestAudioFocus(focusRequest)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) focusLoss = 0
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        focusLoss = 0
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                focusLoss = 0
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                hasFocus = false
                focusLoss = change
            }
        }
        evaluate()
    }

    private fun currentSituation(): Situation? {
        val mode = audioManager.mode
        val inCall = mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING)
        return when {
            inCall -> Situation.CALL
            recordingActive -> Situation.RECORDING
            focusLoss == AudioManager.AUDIOFOCUS_LOSS -> Situation.OTHER_MUSIC
            focusLoss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Situation.NAVIGATION
            focusLoss == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Situation.OTHER_SOUND
            else -> null
        }
    }

    private fun actionFor(situation: Situation): InterruptAction {
        val s = settings()
        return when (situation) {
            Situation.CALL -> s.onCalls
            Situation.RECORDING -> s.onRecording
            Situation.NAVIGATION -> s.onNavigation
            Situation.OTHER_SOUND -> s.onOtherSounds
            // Another app taking over for good can't be "lowered" against.
            Situation.OTHER_MUSIC -> if (s.onOtherMusic == InterruptAction.KEEP) InterruptAction.KEEP else InterruptAction.PAUSE
        }
    }

    /** Re-checks the situation and applies the matching action, or restores the music. */
    private fun evaluate() {
        val situation = currentSituation()
        if (situation == null) {
            restore()
            return
        }
        // Nothing to interrupt if the music isn't playing (and we didn't pause it).
        if (!player.playWhenReady && !pausedByUs) return

        when (actionFor(situation)) {
            InterruptAction.PAUSE -> {
                setLowered(false)
                if (player.playWhenReady) {
                    // A permanent takeover by another player never auto-resumes.
                    pausedByUs = situation != Situation.OTHER_MUSIC
                    setPlayWhenReady(false)
                    if (situation == Situation.OTHER_MUSIC) abandonFocus()
                }
            }
            InterruptAction.LOWER -> {
                setLowered(true)
                resumeIfWePaused()
            }
            InterruptAction.KEEP -> {
                setLowered(false)
                resumeIfWePaused()
            }
        }
    }

    private fun restore() {
        setLowered(false)
        if (pausedByUs) {
            pausedByUs = false
            if (settings().resumeAfterInterruption) {
                requestFocus()
                setPlayWhenReady(true)
            }
        }
    }

    private fun resumeIfWePaused() {
        if (pausedByUs) {
            pausedByUs = false
            setPlayWhenReady(true)
        }
    }

    private fun setLowered(lower: Boolean) {
        if (lowered == lower) return
        lowered = lower
        volume.duck = if (lower) DUCK_VOLUME else 1f
    }

    private fun setPlayWhenReady(play: Boolean) {
        applying = true
        try {
            player.playWhenReady = play
        } finally {
            applying = false
        }
    }

    /** Recordings a person makes (not always-on hotword listening or screen-audio capture). */
    private fun Int.isUserRecording(): Boolean =
        this != MediaRecorder.AudioSource.REMOTE_SUBMIX && this != HOTWORD_SOURCE

    private companion object {
        const val DUCK_VOLUME = 0.2f
        /** MediaRecorder.AudioSource.HOTWORD, a hidden constant. */
        const val HOTWORD_SOURCE = 1999
    }
}
