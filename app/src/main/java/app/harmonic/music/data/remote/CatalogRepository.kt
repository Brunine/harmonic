package app.harmonic.music.data.remote

import androidx.annotation.StringRes
import app.harmonic.music.R
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.Page

data class CatalogFilter(@StringRes val label: Int, val key: String)

/**
 * One entry point for every music source: search, albums/playlists, links and radio.
 * Results always carry their source; Deezer and Apple Music tracks play via [TrackMatcher].
 */
class CatalogRepository(
    private val youtube: YouTubeRepository,
    private val deezer: DeezerClient,
    private val apple: AppleMusicClient,
    private val matcher: TrackMatcher,
    private val client: OkHttpClient,
) {
    fun filters(source: MusicSource): List<CatalogFilter> = when (source) {
        MusicSource.YOUTUBE -> listOf(
            CatalogFilter(R.string.filter_songs, "music_songs"),
            CatalogFilter(R.string.filter_videos, "music_videos"),
            CatalogFilter(R.string.filter_albums, "music_albums"),
            CatalogFilter(R.string.filter_playlists, "music_playlists"),
        )
        MusicSource.SOUNDCLOUD -> listOf(
            CatalogFilter(R.string.filter_tracks, "tracks"),
            CatalogFilter(R.string.filter_playlists, "playlists"),
        )
        MusicSource.DEEZER -> listOf(
            CatalogFilter(R.string.filter_tracks, "track"),
            CatalogFilter(R.string.filter_albums, "album"),
            CatalogFilter(R.string.filter_playlists, "playlist"),
        )
        MusicSource.APPLE -> listOf(
            CatalogFilter(R.string.filter_songs, "song"),
            CatalogFilter(R.string.filter_albums, "album"),
        )
    }

    suspend fun search(source: MusicSource, filter: CatalogFilter, query: String, next: Any? = null): SearchPage =
        withContext(Dispatchers.IO) {
            when (source) {
                MusicSource.YOUTUBE, MusicSource.SOUNDCLOUD ->
                    youtube.searchBlocking(source, query, filter.key, next as? Page)
                MusicSource.DEEZER -> deezer.search(
                    query,
                    DeezerClient.Kind.entries.first { it.path == filter.key },
                    next as? Int ?: 0,
                )
                MusicSource.APPLE ->
                    if (next != null) SearchPage(emptyList(), null)
                    else if (filter.key == "album") apple.searchAlbums(query) else apple.searchSongs(query)
            }
        }

    /**
     * Songs from every source at once, interleaved (YouTube, SoundCloud, Deezer, Apple, YouTube...)
     * so each source shows near the top. A source that fails or times out is simply left out.
     */
    suspend fun searchAll(query: String, perSource: Int = 8): SearchPage = coroutineScope {
        val searches = MusicSource.entries.map { source ->
            async {
                withTimeoutOrNull(12_000) {
                    runCatching { search(source, filters(source).first(), query).items }.getOrNull()
                }.orEmpty().filterIsInstance<SearchResult.Song>().take(perSource)
            }
        }
        val lists = searches.awaitAll()
        val merged = ArrayList<SearchResult>()
        for (i in 0 until perSource) lists.forEach { list -> list.getOrNull(i)?.let { merged += it } }
        SearchPage(merged.distinctBy { (it as SearchResult.Song).track.id }, null)
    }

    /** YouTube Music songs; used by Android Auto voice search. */
    suspend fun searchSongs(query: String): List<Track> =
        search(MusicSource.YOUTUBE, filters(MusicSource.YOUTUBE).first(), query).items
            .filterIsInstance<SearchResult.Song>()
            .map { it.track }

    // ---------------------------------------------------------------------------------------
    // Albums and playlists from any source
    // ---------------------------------------------------------------------------------------

    suspend fun collection(url: String): OnlinePlaylist = withContext(Dispatchers.IO) {
        DeezerClient.parse(url)?.let { (kind, id) ->
            if (kind != DeezerClient.Kind.TRACK) return@withContext deezer.collection(kind, id, url)
        }
        AppleMusicClient.parse(url)?.let { (kind, id) ->
            if (kind == "album") return@withContext apple.album(id, url)
        }
        youtube.playlist(url)
    }

    suspend fun collectionMore(url: String, next: Any): Pair<List<Track>, Any?> = withContext(Dispatchers.IO) {
        val deezerRef = DeezerClient.parse(url)
        when {
            deezerRef != null -> deezer.collectionTracks(deezerRef.first, deezerRef.second, next as Int)
            next is Page -> youtube.playlistMore(url, next)
            else -> emptyList<Track>() to null
        }
    }

    /** Loads every page (capped) for importing or playing a whole collection. */
    suspend fun collectionAll(url: String, maxTracks: Int = 2000): OnlinePlaylist {
        val first = collection(url)
        val all = first.tracks.toMutableList()
        var next = first.next
        while (next != null && all.size < maxTracks) {
            val (tracks, following) = collectionMore(url, next)
            if (tracks.isEmpty()) break
            all += tracks
            next = following
        }
        return first.copy(tracks = all.distinctBy { it.id }, next = null)
    }

    // ---------------------------------------------------------------------------------------
    // Shared links
    // ---------------------------------------------------------------------------------------

    sealed interface LinkTarget {
        data class Song(val track: Track) : LinkTarget
        data class Collection(val url: String) : LinkTarget
    }

    /** Understands YouTube, YouTube Music, SoundCloud, Deezer and Apple Music links. */
    suspend fun resolveLink(rawUrl: String): LinkTarget? = withContext(Dispatchers.IO) {
        val url = if (DeezerClient.isShortLink(rawUrl)) expandRedirect(rawUrl) else rawUrl

        DeezerClient.parse(url)?.let { (kind, id) ->
            return@withContext if (kind == DeezerClient.Kind.TRACK) {
                LinkTarget.Song(deezer.trackById(id))
            } else {
                LinkTarget.Collection("https://www.deezer.com/${kind.path}/$id")
            }
        }
        AppleMusicClient.parse(url)?.let { (kind, id) ->
            return@withContext if (kind == "song") {
                LinkTarget.Song(apple.trackById(id))
            } else {
                LinkTarget.Collection("https://music.apple.com/album/$id")
            }
        }
        youtube.soundCloudIdFromUrl(url)?.let { return@withContext LinkTarget.Song(youtube.track(it)) }
        youtube.videoIdFromUrl(url)?.let { return@withContext LinkTarget.Song(youtube.track(it)) }
        if (youtube.isPlaylistUrl(url)) return@withContext LinkTarget.Collection(url)
        null
    }

    private fun expandRedirect(url: String): String = runCatching {
        client.newCall(Request.Builder().url(url).head().build()).execute().use { it.request.url.toString() }
    }.getOrDefault(url)

    // ---------------------------------------------------------------------------------------
    // Radio
    // ---------------------------------------------------------------------------------------

    /** What to play after [track]: the source's own recommendations, or YouTube's for its match. */
    suspend fun related(track: Track): List<Track> = when {
        track.source.playsViaYouTube -> {
            val ytId = withContext(Dispatchers.IO) { matcher.youtubeIdFor(track.id) }
            youtube.related(ytId)
        }
        else -> youtube.related(track.id)
    }
}
