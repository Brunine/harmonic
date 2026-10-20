package app.harmonic.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.container
import app.harmonic.music.data.db.toEntity
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.source
import app.harmonic.music.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Background playback. Media3 keeps this as a foreground service with a media notification
 * while playing, exposes it to the lock screen, Bluetooth, Wear OS and Android Auto, and
 * handles audio focus and "becoming noisy" (headphones unplugged).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var app: AppContainer
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var tree: LibraryTree
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("queue", MODE_PRIVATE) }

    private var sponsorJob: Job? = null
    private val sponsorMessages = ArrayList<PlayerMessage>()
    private var queueSaveJob: Job? = null
    private var retriedId: String? = null
    private val radioRequested = HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        app = container
        tree = LibraryTree(this, app)

        player = ExoPlayer.Builder(this, audioOnlyRenderers())
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.playbackDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Holds a Wi-Fi lock only while actually playing streamed content.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(listener)
        applyTrackParameters(app.settings.current.audioOffload)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(sessionActivity)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        scope.launch {
            app.settings.flow.map { it.audioOffload }.distinctUntilChanged().collect {
                applyTrackParameters(it)
            }
        }
        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        saveQueueNow()
        scope.cancel()
        session.release()
        player.removeListener(listener)
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------
    // Player setup (battery / data tuning)
    // ---------------------------------------------------------------------------------------

    /** Only an audio renderer: no video decoders are ever created, the thumbnail is the art. */
    private fun audioOnlyRenderers() = RenderersFactory { handler, _, audioListener, _, _ ->
        arrayOf<Renderer>(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener))
    }

    /**
     * Audio is tiny compared to video, so we buffer in bursts: fill up to 2 minutes, then let
     * the radio sleep until the buffer drops to 30 seconds. Fewer radio wake-ups = less battery.
     * Playback starts after just 1 second of audio for a fast start.
     */
    private fun loadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(30_000, 120_000, 1_000, 2_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /** Offload hands decoding to the DSP so the CPU can sleep with the screen off. */
    private fun applyTrackParameters(offload: Boolean) {
        val offloadPrefs = AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                if (offload) {
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                } else {
                    AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                },
            )
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(offloadPrefs)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // ---------------------------------------------------------------------------------------
    // Player events
    // ---------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val id = mediaItem.trackId()
            retriedId = null
            recordPlay(mediaItem)
            scheduleSponsorBlock(mediaItem)
            prefetchNext()
            maybeExtendQueue(mediaItem.toTrack())
            saveQueueSoon()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveQueueSoon()
                prefetchNext()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveQueueSoon()
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError()
        }
    }

    private fun recordPlay(item: MediaItem) {
        val track = item.toTrack()
        scope.launch(Dispatchers.IO) {
            val dao = app.db.songDao()
            dao.upsert(track)
            dao.markPlayed(track.id, System.currentTimeMillis())
        }
    }

    private fun prefetchNext() {
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET || !player.playWhenReady) return
        val id = player.getMediaItemAt(next).trackId()
        if (app.downloads.states.value[id]?.isDone == true) return
        app.resolver.prefetch(id)
    }

    /** Radio: when the queue is about to run out, append what YouTube recommends next. */
    private fun maybeExtendQueue(track: Track) {
        val id = track.id
        if (!app.settings.current.autoplayRelated) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount - player.currentMediaItemIndex > 3) return
        if (!app.hasNetwork() || !radioRequested.add(id)) return
        scope.launch {
            val related = runCatching { app.catalog.related(track) }.getOrNull() ?: return@launch
            val existing = HashSet<String>()
            for (i in 0 until player.mediaItemCount) existing += player.getMediaItemAt(i).trackId()
            val toAdd = related.filter { it.id !in existing }.take(20)
            if (toAdd.isNotEmpty()) {
                player.addMediaItems(toAdd.map { it.toMediaItem(this@PlaybackService) })
            }
        }
    }

    /**
     * Skips non-music parts of music videos. Uses player messages fired at exact positions
     * instead of polling the playback position, so it costs no extra wake-ups.
     */
    private fun scheduleSponsorBlock(item: MediaItem) {
        sponsorJob?.cancel()
        sponsorMessages.forEach { it.cancel() }
        sponsorMessages.clear()
        val track = item.toTrack()
        if (!app.settings.current.sponsorBlock || !track.isVideo || track.source != MusicSource.YOUTUBE) return
        if (!app.hasNetwork()) return
        val id = track.id
        val index = player.currentMediaItemIndex
        sponsorJob = scope.launch {
            val segments = app.sponsorBlock.segments(id)
            if (player.currentMediaItem?.trackId() != id) return@launch
            for (seg in segments) {
                val pos = player.currentPosition
                if (pos in seg.startMs until seg.endMs) {
                    player.seekTo(seg.endMs)
                    continue
                }
                if (seg.startMs < pos) continue
                sponsorMessages += player.createMessage { _, _ ->
                    if (player.currentMediaItem?.trackId() == id && player.currentPosition < seg.endMs) {
                        player.seekTo(seg.endMs)
                    }
                }
                    .setLooper(Looper.getMainLooper())
                    .setPosition(index, seg.startMs)
                    .setDeleteAfterDelivery(true)
                    .send()
            }
        }
    }

    /** Expired URLs (HTTP 403) are re-resolved once; a track that still fails is skipped. */
    private fun handleError() {
        val item = player.currentMediaItem ?: return
        val id = item.trackId()
        app.resolver.invalidate(id)
        if (retriedId != id) {
            retriedId = id
            player.prepare()
            return
        }
        if (app.hasNetwork() && player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.prepare()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Queue persistence (restored on start and for "resume" from headset / car buttons)
    // ---------------------------------------------------------------------------------------

    private fun saveQueueSoon() {
        queueSaveJob?.cancel()
        queueSaveJob = scope.launch {
            delay(1_500)
            saveQueueNow()
        }
    }

    private fun saveQueueNow() {
        val count = player.mediaItemCount
        if (count == 0) {
            prefs.edit().remove(KEY_IDS).apply()
            return
        }
        val start = max(0, player.currentMediaItemIndex - 100)
        val end = min(count, start + MAX_SAVED)
        val items = (start until end).map { player.getMediaItemAt(it) }
        val index = player.currentMediaItemIndex - start
        val position = player.currentPosition
        val entities = items.map { it.toTrack().toEntity() }
        app.appScope.launch(Dispatchers.IO) {
            app.db.songDao().insertIgnoreAll(entities)
            prefs.edit()
                .putString(KEY_IDS, items.joinToString(",") { it.trackId() })
                .putInt(KEY_INDEX, index)
                .putLong(KEY_POSITION, position)
                .apply()
        }
    }

    private suspend fun loadSavedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val ids = prefs.getString(KEY_IDS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return null
        val byId = withContext(Dispatchers.IO) { app.db.songDao().getAll(ids) }.associateBy { it.id }
        val tracks = ids.mapNotNull { byId[it]?.toTrack() }
        if (tracks.isEmpty()) return null
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, tracks.size - 1)
        return MediaSession.MediaItemsWithStartPosition(
            tracks.map { it.toMediaItem(this) },
            index,
            prefs.getLong(KEY_POSITION, 0),
        )
    }

    /** Puts the last queue back without preparing it, so nothing is downloaded until play. */
    private fun restoreQueue() {
        scope.launch {
            val saved = loadSavedQueue() ?: return@launch
            if (player.mediaItemCount == 0) {
                player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Session / browse callbacks (app UI, Android Auto, Assistant, Bluetooth)
    // ---------------------------------------------------------------------------------------

    /**
     * Fills in metadata for bare ids and stores every track before it plays: Deezer and Apple
     * Music tracks are matched to YouTube by title/artist read from the database.
     */
    private suspend fun resolveItems(items: List<MediaItem>): List<MediaItem> {
        val tracks = items.map { item ->
            when {
                item.hasMetadata() -> item.toTrack()
                else -> withContext(Dispatchers.IO) { app.db.songDao().get(item.trackId())?.toTrack() }
                    ?: runCatching { app.youtube.track(item.trackId()) }.getOrNull()
                    ?: Track(item.trackId(), item.trackId(), "")
            }
        }
        withContext(Dispatchers.IO) { app.db.songDao().insertIgnoreAll(tracks.map { it.toEntity() }) }
        return tracks.map { it.toMediaItem(this) }
    }

    private fun <T> paged(all: List<T>, page: Int, pageSize: Int): List<T> {
        val from = page.toLong() * pageSize
        if (from >= all.size) return emptyList()
        return all.drop(from.toInt()).take(pageSize)
    }

    private val callback = object : MediaLibrarySession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            resolveItems(mediaItems).toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()

            // "Hey Google, play <query> on Harmonic" / Android Auto voice search
            val query = single?.requestMetadata?.searchQuery
            if (query != null) {
                val tracks = if (query.isBlank()) {
                    withContext(Dispatchers.IO) { app.db.songDao().recentOnce(50) }.map { it.toTrack() }
                } else {
                    tree.search(query)
                }
                return@future MediaSession.MediaItemsWithStartPosition(
                    tracks.map { it.toMediaItem(this@PlaybackService) }, 0, C.TIME_UNSET,
                )
            }

            // Tapping one song inside a browsed folder queues the whole folder from that song.
            if (single != null && '|' in single.mediaId) {
                val tracks = tree.tracksFor(single.mediaId.substringBeforeLast('|'))
                val index = tracks.indexOfFirst { it.id == single.trackId() }
                if (index >= 0) {
                    return@future MediaSession.MediaItemsWithStartPosition(
                        tracks.map { it.toMediaItem(this@PlaybackService) }, index, startPositionMs,
                    )
                }
            }

            MediaSession.MediaItemsWithStartPosition(resolveItems(mediaItems), startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            loadSavedQueue() ?: throw UnsupportedOperationException("Nothing to resume")
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply { putBoolean(EXTRA_SEARCH_SUPPORTED, true) }
            return Futures.immediateFuture(
                LibraryResult.ofItem(tree.root(), LibraryParams.Builder().setExtras(extras).build()),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(paged(tree.children(parentId), page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val item = tree.item(mediaId)
            if (item != null) {
                LibraryResult.ofItem(item, null)
            } else {
                LibraryResult.ofError<MediaItem>(SessionError.ERROR_BAD_VALUE)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = tree.search(query)
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val items = tree.searchItems(query, tree.search(query))
            LibraryResult.ofItemList(paged(items, page, pageSize), params)
        }
    }

    private companion object {
        const val KEY_IDS = "ids"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val MAX_SAVED = 300
        const val EXTRA_SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
