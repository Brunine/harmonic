package app.harmonic.music.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest

/**
 * Non-music parts of music videos (intros, outros, sponsor reads) from the community
 * SponsorBlock database. Uses the k-anonymity endpoint, so only the first 4 hex chars of a
 * hash of the video id are sent.
 */
class SponsorBlockClient(private val client: OkHttpClient) {
    data class Segment(val startMs: Long, val endMs: Long)

    suspend fun segments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        val prefix = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(4)
        val categories = CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
        val url = "https://sponsor.ajay.app/api/skipSegments/$prefix".toHttpUrl().newBuilder()
            .addQueryParameter("categories", categories)
            .build()
        val request = Request.Builder().url(url).build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<Segment>()
                val videos = JSONArray(response.body?.string() ?: "[]")
                val result = ArrayList<Segment>()
                for (i in 0 until videos.length()) {
                    val video = videos.getJSONObject(i)
                    if (video.optString("videoID") != videoId) continue
                    val segments = video.optJSONArray("segments") ?: continue
                    for (j in 0 until segments.length()) {
                        val seg = segments.getJSONObject(j)
                        if (seg.optString("actionType", "skip") != "skip") continue
                        val range = seg.getJSONArray("segment")
                        val start = (range.getDouble(0) * 1000).toLong()
                        val end = (range.getDouble(1) * 1000).toLong()
                        if (end - start >= 1000) result += Segment(start, end)
                    }
                }
                result.sortedBy { it.startMs }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        val CATEGORIES = listOf("music_offtopic", "sponsor", "selfpromo", "interaction")
    }
}
