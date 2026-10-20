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
import java.util.Locale

/**
 * Apple Music catalog through Apple's public iTunes Search API (no account needed). Apple Music
 * audio is DRM-protected, so tracks play from their YouTube Music match.
 */
class AppleMusicClient(private val client: OkHttpClient, private val thumbnailPx: () -> Int) {

    private val country: String
        get() = Locale.getDefault().country.lowercase().ifEmpty { "us" }

    private fun get(path: String, params: Map<String, String>): JSONArray {
        val url = "https://itunes.apple.com/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("country", country)
        }.build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Apple Music HTTP ${r.code}")
            JSONObject(r.body?.string() ?: "{}").optJSONArray("results") ?: JSONArray()
        }
    }

    fun searchSongs(query: String): SearchPage {
        val results = get("search", mapOf("term" to query, "media" to "music", "entity" to "song", "limit" to "50"))
        val items = (0 until results.length()).mapNotNull { track(results.getJSONObject(it)) }
            .map { SearchResult.Song(it) }
        return SearchPage(items, null)
    }

    fun searchAlbums(query: String): SearchPage {
        val results = get("search", mapOf("term" to query, "media" to "music", "entity" to "album", "limit" to "50"))
        val items = (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            SearchResult.Collection(
                OnlineCollection(
                    url = "https://music.apple.com/album/${o.optLong("collectionId")}",
                    title = o.optString("collectionName"),
                    subtitle = o.optString("artistName"),
                    thumbnailUrl = art(o),
                    isAlbum = true,
                    source = MusicSource.APPLE,
                ),
            )
        }
        return SearchPage(items, null)
    }

    fun trackById(id: String): Track {
        val results = get("lookup", mapOf("id" to id))
        return (0 until results.length()).firstNotNullOfOrNull { track(results.getJSONObject(it)) }
            ?: throw IOException("Apple Music track not found")
    }

    fun album(albumId: String, url: String): OnlinePlaylist {
        val results = get("lookup", mapOf("id" to albumId, "entity" to "song", "limit" to "200"))
        var header: JSONObject? = null
        val tracks = ArrayList<Track>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            if (o.optString("wrapperType") == "collection") header = o else track(o)?.let { tracks += it }
        }
        return OnlinePlaylist(
            url = url,
            title = header?.optString("collectionName").orEmpty(),
            author = header?.optString("artistName").orEmpty(),
            thumbnailUrl = header?.let { art(it) },
            tracks = tracks,
            next = null,
        )
    }

    private fun track(o: JSONObject): Track? {
        if (o.optString("wrapperType") != "track" || o.optString("kind") != "song") return null
        val id = o.optLong("trackId")
        if (id <= 0) return null
        return Track(
            id = "am:$id",
            title = o.optString("trackName"),
            artist = o.optString("artistName"),
            durationSec = o.optLong("trackTimeMillis", -1000) / 1000,
            thumbnailUrl = art(o),
            isVideo = false,
        )
    }

    private fun art(o: JSONObject): String? {
        val px = thumbnailPx()
        return o.optString("artworkUrl100").takeIf { it.isNotEmpty() }
            ?.replace("100x100bb", "${px}x${px}bb")
    }

    companion object {
        /**
         * music.apple.com/{cc}/album/{name}/{albumId}?i={trackId} -> ("song", trackId)
         * music.apple.com/{cc}/album/{name}/{albumId}             -> ("album", albumId)
         * music.apple.com/{cc}/song/{name}/{trackId}              -> ("song", trackId)
         */
        fun parse(url: String): Pair<String, String>? {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            if (host != "music.apple.com" && host != "itunes.apple.com") return null
            uri.getQueryParameter("i")?.takeIf { it.all(Char::isDigit) }?.let { return "song" to it }
            val segments = uri.pathSegments
            val last = segments.lastOrNull()?.removePrefix("id")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?: return null
            return when {
                "song" in segments -> "song" to last
                "album" in segments -> "album" to last
                else -> null
            }
        }
    }
}
