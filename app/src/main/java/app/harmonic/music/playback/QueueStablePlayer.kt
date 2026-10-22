package app.harmonic.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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

    /**
     * Passes every player event on to [delegate], except duration-only timeline updates.
     *
     * Every callback is forwarded by hand on purpose: all of Player.Listener's methods are Java
     * default methods, and Kotlin's "by delegate" does not forward those. (Version 1.6.0 relied
     * on it, so the session never heard that music was playing.)
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class StableListener(
        private val delegate: Player.Listener,
        private val owner: QueueStablePlayer,
    ) : Player.Listener {
        private var lastIds: List<String>? = null

        fun metadataChanged(metadata: MediaMetadata) = delegate.onMediaMetadataChanged(metadata)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val ids = mediaIds(timeline)
            if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && ids == lastIds) return
            lastIds = ids
            delegate.onTimelineChanged(timeline, reason)
        }

        // While an overlay shows, song changes must not replace it.
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
            delegate.onMediaMetadataChanged(owner.overlay ?: mediaMetadata)

        override fun onEvents(player: Player, events: Player.Events) = delegate.onEvents(player, events)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = delegate.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = delegate.onTracksChanged(tracks)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = delegate.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = delegate.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = delegate.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = delegate.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = delegate.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = delegate.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = delegate.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = delegate.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            delegate.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = delegate.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = delegate.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPlayerError(error: PlaybackException) = delegate.onPlayerError(error)
        override fun onPlayerErrorChanged(error: PlaybackException?) = delegate.onPlayerErrorChanged(error)
        override fun onPositionDiscontinuity(reason: Int) = delegate.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            delegate.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = delegate.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = delegate.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = delegate.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = delegate.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = delegate.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = delegate.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = delegate.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = delegate.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = delegate.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = delegate.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = delegate.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = delegate.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = delegate.onMetadata(metadata)

        private fun mediaIds(timeline: Timeline): List<String> {
            val window = Timeline.Window()
            return List(timeline.windowCount) { timeline.getWindow(it, window).mediaItem.mediaId }
        }

        // Equal to another wrapper of the same listener, so removeListener finds it.
        override fun equals(other: Any?) = other is StableListener && other.delegate == delegate
        override fun hashCode() = delegate.hashCode()
    }
}
