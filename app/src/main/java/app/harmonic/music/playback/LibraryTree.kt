package app.harmonic.music.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.db.SongEntity
import app.harmonic.music.data.model.Track
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The browse tree shown by Android Auto (and other media browsers):
 *
 *   root                (Android Auto shows these four as tabs)
 *   ├── Quick picks     (recommendations, a grid of covers, cached so it opens instantly)
 *   ├── Recent          (playable songs)
 *   ├── Playlists       (a grid of covers)
 *   │   ├── Liked songs (playable songs)
 *   │   └── <playlist>  (playable songs)
 *   └── Downloads       (playable songs, works offline)
 *
 * Song children get ids "<parentId>|<videoId>" so that tapping one queues the whole list.
 */
class LibraryTree(private val context: Context, private val container: AppContainer) {

    fun root(): MediaItem = folder(ROOT, context.getString(R.string.app_name), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(
                QUICK_PICKS, context.getString(R.string.quick_picks), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                childStyle = MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
            ),
            folder(RECENT, context.getString(R.string.auto_recent), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(
                PLAYLISTS, context.getString(R.string.auto_playlists), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                childStyle = MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
            ),
            folder(DOWNLOADS, context.getString(R.string.auto_downloads), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        )
        parentId == PLAYLISTS -> {
            val likedCover = container.db.songDao().likedOnce(1).firstOrNull()?.thumbnailUrl
            listOf(
                folder(LIKED, context.getString(R.string.auto_liked), MediaMetadata.MEDIA_TYPE_PLAYLIST, likedCover),
            ) + container.db.playlistDao().summariesOnce().map {
                folder("$PLAYLIST_PREFIX${it.playlist.id}", it.playlist.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, it.thumbnailUrl)
            }
        }
        else -> tracksFor(parentId).map { it.toMediaItem(context, "$parentId|${it.id}") }
    }

    /** The full list a browse id refers to (used to queue a whole folder from one tap). */
    suspend fun tracksFor(parentId: String): List<Track> {
        if (parentId.startsWith(SEARCH_PREFIX)) {
            val (q, results) = lastSearch
            return if (parentId.removePrefix(SEARCH_PREFIX) == q) results else search(parentId.removePrefix(SEARCH_PREFIX))
        }
        if (parentId == QUICK_PICKS) return quickPicks()
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
            if (mediaId.startsWith(PLAYLIST_PREFIX) || mediaId == LIKED) {
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

    /**
     * Cached recommendations; refreshed in the background. With nothing cached yet, waits a
     * few seconds for a first list rather than showing an empty tab.
     */
    private suspend fun quickPicks(): List<Track> {
        val picks = container.quickPicks
        if (picks.items.value.isEmpty() && container.hasNetwork()) {
            withTimeoutOrNull(8_000) { picks.refresh() }
        } else {
            picks.refreshIfNeeded()
        }
        return picks.items.value
    }

    /**
     * [childStyle] (a content style key) shows this folder's children as a grid of covers
     * instead of a list. [coverUrl] is the folder's own artwork.
     */
    private fun folder(
        id: String,
        title: String,
        type: Int,
        coverUrl: String? = null,
        childStyle: String? = null,
    ) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .setArtworkUri(ArtworkProvider.uriFor(context, coverUrl))
                .apply {
                    if (childStyle != null) {
                        setExtras(Bundle().apply { putInt(childStyle, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM) })
                    }
                }
                .build(),
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val QUICK_PICKS = "quickpicks"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADS = "downloads"
        const val LIKED = "liked"
        const val PLAYLIST_PREFIX = "playlist:"
        const val SEARCH_PREFIX = "search:"
        private const val LIMIT = 200
    }
}
