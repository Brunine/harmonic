package app.harmonic.music.data.remote

import android.net.Uri
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.model.OnlineCollection
import app.harmonic.music.data.model.Track
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Deezer's public catalog API (no account needed). Used for search and playlist/album import
 * only: Deezer audio is DRM-protected, so tracks play from their YouTube Music match.
 */
class DeezerClient(private val client: OkHttpClient, private val thumbnailPx: () -> Int) {

    enum class Kind(val path: String) { TRACK("track"), ALBUM("album"), PLAYLIST("playlist") }

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = "$API/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val json = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Deezer HTTP ${r.code}")
            JSONObject(r.body?.string() ?: "{}")
        }
        json.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
        return json
    }

    fun search(query: String, kind: Kind, index: Int): SearchPage {
        val path = if (kind == Kind.TRACK) "search" else "search/${kind.path}"
        val json = get(path, mapOf("q" to query, "limit" to "$PAGE", "index" to "$index"))
        val data = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { i ->
            val o = data.getJSONObject(i)
            when (kind) {
                Kind.TRACK -> track(o)?.let { SearchResult.Song(it) }
                Kind.ALBUM -> SearchResult.Collection(
                    OnlineCollection(
                        url = "https://www.deezer.com/album/${o.optLong("id")}",
                        title = o.optString("title"),
                        subtitle = o.optJSONObject("artist")?.optString("name").orEmpty(),
                        thumbnailUrl = cover(o),
                        isAlbum = true,
                        source = MusicSource.DEEZER,
                    ),
                )
                Kind.PLAYLIST -> SearchResult.Collection(
                    OnlineCollection(
                        url = "https://www.deezer.com/playlist/${o.optLong("id")}",
                        title = o.optString("title"),
                        subtitle = o.optJSONObject("user")?.optString("name").orEmpty(),
                        thumbnailUrl = picture(o),
                        isAlbum = false,
                        source = MusicSource.DEEZER,
                    ),
                )
            }
        }
        val next = if (json.has("next") && items.isNotEmpty()) index + PAGE else null
        return SearchPage(items, next)
    }

    fun trackById(id: String): Track = track(get("track/$id")) ?: throw IOException("Deezer track not found")

    /** Album or playlist: header plus the first page of tracks. [next] is the next index. */
    fun collection(kind: Kind, id: String, url: String): OnlinePlaylist {
        val header = get("${kind.path}/$id")
        val (tracks, next) = collectionTracks(kind, id, 0, header)
        return OnlinePlaylist(
            url = url,
            title = header.optString("title"),
            author = header.optJSONObject("artist")?.optString("name")
                ?: header.optJSONObject("creator")?.optString("name").orEmpty(),
            thumbnailUrl = if (kind == Kind.ALBUM) cover(header) else picture(header),
            tracks = tracks,
            next = next,
        )
    }

    fun collectionTracks(kind: Kind, id: String, index: Int, album: JSONObject? = null): Pair<List<Track>, Int?> {
        val json = get("${kind.path}/$id/tracks", mapOf("limit" to "100", "index" to "$index"))
        val data = json.optJSONArray("data") ?: JSONArray()
        // Album track lists omit the album object, so the album cover is filled in from the header.
        val albumCover = album?.let { cover(it) }
        val tracks = (0 until data.length()).mapNotNull { i -> track(data.getJSONObject(i), albumCover) }
        val next = if (json.has("next") && tracks.isNotEmpty()) index + tracks.size else null
        return tracks to next
    }

    private fun track(o: JSONObject, fallbackCover: String? = null): Track? {
        val id = o.optLong("id")
        if (id <= 0 || o.optBoolean("readable", true).not()) return null
        return Track(
            id = "dz:$id",
            title = o.optString("title_short").ifEmpty { o.optString("title") },
            artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
            durationSec = o.optLong("duration", -1),
            thumbnailUrl = o.optJSONObject("album")?.let { cover(it) } ?: fallbackCover,
            isVideo = false,
        )
    }

    private fun cover(o: JSONObject): String? =
        (if (thumbnailPx() <= 300) o.optString("cover_medium") else o.optString("cover_big"))
            .takeIf { it.isNotEmpty() }

    private fun picture(o: JSONObject): String? =
        (if (thumbnailPx() <= 300) o.optString("picture_medium") else o.optString("picture_big"))
            .takeIf { it.isNotEmpty() }

    companion object {
        private const val API = "https://api.deezer.com"
        private const val PAGE = 25

        /** Parses deezer.com/{lang}/track|album|playlist/{id} links. */
        fun parse(url: String): Pair<Kind, String>? {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            if (!host.endsWith("deezer.com")) return null
            val segments = uri.pathSegments
            for (i in 0 until segments.size - 1) {
                val kind = Kind.entries.firstOrNull { it.path == segments[i] } ?: continue
                val id = segments[i + 1].takeWhile { it.isDigit() }
                if (id.isNotEmpty()) return kind to id
            }
            return null
        }

        fun isShortLink(url: String): Boolean {
            val host = Uri.parse(url).host ?: return false
            return host == "link.deezer.com" || host == "deezer.page.link"
        }
    }
}
