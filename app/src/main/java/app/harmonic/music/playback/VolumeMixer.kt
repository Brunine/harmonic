package app.harmonic.music.playback

import androidx.media3.common.Player

/**
 * The player's volume is shared by three features that must not overwrite each other:
 * lowering the music for navigation prompts, the sleep timer's fade-out and volume
 * normalization. Each sets its own factor; the player gets the product.
 *
 * Only ever lowers the volume (every factor is at most 1), so it never clips. Main thread.
 */
class VolumeMixer {
    private var player: Player? = null

    /** Lowered while something else talks (see [InterruptionHandler]). */
    var duck = 1f
        set(value) { field = value.coerceIn(0f, 1f); apply() }

    /** Sleep timer fade-out. */
    var fade = 1f
        set(value) { field = value.coerceIn(0f, 1f); apply() }

    /** Volume normalization for the current song. */
    var normalization = 1f
        set(value) { field = value.coerceIn(0f, 1f); apply() }

    fun attach(player: Player) {
        this.player = player
        apply()
    }

    fun detach() {
        player = null
    }

    private fun apply() {
        player?.volume = duck * fade * normalization
    }
}
