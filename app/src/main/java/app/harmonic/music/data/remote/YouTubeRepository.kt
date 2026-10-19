package app.harmonic.music.data.remote

import android.util.LruCache
import app.harmonic.music.data.model.OnlineCollection
import app.harmonic.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

enum class SearchFilter(val label: String, val contentFilter: String) {
    SONGS("Songs", YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
    VIDEOS("Videos", YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS),
    ALBUMS("Albums", YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS),
    PLAYLISTS("Playlists", YoutubeSearchQueryHandlerFactory.MUSIC_PLAYLISTS),
}

sealed interface SearchResult {
    data class Song(val track: Track) : SearchResult
    data class Collection(val collection: OnlineCollection) : SearchResult
}

data class SearchPage(val items: List<SearchResult>, val nextPage: Page?)

data class OnlinePlaylist(
    val url: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val tracks: List<Track>,
    val nextPage: Page?,
)

/** All YouTube / YouTube Music metadata access goes through NewPipeExtractor here. */
class YouTubeRepository(private val thumbnailPx: () -> Int) {
    private val service = ServiceList.YouTube

    private class CachedInfo(val info: StreamInfo, val fetchedAt: Long)

    // Stream URLs live ~6 hours; keep extracted info for 1 hour so skipping back and forth,
    // related-track lookups and prefetches never repeat the extraction requests.
    private val streamCache = LruCache<String, CachedInfo>(48)

    fun videoIdFromUrl(url: String): String? =
        runCatching { service.streamLHFactory.getId(url) }.getOrNull()

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    suspend fun search(query: String, filter: SearchFilter): SearchPage = withContext(Dispatchers.IO) {
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter.contentFilter), "")
        val info = SearchInfo.getInfo(service, handler)
        SearchPage(info.relatedItems.mapNotNull { toResult(it, filter) }, info.nextPage)
    }

    suspend fun searchMore(query: String, filter: SearchFilter, page: Page): SearchPage =
        withContext(Dispatchers.IO) {
            val handler = service.searchQHFactory.fromQuery(query, listOf(filter.contentFilter), "")
            val more = SearchInfo.getMoreItems(service, handler, page)
            SearchPage(more.items.mapNotNull { toResult(it, filter) }, more.nextPage)
        }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    /** Blocking; called from player loader threads. */
    fun streamInfoBlocking(id: String): StreamInfo {
        val now = System.currentTimeMillis()
        streamCache.get(id)?.let { if (now - it.fetchedAt < STREAM_INFO_TTL) return it.info }
        val info = StreamInfo.getInfo(service, watchUrl(id))
        streamCache.put(id, CachedInfo(info, now))
        return info
    }

    fun invalidate(id: String) {
        streamCache.remove(id)
    }

    suspend fun track(id: String): Track = withContext(Dispatchers.IO) {
        val info = streamInfoBlocking(id)
        Track(
            id = id,
            title = info.name,
            artist = info.uploaderName.orEmpty().removeSuffix(" - Topic"),
            durationSec = info.duration,
            thumbnailUrl = Thumbnails.pick(info.thumbnails, id, thumbnailPx()),
            isVideo = true,
        )
    }

    /** Tracks YouTube recommends after [id]; used for radio / autoplay. */
    suspend fun related(id: String): List<Track> = withContext(Dispatchers.IO) {
        streamInfoBlocking(id).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType != StreamType.LIVE_STREAM && it.streamType != StreamType.AUDIO_LIVE_STREAM }
            .mapNotNull { toTrack(it, isVideo = true) }
            .distinctBy { it.id }
    }

    suspend fun playlist(url: String): OnlinePlaylist = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(service, url)
        OnlinePlaylist(
            url = url,
            title = info.name,
            author = info.uploaderName.orEmpty(),
            thumbnailUrl = Thumbnails.pick(info.thumbnails, null, thumbnailPx()),
            tracks = info.relatedItems.mapNotNull { toTrack(it, isVideo = false) }.distinctBy { it.id },
            nextPage = info.nextPage,
        )
    }

    suspend fun playlistMore(url: String, page: Page): Pair<List<Track>, Page?> =
        withContext(Dispatchers.IO) {
            val more = PlaylistInfo.getMoreItems(service, url, page)
            more.items.mapNotNull { toTrack(it, isVideo = false) } to more.nextPage
        }

    /** Loads every page of a playlist (capped) for importing into the local library. */
    suspend fun playlistAll(url: String, maxTracks: Int = 2000): OnlinePlaylist {
        val first = playlist(url)
        val all = first.tracks.toMutableList()
        var page = first.nextPage
        while (Page.isValid(page) && all.size < maxTracks) {
            val (tracks, next) = playlistMore(url, page!!)
            if (tracks.isEmpty()) break
            all += tracks
            page = next
        }
        return first.copy(tracks = all.distinctBy { it.id }, nextPage = null)
    }

    private fun toResult(item: InfoItem, filter: SearchFilter): SearchResult? = when (item) {
        is StreamInfoItem -> toTrack(item, isVideo = filter == SearchFilter.VIDEOS)?.let { SearchResult.Song(it) }
        is PlaylistInfoItem -> SearchResult.Collection(
            OnlineCollection(
                url = item.url,
                title = item.name,
                subtitle = item.uploaderName.orEmpty(),
                thumbnailUrl = Thumbnails.pick(item.thumbnails, null, thumbnailPx()),
                isAlbum = filter == SearchFilter.ALBUMS,
            ),
        )
        else -> null
    }

    private fun toTrack(item: StreamInfoItem, isVideo: Boolean): Track? {
        val id = videoIdFromUrl(item.url) ?: return null
        return Track(
            id = id,
            title = item.name,
            artist = item.uploaderName.orEmpty().removeSuffix(" - Topic"),
            durationSec = item.duration,
            thumbnailUrl = Thumbnails.pick(item.thumbnails, id, thumbnailPx()),
            isVideo = isVideo,
        )
    }

    private companion object {
        const val STREAM_INFO_TTL = 60 * 60 * 1000L
    }
}
