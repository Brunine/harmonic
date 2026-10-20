package app.harmonic.music.data.remote

import app.harmonic.music.BuildConfig
import app.harmonic.music.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.abs

/** One timed line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

sealed interface Lyrics {
    /** Lines with timestamps, sorted by time: the view follows the song. */
    data class Synced(val lines: List<LyricLine>) : Lyrics
    data class Plain(val text: String) : Lyrics
    data object Instrumental : Lyrics
    data object NotFound : Lyrics
}

/**
 * Lyrics from LRCLIB (lrclib.net): free, open, no account. Only asked when the lyrics view is
 * opened, and kept on the phone afterwards (misses too, for a week) so a song is looked up
 * once.
 */
class LyricsClient(private val client: OkHttpClient, private val cacheDir: File) {

    suspend fun lyrics(track: Track): Lyrics = withContext(Dispatchers.IO) {
        readCache(track.id)?.let { return@withContext it }
        val (title, artist) = cleanQuery(track)
        // Network errors propagate (and are not cached); "nothing found" is remembered.
        val found = search(title, artist, track.durationSec)
        val result = found?.let(::toLyrics) ?: Lyrics.NotFound
        writeCache(track.id, found)
        result
    }

    /** Searches by title and artist, then by free text; picks the closest length, synced first. */
    private fun search(title: String, artist: String, durationSec: Long): JSONObject? {
        val strict = request("track_name" to title, "artist_name" to artist)
        best(strict, durationSec)?.let { return it }
        val loose = request("q" to "$artist $title".trim())
        return best(loose, durationSec)
    }

    private fun best(results: JSONArray, durationSec: Long): JSONObject? {
        val candidates = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { it.hasText() || it.optBoolean("instrumental") }
        if (candidates.isEmpty()) return null
        val fitting = if (durationSec > 0) {
            candidates.filter { abs(it.optDouble("duration", -1.0) - durationSec) <= MAX_LENGTH_DIFF_SEC }
        } else {
            candidates
        }
        if (fitting.isEmpty()) return null
        return fitting.firstOrNull { !it.optString("syncedLyrics").isNullOrBlankJson() } ?: fitting.first()
    }

    private fun request(vararg params: Pair<String, String>): JSONArray {
        val url = "$BASE/api/search".toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val request = Request.Builder()
            .url(url)
            // LRCLIB asks apps to identify themselves.
            .header("User-Agent", "Harmonic ${BuildConfig.VERSION_NAME} (https://github.com/Brunine/harmonic)")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("LRCLIB returned HTTP ${response.code}")
            JSONArray(response.body?.string() ?: "[]")
        }
    }

    private fun toLyrics(entry: JSONObject): Lyrics {
        if (entry.optBoolean("instrumental")) return Lyrics.Instrumental
        val synced = entry.optString("syncedLyrics").takeUnless { it.isNullOrBlankJson() }
            ?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        if (synced != null) return Lyrics.Synced(synced)
        val plain = entry.optString("plainLyrics").takeUnless { it.isNullOrBlankJson() }
        return plain?.let { Lyrics.Plain(it.trim()) } ?: Lyrics.NotFound
    }

    private fun JSONObject.hasText() =
        !optString("syncedLyrics").isNullOrBlankJson() || !optString("plainLyrics").isNullOrBlankJson()

    /** org.json turns JSON null into the string "null". */
    private fun String?.isNullOrBlankJson() = this == null || isBlank() || this == "null"

    // ---------------------------------------------------------------------------------------
    // Cache: one small JSON file per song
    // ---------------------------------------------------------------------------------------

    private val dir get() = File(cacheDir, "lyrics").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    private fun readCache(id: String): Lyrics? = runCatching {
        val file = fileFor(id)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        if (json.optBoolean("notFound")) {
            // Lyrics get added to LRCLIB over time: look again after a week.
            if (System.currentTimeMillis() - file.lastModified() > MISS_TTL_MS) return null
            return Lyrics.NotFound
        }
        toLyrics(json)
    }.getOrNull()

    private fun writeCache(id: String, entry: JSONObject?) {
        runCatching {
            val json = entry?.let {
                JSONObject()
                    .put("instrumental", it.optBoolean("instrumental"))
                    .put("syncedLyrics", it.opt("syncedLyrics") ?: JSONObject.NULL)
                    .put("plainLyrics", it.opt("plainLyrics") ?: JSONObject.NULL)
            } ?: JSONObject().put("notFound", true)
            fileFor(id).writeText(json.toString())
        }
    }

    companion object {
        private const val BASE = "https://lrclib.net"
        private const val MAX_LENGTH_DIFF_SEC = 8.0
        private const val MISS_TTL_MS = 7 * 24 * 60 * 60 * 1000L

        private val TIMESTAMP = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

        /** "[01:02.34]text" lines; a line may carry several timestamps. */
        fun parseLrc(lrc: String): List<LyricLine> {
            val lines = ArrayList<LyricLine>()
            lrc.lineSequence().forEach { raw ->
                val stamps = TIMESTAMP.findAll(raw).toList()
                if (stamps.isEmpty()) return@forEach
                val text = raw.substring(stamps.last().range.last + 1).trim()
                stamps.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    lines += LyricLine(min * 60_000 + sec * 1000 + ms, text)
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        private val NOISE = Regex(
            "\\s*[(\\[][^)\\]]*(official|video|lyric|audio|visuali[sz]er|clipe|hd|4k|mv|remaster|letra)[^)\\]]*[)\\]]",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Song title and artist to search with. Music video titles often look like
         * "Artist - Song (Official Video)" on a channel named after the artist or label.
         */
        fun cleanQuery(track: Track): Pair<String, String> {
            var title = track.title.replace(NOISE, "").trim()
            var artist = track.artist.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
            val dash = title.indexOf(" - ").takeIf { it > 0 } ?: title.indexOf(" – ").takeIf { it > 0 }
            if (track.isVideo && dash != null) {
                val left = title.substring(0, dash).trim()
                val right = title.substring(dash + 3).trim()
                if (right.isNotEmpty()) {
                    artist = left
                    title = right
                }
            }
            title = title.substringBefore(" feat.").substringBefore(" ft.").trim().removeSurrounding("\"")
            return title to artist
        }
    }
}
