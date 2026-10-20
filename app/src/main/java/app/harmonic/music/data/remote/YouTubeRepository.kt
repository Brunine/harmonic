package app.harmonic.music.data.remote

import android.net.Uri
import android.util.LruCache
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.model.OnlineCollection
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.soundCloudUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

sealed interface SearchResult {
    data class Song(val track: Track) : SearchResult
    data class Collection(val collection: OnlineCollection) : SearchResult
}

/** [next] is an opaque continuation token (a NewPipe Page, a Deezer index...), null at the end. */
data class SearchPage(val items: List<SearchResult>, val next: Any?)

data class OnlinePlaylist(
    val url: String,
    val title: String,
    val author: String,
    val thumbnailUrl: String?,
    val tracks: List<Track>,
    val next: Any?,
)

/**
 * YouTube, YouTube Music and SoundCloud access through NewPipeExtractor.
 * (NewPipeExtractor supports both services with the same API.)
 */
class YouTubeRepository(private val thumbnailPx: () -> Int) {
    private val youtube = ServiceList.YouTube
    private val soundcloud = ServiceList.SoundCloud

    private class CachedInfo(val info: StreamInfo, val fetchedAt: Long)

    // Stream URLs live ~6 hours; keep extracted info for 30 minutes so skipping back and forth,
    // related-track lookups and prefetches never repeat the extraction requests.
    private val streamCache = LruCache<String, CachedInfo>(48)

    fun videoIdFromUrl(url: String): String? =
        runCatching { youtube.streamLHFactory.getId(url) }.getOrNull()

    /** "sc:<user>/<track>" for a SoundCloud track permalink, null for anything else. */
    fun soundCloudIdFromUrl(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host ?: return null
        if (host != "soundcloud.com" && !host.endsWith(".soundcloud.com")) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[1] in setOf("sets", "tracks", "albums", "likes", "reposts")) return null
        return "sc:${segments[0].lowercase()}/${segments[1].lowercase()}"
    }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun urlFor(id: String) =
        if (id.startsWith("sc:")) soundCloudUrl(id) else watchUrl(id)

    private fun serviceFor(id: String): StreamingService =
        if (id.startsWith("sc:")) soundcloud else youtube

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    /** [contentFilter] is a NewPipe content filter of the service, e.g. "music_songs" or "tracks". */
    fun searchBlocking(source: MusicSource, query: String, contentFilter: String, page: Page?): SearchPage {
        val service = if (source == MusicSource.SOUNDCLOUD) soundcloud else youtube
        val handler = service.searchQHFactory.fromQuery(query, listOf(contentFilter), "")
        val isVideo = contentFilter == "music_videos" || contentFilter == "videos"
        val isAlbum = contentFilter == "music_albums"
        return if (page == null) {
            val info = SearchInfo.getInfo(service, handler)
            SearchPage(info.relatedItems.mapNotNull { toResult(it, isVideo, isAlbum) }, validPage(info.nextPage))
        } else {
            val more = SearchInfo.getMoreItems(service, handler, page)
            SearchPage(more.items.mapNotNull { toResult(it, isVideo, isAlbum) }, validPage(more.nextPage))
        }
    }

    /** First page of YouTube Music songs, then videos if there are none. Used for matching. */
    fun searchSongsBlocking(query: String): List<Track> {
        val songs = searchBlocking(MusicSource.YOUTUBE, query, "music_songs", null).items
            .filterIsInstance<SearchResult.Song>().map { it.track }
        if (songs.isNotEmpty()) return songs
        return searchBlocking(MusicSource.YOUTUBE, query, "music_videos", null).items
            .filterIsInstance<SearchResult.Song>().map { it.track }
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { youtube.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------------------------------
    // Streams
    // ---------------------------------------------------------------------------------------

    /** Blocking; called from player loader threads. Works for YouTube ids and "sc:" ids. */
    fun streamInfoBlocking(id: String): StreamInfo {
        val now = System.currentTimeMillis()
        streamCache.get(id)?.let { if (now - it.fetchedAt < STREAM_INFO_TTL) return it.info }
        val info = StreamInfo.getInfo(serviceFor(id), urlFor(id))
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
            thumbnailUrl = Thumbnails.pick(info.thumbnails, if (id.startsWith("sc:")) null else id, thumbnailPx()),
            isVideo = !id.startsWith("sc:"),
        )
    }

    /** Tracks the service recommends after [id]; used for radio / autoplay. */
    suspend fun related(id: String): List<Track> = withContext(Dispatchers.IO) {
        streamInfoBlocking(id).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType != StreamType.LIVE_STREAM && it.streamType != StreamType.AUDIO_LIVE_STREAM }
            .mapNotNull { toTrack(it, isVideo = !id.startsWith("sc:")) }
            .filter { it.id != id }
            .distinctBy { it.id }
    }

    // ---------------------------------------------------------------------------------------
    // Playlists (YouTube playlists / albums and SoundCloud sets)
    // ---------------------------------------------------------------------------------------

    fun isPlaylistUrl(url: String): Boolean = runCatching {
        NewPipe.getServiceByUrl(url).playlistLHFactory.acceptUrl(url)
    }.getOrDefault(false)

    suspend fun playlist(url: String): OnlinePlaylist = withContext(Dispatchers.IO) {
        val info = PlaylistInfo.getInfo(url)
        OnlinePlaylist(
            url = url,
            title = info.name,
            author = info.uploaderName.orEmpty(),
            thumbnailUrl = Thumbnails.pick(info.thumbnails, null, thumbnailPx()),
            tracks = info.relatedItems.mapNotNull { toTrack(it, isVideo = false) }.distinctBy { it.id },
            next = validPage(info.nextPage),
        )
    }

    suspend fun playlistMore(url: String, page: Page): Pair<List<Track>, Page?> =
        withContext(Dispatchers.IO) {
            val more = PlaylistInfo.getMoreItems(NewPipe.getServiceByUrl(url), url, page)
            more.items.mapNotNull { toTrack(it, isVideo = false) } to validPage(more.nextPage)
        }

    private fun validPage(page: Page?): Page? = page?.takeIf { Page.isValid(it) }

    private fun toResult(item: InfoItem, isVideo: Boolean, isAlbum: Boolean): SearchResult? = when (item) {
        is StreamInfoItem -> toTrack(item, isVideo)?.let { SearchResult.Song(it) }
        is PlaylistInfoItem -> SearchResult.Collection(
            OnlineCollection(
                url = item.url,
                title = item.name,
                subtitle = item.uploaderName.orEmpty(),
                thumbnailUrl = Thumbnails.pick(item.thumbnails, null, thumbnailPx()),
                isAlbum = isAlbum,
                source = if (item.serviceId == soundcloud.serviceId) MusicSource.SOUNDCLOUD else MusicSource.YOUTUBE,
            ),
        )
        else -> null
    }

    private fun toTrack(item: StreamInfoItem, isVideo: Boolean): Track? {
        if (item.serviceId == soundcloud.serviceId) {
            val id = soundCloudIdFromUrl(item.url) ?: return null
            return Track(
                id = id,
                title = item.name,
                artist = item.uploaderName.orEmpty(),
                durationSec = item.duration,
                thumbnailUrl = Thumbnails.pick(item.thumbnails, null, thumbnailPx()),
                isVideo = false,
            )
        }
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
        const val STREAM_INFO_TTL = 30 * 60 * 1000L
    }
}
