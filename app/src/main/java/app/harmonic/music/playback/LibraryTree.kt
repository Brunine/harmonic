package app.harmonic.music.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.db.SongEntity
import app.harmonic.music.data.model.Track

/**
 * The browse tree shown by Android Auto (and other media browsers):
 *
 *   root
 *   ├── Recent          (playable songs)
 *   ├── Playlists       ├── <playlist>  (playable songs)
 *   ├── Downloads       (playable songs, works offline)
 *   └── Liked           (playable songs)
 *
 * Song children get ids "<parentId>|<videoId>" so that tapping one queues the whole list.
 */
class LibraryTree(private val context: Context, private val container: AppContainer) {

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(RECENT, context.getString(R.string.auto_recent), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(PLAYLISTS, context.getString(R.string.auto_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(DOWNLOADS, context.getString(R.string.auto_downloads), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(LIKED, context.getString(R.string.auto_liked), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        parentId == PLAYLISTS -> container.db.playlistDao().allOnce().map {
            folder("$PLAYLIST_PREFIX${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)
        }
        else -> tracksFor(parentId).map { it.toMediaItem(context, "$parentId|${it.id}") }
    }

    /** The full list a browse id refers to (used to queue a whole folder from one tap). */
    suspend fun tracksFor(parentId: String): List<Track> {
        if (parentId.startsWith(SEARCH_PREFIX)) {
            val (q, results) = lastSearch
            return if (parentId.removePrefix(SEARCH_PREFIX) == q) results else search(parentId.removePrefix(SEARCH_PREFIX))
        }
        val songs = container.db.songDao()
        val list: List<SongEntity> = when {
            parentId == RECENT -> songs.recentOnce(LIMIT)
            parentId == DOWNLOADS -> songs.downloadedOnce(LIMIT)
            parentId == LIKED -> songs.likedOnce(LIMIT)
            parentId.startsWith(PLAYLIST_PREFIX) ->
                parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                    ?.let { container.db.playlistDao().songsOnce(it) }
                    .orEmpty()
            else -> emptyList()
        }
        return list.map { it.toTrack() }
    }

    suspend fun item(mediaId: String): MediaItem? {
        if (mediaId == ROOT) return root()
        if ('|' !in mediaId) {
            children(ROOT).firstOrNull { it.mediaId == mediaId }?.let { return it }
            if (mediaId.startsWith(PLAYLIST_PREFIX)) {
                return children(PLAYLISTS).firstOrNull { it.mediaId == mediaId }
            }
            return container.db.songDao().get(mediaId)?.toTrack()?.toMediaItem(context)
        }
        val parent = mediaId.substringBeforeLast('|')
        return tracksFor(parent).firstOrNull { it.id == mediaId.substringAfterLast('|') }
            ?.toMediaItem(context, mediaId)
    }

    @Volatile
    private var lastSearch: Pair<String, List<Track>> = "" to emptyList()

    /** Offline-first search: matching downloaded/known songs, then YouTube Music results. */
    suspend fun search(query: String): List<Track> {
        if (lastSearch.first == query && lastSearch.second.isNotEmpty()) return lastSearch.second
        val local = container.db.songDao().searchLocal(query, 10).map { it.toTrack() }
        val online = if (container.hasNetwork()) {
            runCatching { container.catalog.searchSongs(query) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val results = (local + online).distinctBy { it.id }.take(40)
        lastSearch = query to results
        return results
    }

    fun searchItems(query: String, results: List<Track>): List<MediaItem> =
        results.map { it.toMediaItem(context, "$SEARCH_PREFIX$query|${it.id}") }

    private fun folder(id: String, title: String, type: Int) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADS = "downloads"
        const val LIKED = "liked"
        const val PLAYLIST_PREFIX = "playlist:"
        const val SEARCH_PREFIX = "search:"
        private const val LIMIT = 200
    }
}
