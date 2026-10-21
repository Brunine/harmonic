package app.harmonic.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
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
 */
@OptIn(UnstableApi::class)
class QueueStablePlayer(player: Player) : ForwardingPlayer(player) {

    override fun addListener(listener: Player.Listener) {
        super.addListener(StableListener(listener))
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(StableListener(listener))
    }

    private class StableListener(private val delegate: Player.Listener) : Player.Listener by delegate {
        private var lastIds: List<String>? = null

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
