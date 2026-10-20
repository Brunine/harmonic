package app.harmonic.music.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import app.harmonic.music.data.db.SongDao
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.canDownload
import app.harmonic.music.playback.watchUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** Download progress for one track, as shown in the UI. */
data class DownloadState(val state: Int, val percent: Float) {
    val isDone get() = state == Download.STATE_COMPLETED
    val isActive get() = state == Download.STATE_QUEUED || state == Download.STATE_DOWNLOADING ||
        state == Download.STATE_RESTARTING
}

/**
 * Offline downloads. Audio is written into the dedicated download cache (keyed by video id),
 * which the player reads before touching the network, so a downloaded track plays offline
 * from any screen, playlist or Android Auto without special handling.
 */
@OptIn(UnstableApi::class)
class DownloadUtil(
    private val context: Context,
    databaseProvider: () -> DatabaseProvider,
    private val downloadCache: () -> Cache,
    private val playerCache: () -> Cache,
    upstreamFactory: () -> DataSource.Factory,
    private val songDao: SongDao,
    private val scope: CoroutineScope,
    wifiOnly: () -> Boolean,
) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** Must be first touched on the main thread (DownloadManager binds to the calling looper). */
    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            context,
            databaseProvider(),
            downloadCache(),
            upstreamFactory(),
            Executors.newFixedThreadPool(2),
        ).apply {
            maxParallelDownloads = 2
            requirements = requirementsFor(wifiOnly())
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    publish(download)
                    if (download.state == Download.STATE_COMPLETED) {
                        scope.launch(Dispatchers.IO) {
                            songDao.setDownloaded(download.request.id, System.currentTimeMillis())
                        }
                    }
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    _states.update { it - download.request.id }
                    scope.launch(Dispatchers.IO) { songDao.setDownloaded(download.request.id, 0) }
                }
            })
            loadInitialStates(this)
        }
    }

    private fun publish(download: Download) {
        val percent = download.percentDownloaded.takeIf { it >= 0 } ?: 0f
        _states.update { it + (download.request.id to DownloadState(download.state, percent)) }
    }

    private fun loadInitialStates(manager: DownloadManager) {
        scope.launch(Dispatchers.IO) {
            val map = HashMap<String, DownloadState>()
            manager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    map[d.request.id] = DownloadState(d.state, d.percentDownloaded.coerceAtLeast(0f))
                }
            }
            _states.update { map + it }
            // Resume unfinished downloads under the service's foreground notification. This
            // is refused when the app was started in the background; the scheduler retries.
            if (map.values.any { it.isActive }) {
                runCatching { DownloadService.start(context, ExoDownloadService::class.java) }
            }
            // Reconcile the database with the download index (e.g. after clearing app data).
            val completed = map.filterValues { it.isDone }.keys
            val marked = songDao.downloadedIds().toSet()
            (marked - completed).forEach { songDao.setDownloaded(it, 0) }
            (completed - marked).forEach { songDao.setDownloaded(it, System.currentTimeMillis()) }
        }
    }

    /**
     * Queues offline copies. Returns how many tracks were skipped because their source can't be
     * downloaded (SoundCloud streams are short-lived HLS playlists).
     */
    fun download(tracks: List<Track>): Int {
        downloadManager // make sure the manager and its listener exist
        val allowed = tracks.filter { it.canDownload }
        scope.launch(Dispatchers.IO) {
            songDao.upsertAll(allowed)
            allowed.forEach { track ->
                if (_states.value[track.id]?.let { it.isDone || it.isActive } == true) return@forEach
                // Drop any partial copy in the streaming cache so it is never mixed with the
                // download's (possibly different) audio format.
                runCatching { playerCache().removeResource(track.id) }
                val request = DownloadRequest.Builder(track.id, watchUri(track.id))
                    .setCustomCacheKey(track.id)
                    .setData(track.title.toByteArray())
                    .build()
                DownloadService.sendAddDownload(context, ExoDownloadService::class.java, request, false)
            }
        }
        return tracks.size - allowed.size
    }

    fun remove(id: String) {
        DownloadService.sendRemoveDownload(context, ExoDownloadService::class.java, id, false)
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        DownloadService.sendSetRequirements(
            context, ExoDownloadService::class.java, requirementsFor(wifiOnly), false,
        )
    }

    private fun requirementsFor(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
}
