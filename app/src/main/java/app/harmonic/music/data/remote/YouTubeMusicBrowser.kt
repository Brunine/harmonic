package app.harmonic.music.data.remote

import app.harmonic.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One artist page: header, "Top songs" and the carousels (albums, singles, videos...). */
data class ArtistPage(
    val id: String,
    val name: String,
    /** Wide header picture when the artist has one, else the round avatar. */
    val bannerUrl: String?,
    val description: String?,
    val topSongs: List<Track>,
    /** Playlist with all of the artist's songs ("See all" under Top songs), when offered. */
    val songsPlaylistUrl: String?,
    val sections: List<ArtistSection>,
)

/** A carousel on an artist page; [title] comes from YouTube already translated. */
data class ArtistSection(val title: String, val items: List<BrowseItem>)

/** A card in a carousel. */
sealed interface BrowseItem {
    val title: String
    val subtitle: String
    val thumbnailUrl: String?

    data class Album(
        val browseId: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
    ) : BrowseItem

    data class Playlist(
        val url: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
    ) : BrowseItem

    data class Artist(
        val id: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
    ) : BrowseItem

    data class Song(
        val track: Track,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String?,
    ) : BrowseItem
}

data class AlbumTrack(val number: Int?, val track: Track)

data class AlbumPage(
    val browseId: String,
    val title: String,
    val artist: String,
    val artistId: String?,
    /** Release year, when YouTube shows one. */
    val year: Int?,
    /** "Album • 2013", already translated by YouTube. */
    val subtitle: String,
    /** "12 songs • 45 minutes", already translated by YouTube. */
    val details: String,
    val thumbnailUrl: String?,
    /** The album as a playlist URL, for saving it to the library. */
    val playlistUrl: String?,
    val tracks: List<AlbumTrack>,
)

/**
 * Artist and album pages from YouTube Music's own web API ("browse" requests of the
 * WEB_REMIX client). NewPipeExtractor does not cover these pages. Parsing is deliberately
 * forgiving: it looks renderers up by name wherever they sit, so small layout changes on
 * YouTube's side degrade the page instead of breaking it.
 *
 * Only small JSON responses are fetched (no HTML, no images); pages are kept in memory for
 * a while so going back and forth costs no data.
 */
class YouTubeMusicBrowser(
    private val client: OkHttpClient,
    private val thumbnailPx: () -> Int,
) {
    private class Cached<T>(val value: T, val at: Long)

    private val artists = ConcurrentHashMap<String, Cached<ArtistPage>>()
    private val albums = ConcurrentHashMap<String, Cached<AlbumPage>>()
    /** Album playlist id ("OLAK5uy_...") to album browse id ("MPREb_..."). */
    private val albumIds = ConcurrentHashMap<String, String>()

    // ---------------------------------------------------------------------------------------
    // Artists
    // ---------------------------------------------------------------------------------------

    suspend fun artist(id: String): ArtistPage = withContext(Dispatchers.IO) {
        artists[id]?.takeIf { fresh(it.at) }?.let { return@withContext it.value }
        val json = browse(id)
        val header = findObject(json, "musicImmersiveHeaderRenderer")
            ?: findObject(json, "musicVisualHeaderRenderer")
            ?: findObject(json, "musicHeaderRenderer")
            ?: throw IOException("Artist page without a header")
        val name = header.optJSONObject("title")?.let(::runsText)?.takeIf { it.isNotBlank() }
            ?: throw IOException("Artist page without a name")
        // The wide header picture is shown at full width: ask for twice the list size.
        val banner = header.optJSONObject("thumbnail")?.let { sizedThumbnailOf(it, thumbnailPx() * 2) }
            ?: header.optJSONObject("foregroundThumbnail")?.let { sizedThumbnailOf(it, thumbnailPx()) }

        var description = findObject(json, "musicDescriptionShelfRenderer")
            ?.optJSONObject("description")?.let(::runsText)
            ?: header.optJSONObject("description")?.let(::runsText)
        description = description?.trim()?.takeIf { it.isNotEmpty() }

        val topSongs = ArrayList<Track>()
        var songsPlaylist: String? = null
        val sections = ArrayList<ArtistSection>()

        val shelves = findObject(json, "sectionListRenderer")?.optJSONArray("contents") ?: JSONArray()
        for (i in 0 until shelves.length()) {
            val shelf = shelves.optJSONObject(i) ?: continue
            shelf.optJSONObject("musicShelfRenderer")?.let { songsShelf ->
                if (topSongs.isNotEmpty()) return@let
                val rows = songsShelf.optJSONArray("contents") ?: JSONArray()
                for (r in 0 until rows.length()) {
                    rows.optJSONObject(r)?.optJSONObject("musicResponsiveListItemRenderer")
                        ?.let { listItemTrack(it, fallbackArtist = name, fallbackArtistId = id) }
                        ?.let { topSongs += it.track }
                }
                songsPlaylist = playlistUrlFromBrowseId(
                    songsShelf.optJSONObject("title")?.let(::firstBrowseId)
                        ?: songsShelf.optJSONObject("bottomEndpoint")?.optJSONObject("browseEndpoint")
                            ?.optString("browseId"),
                )
            }
            shelf.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                val title = findObject(carousel.optJSONObject("header"), "musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")?.let(::runsText).orEmpty()
                val cards = carousel.optJSONArray("contents") ?: JSONArray()
                val items = ArrayList<BrowseItem>()
                for (c in 0 until cards.length()) {
                    cards.optJSONObject(c)?.optJSONObject("musicTwoRowItemRenderer")
                        ?.let { twoRowItem(it, name, id) }
                        ?.let { items += it }
                }
                if (title.isNotBlank() && items.isNotEmpty()) sections += ArtistSection(title, items)
            }
        }

        ArtistPage(id, name, banner, description, topSongs, songsPlaylist, sections)
            .also { artists[id] = Cached(it, now()) }
    }

    // ---------------------------------------------------------------------------------------
    // Albums
    // ---------------------------------------------------------------------------------------

    /**
     * [id] is an album browse id ("MPREb_...") or an album playlist id ("OLAK5uy_..."), as
     * album search results carry the playlist id only.
     */
    suspend fun album(id: String): AlbumPage = withContext(Dispatchers.IO) {
        val browseId = if (id.startsWith("MPRE")) id else albumBrowseId(id)
        albums[browseId]?.takeIf { fresh(it.at) }?.let { return@withContext it.value }
        val json = browse(browseId)

        val header = findObject(json, "musicResponsiveHeaderRenderer")
            ?: findObject(json, "musicDetailHeaderRenderer")
            ?: throw IOException("Album page without a header")
        val title = header.optJSONObject("title")?.let(::runsText)?.takeIf { it.isNotBlank() }
            ?: throw IOException("Album page without a title")
        val strapline = header.optJSONObject("straplineTextOne")
        val artist = strapline?.let(::runsText)?.takeIf { it.isNotBlank() }
            ?: header.optJSONObject("subtitle")?.optJSONArray("runs")?.let { runs ->
                // Older layout: "Album • Artist • 2013"
                (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
                    .firstOrNull { it.has("navigationEndpoint") }?.optString("text")
            }.orEmpty()
        val artistId = (strapline ?: header.optJSONObject("subtitle"))?.let(::firstBrowseId)
            ?.takeIf { it.startsWith("UC") }
        val subtitle = header.optJSONObject("subtitle")?.let(::runsText).orEmpty()
        val year = YEAR.findAll(subtitle).lastOrNull()?.value?.toIntOrNull()
        val details = header.optJSONObject("secondSubtitle")?.let(::runsText).orEmpty()
        val cover = header.optJSONObject("thumbnail")?.let(::thumbnailOf)

        val playlistId = json.optJSONObject("microformat")?.optJSONObject("microformatDataRenderer")
            ?.optString("urlCanonical")?.substringAfter("list=", "")?.takeIf { it.isNotEmpty() }
            ?: id.takeIf { !it.startsWith("MPRE") }
            ?: findString(json, "playlistId") { it.startsWith("OLAK") }

        // Tracks sit next to the header (secondaryContents) on current layouts.
        val trackRoot = json.optJSONObject("contents")?.let { findObject(it, "secondaryContents") } ?: json
        var tracks = albumTracks(trackRoot, artist, artistId, cover)
        if (tracks.isEmpty() && playlistId != null) {
            // Fallback: the album's playlist page lists the same tracks.
            tracks = albumTracks(browse("VL$playlistId"), artist, artistId, cover)
        }

        AlbumPage(
            browseId = browseId,
            title = title,
            artist = artist,
            artistId = artistId,
            year = year,
            subtitle = subtitle,
            details = details,
            thumbnailUrl = cover,
            playlistUrl = playlistId?.let { "https://music.youtube.com/playlist?list=$it" },
            tracks = tracks,
        ).also { albums[browseId] = Cached(it, now()) }
    }

    /** The album browse id behind an album playlist: its tracks link back to the album. */
    private fun albumBrowseId(playlistId: String): String {
        albumIds[playlistId]?.let { return it }
        val json = browse("VL$playlistId")
        val browseId = findString(json, "browseId") { it.startsWith("MPRE") }
            ?: throw IOException("Not an album")
        albumIds[playlistId] = browseId
        return browseId
    }

    private fun albumTracks(root: JSONObject, artist: String, artistId: String?, cover: String?): List<AlbumTrack> {
        val rows = ArrayList<JSONObject>()
        findAllObjects(root, "musicResponsiveListItemRenderer", rows)
        return rows.mapNotNull { row ->
            val parsed = listItemTrack(row, artist, artistId) ?: return@mapNotNull null
            val number = row.optJSONObject("index")?.let(::runsText)?.trim()?.toIntOrNull()
            // Album rows carry no artwork of their own: use the cover.
            val track = if (parsed.hasOwnThumbnail) parsed.track else parsed.track.copy(thumbnailUrl = cover)
            AlbumTrack(number, track)
        }.distinctBy { it.track.id }
    }

    // ---------------------------------------------------------------------------------------
    // Parsing helpers
    // ---------------------------------------------------------------------------------------

    private class ParsedTrack(val track: Track, val hasOwnThumbnail: Boolean)

    /** A song row (musicResponsiveListItemRenderer). */
    private fun listItemTrack(row: JSONObject, fallbackArtist: String, fallbackArtistId: String?): ParsedTrack? {
        val videoId = row.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotEmpty() }
            ?: findString(row, "videoId") { it.length == 11 }
            ?: return null
        val columns = row.optJSONArray("flexColumns") ?: return null
        fun column(i: Int): JSONObject? = columns.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val title = column(0)?.let(::runsText)?.takeIf { it.isNotBlank() } ?: return null

        val artistRuns = column(1)
        val artistText = artistRuns?.let(::runsText)?.substringBefore(" • ")?.trim().orEmpty()
        val artistId = artistRuns?.let(::firstBrowseId)?.takeIf { it.startsWith("UC") } ?: fallbackArtistId

        val duration = row.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            ?.let(::runsText)?.let(::parseDuration) ?: -1L
        val thumb = row.optJSONObject("thumbnail")?.let(::thumbnailOf)
        val isVideo = findString(row, "musicVideoType") { true }
            ?.let { it != "MUSIC_VIDEO_TYPE_ATV" } ?: false

        return ParsedTrack(
            Track(
                id = videoId,
                title = title,
                artist = artistText.ifEmpty { fallbackArtist },
                durationSec = duration,
                thumbnailUrl = thumb ?: Thumbnails.fallback(videoId),
                isVideo = isVideo,
                artistId = artistId,
            ),
            hasOwnThumbnail = thumb != null,
        )
    }

    /** A carousel card (musicTwoRowItemRenderer). */
    private fun twoRowItem(card: JSONObject, artistName: String, artistId: String): BrowseItem? {
        val title = card.optJSONObject("title")?.let(::runsText)?.takeIf { it.isNotBlank() } ?: return null
        val subtitle = card.optJSONObject("subtitle")?.let(::runsText).orEmpty()
        val thumb = card.optJSONObject("thumbnailRenderer")?.let(::thumbnailOf)
        val endpoint = card.optJSONObject("navigationEndpoint") ?: return null

        endpoint.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotEmpty() }?.let { videoId ->
            val isVideo = findString(endpoint, "musicVideoType") { true }?.let { it != "MUSIC_VIDEO_TYPE_ATV" } ?: true
            val track = Track(
                id = videoId,
                title = title,
                artist = artistName,
                thumbnailUrl = thumb ?: Thumbnails.fallback(videoId),
                isVideo = isVideo,
                artistId = artistId,
            )
            return BrowseItem.Song(track, title, subtitle, track.thumbnailUrl)
        }

        val browse = endpoint.optJSONObject("browseEndpoint") ?: return null
        val browseId = browse.optString("browseId").takeIf { it.isNotEmpty() } ?: return null
        val pageType = findString(browse, "pageType") { true }.orEmpty()
        return when {
            browseId.startsWith("MPRE") || pageType == "MUSIC_PAGE_TYPE_ALBUM" ->
                BrowseItem.Album(browseId, title, subtitle, thumb)
            browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST" ->
                BrowseItem.Artist(browseId, title, subtitle, thumb)
            browseId.startsWith("VL") || pageType == "MUSIC_PAGE_TYPE_PLAYLIST" ->
                playlistUrlFromBrowseId(browseId)?.let { BrowseItem.Playlist(it, title, subtitle, thumb) }
            else -> null
        }
    }

    private fun playlistUrlFromBrowseId(browseId: String?): String? =
        browseId?.takeIf { it.startsWith("VL") }?.let { "https://music.youtube.com/playlist?list=${it.removePrefix("VL")}" }

    /** Picks a sharp-enough thumbnail and asks Google's image server for exactly that size. */
    private fun thumbnailOf(holder: JSONObject): String? = sizedThumbnailOf(holder, thumbnailPx())

    private fun sizedThumbnailOf(holder: JSONObject, px: Int): String? {
        val list = findObject(holder, "thumbnail")?.optJSONArray("thumbnails")
            ?: holder.optJSONArray("thumbnails")
            ?: return null
        val last = list.optJSONObject(list.length() - 1)?.optString("url")?.takeIf { it.isNotEmpty() } ?: return null
        val url = if (last.startsWith("//")) "https:$last" else last
        return if (Thumbnails.isResizable(url)) Thumbnails.resize(url, px) else url
    }

    private fun runsText(textObject: JSONObject): String? {
        textObject.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = textObject.optJSONArray("runs") ?: return null
        return buildString {
            for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
    }

    private fun firstBrowseId(textObject: JSONObject): String? {
        val runs = textObject.optJSONArray("runs") ?: return null
        for (i in 0 until runs.length()) {
            val id = runs.optJSONObject(i)?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId")
            if (!id.isNullOrEmpty()) return id
        }
        return null
    }

    private fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':').map { it.toLongOrNull() ?: return null }
        return parts.fold(0L) { acc, n -> acc * 60 + n }
    }

    // ---------------------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------------------

    private fun browse(browseId: String): JSONObject {
        val locale = Locale.getDefault()
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", CLIENT_NAME)
                put("clientVersion", CLIENT_VERSION)
                // YouTube translates section titles ("Top songs", "Albums") into this language.
                put("hl", locale.language.ifEmpty { "en" })
                put("gl", locale.country.ifEmpty { "US" })
            }))
            put("browseId", browseId)
        }
        val request = Request.Builder()
            .url("$ORIGIN/youtubei/v1/browse?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music returned HTTP ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun fresh(at: Long) = now() - at < CACHE_MS

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_ID = "67"
        /** Keep in step with [AuthenticatedPlayer]'s WEB_REMIX version. */
        private const val CLIENT_VERSION = "1.20260121.03.00"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val CACHE_MS = 30 * 60 * 1000L
        private val JSON = "application/json".toMediaType()
        private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

        /** Depth-first search for the first object stored under [key]. */
        internal fun findObject(node: Any?, key: String): JSONObject? {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject(key)?.let { return it }
                    val names = node.keys()
                    while (names.hasNext()) findObject(node.opt(names.next()), key)?.let { return it }
                }
                is JSONArray -> for (i in 0 until node.length()) findObject(node.opt(i), key)?.let { return it }
            }
            return null
        }

        /** Every object stored under [key], without looking inside the ones found. */
        internal fun findAllObjects(node: Any?, key: String, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> {
                    val names = node.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        val value = node.opt(name)
                        if (name == key && value is JSONObject) out += value else findAllObjects(value, key, out)
                    }
                }
                is JSONArray -> for (i in 0 until node.length()) findAllObjects(node.opt(i), key, out)
            }
        }

        /** First string value under [key] that satisfies [accept], depth-first. */
        internal fun findString(node: Any?, key: String, accept: (String) -> Boolean): String? {
            when (node) {
                is JSONObject -> {
                    (node.opt(key) as? String)?.takeIf(accept)?.let { return it }
                    val names = node.keys()
                    while (names.hasNext()) findString(node.opt(names.next()), key, accept)?.let { return it }
                }
                is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key, accept)?.let { return it }
            }
            return null
        }
    }
}
