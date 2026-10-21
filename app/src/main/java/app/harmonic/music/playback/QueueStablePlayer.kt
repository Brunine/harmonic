package app.harmonic.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * The player as the media session sees it.
 *
 * ExoPlayer reports a "timeline change" every time a song finishes loading and its exact
 * length becomes known, several times per song. Media3 answers each one by sending the whole
 * queue to Android Auto again, and Android Auto redraws its queue list and jumps back to the
 * top. Those updates change nothing about the queue itself (same songs, same order), so they
 * are not passed on; real queue changes (songs added, moved, removed) still are.
 *
 * It can also show something else in place of the song's details for a while ([overlay]):
 * the Jam QR code as album art on the car screen and in the notification.
 */
@OptIn(UnstableApi::class)
class QueueStablePlayer(player: Player) : ForwardingPlayer(player) {

    private val listeners = LinkedHashSet<StableListener>()

    /** Shown instead of the playing song's metadata while set. Main thread. */
    var overlay: MediaMetadata? = null
        set(value) {
            field = value
            val shown = mediaMetadata
            listeners.toList().forEach { it.metadataChanged(shown) }
        }

    override fun getMediaMetadata(): MediaMetadata = overlay ?: super.getMediaMetadata()

    override fun addListener(listener: Player.Listener) {
        val wrapper = StableListener(listener, this)
        listeners += wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = StableListener(listener, this)
        listeners -= wrapper
        super.removeListener(wrapper)
    }

    private class StableListener(
        private val delegate: Player.Listener,
        private val owner: QueueStablePlayer,
    ) : Player.Listener by delegate {
        private var lastIds: List<String>? = null

        fun metadataChanged(metadata: MediaMetadata) = delegate.onMediaMetadataChanged(metadata)

        // While an overlay shows, song changes must not replace it.
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            delegate.onMediaMetadataChanged(owner.overlay ?: mediaMetadata)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val ids = mediaIds(timeline)
            if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && ids == lastIds) return
            lastIds = ids
            delegate.onTimelineChanged(timeline, reason)
        }

        private fun mediaIds(timeline: Timeline): List<String> {
            val window = Timeline.Window()
            return List(timeline.windowCount) { timeline.getWindow(it, window).mediaItem.mediaId }
        }

        // Equal to another wrapper of the same listener, so removeListener finds it.
        override fun equals(other: Any?) = other is StableListener && other.delegate == delegate
        override fun hashCode() = delegate.hashCode()
    }
}
