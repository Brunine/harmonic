package app.harmonic.music.data.remote

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException

/**
 * Signed-in fallback used only for age-restricted tracks.
 *
 * NewPipeExtractor's anonymous mobile clients cannot carry a web login, so for those tracks we
 * ask the InnerTube player endpoint directly with the user's cookies. Signature and throttling
 * parameters are deciphered with NewPipeExtractor's JavaScript player manager.
 *
 * This is best-effort: YouTube changes which clients accept cookies from time to time. The
 * client list below is tried in order; update the versions if requests start failing.
 */
class AuthenticatedPlayer(
    private val client: OkHttpClient,
    private val account: AccountManager,
) {
    data class AudioFormat(val url: String, val itag: Int, val mimeType: String, val bitrate: Int)

    /** Loudness from the last signed-in player response per video, for normalization. */
    private val loudness = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun loudnessDb(videoId: String): Float? = loudness[videoId]

    private data class ClientSpec(
        val name: String,
        val nameId: String,
        val version: String,
        val userAgent: String,
        val origin: String,
    )

    private val clients = listOf(
        ClientSpec("TVHTML5", "7", "7.20260114.12.00", TV_USER_AGENT, "https://www.youtube.com"),
        ClientSpec("WEB_REMIX", "67", "1.20260121.03.00", BROWSER_USER_AGENT, "https://music.youtube.com"),
    )

    fun audioFormats(videoId: String): List<AudioFormat> {
        if (!account.isLoggedIn) throw IOException("Sign in to play age-restricted tracks")
        var lastError: Exception? = null
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        for (spec in clients) {
            try {
                val formats = request(spec, videoId, sts)
                if (formats.isNotEmpty()) return formats
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Signed-in playback failed: ${lastError?.message ?: "no audio formats"}", lastError)
    }

    private fun request(spec: ClientSpec, videoId: String, sts: Int?): List<AudioFormat> {
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", spec.name)
                put("clientVersion", spec.version)
                put("hl", "en")
                put("gl", "US")
            }))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (sts != null) {
                put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().apply {
                    put("signatureTimestamp", sts)
                    put("html5Preference", "HTML5_PREF_WANTS")
                }))
            }
        }

        val auth = account.authHeaders(spec.origin) ?: throw IOException("Not signed in")
        val request = Request.Builder()
            .url("${spec.origin}/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", spec.userAgent)
            .header("X-YouTube-Client-Name", spec.nameId)
            .header("X-YouTube-Client-Version", spec.version)
            .apply { auth.forEach { (k, v) -> header(k, v) } }
            .build()

        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${spec.name}")
            JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        }

        val status = json.optJSONObject("playabilityStatus")
        if (status != null && status.optString("status") != "OK") {
            throw IOException("${spec.name}: ${status.optString("status")} ${status.optString("reason")}")
        }

        json.optJSONObject("playerConfig")?.optJSONObject("audioConfig")?.let { audio ->
            if (audio.has("loudnessDb")) loudness[videoId] = audio.optDouble("loudnessDb").toFloat()
        }

        val formats = json.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
            ?: return emptyList()
        val result = ArrayList<AudioFormat>()
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            val url = resolveUrl(videoId, f) ?: continue
            result += AudioFormat(url, f.optInt("itag"), mime.substringBefore(';'), f.optInt("bitrate"))
        }
        return result
    }

    private fun resolveUrl(videoId: String, format: JSONObject): String? {
        var url = format.optString("url").takeIf { it.isNotEmpty() }
        if (url == null) {
            val cipher = format.optString("signatureCipher").ifEmpty { format.optString("cipher") }
            if (cipher.isEmpty()) return null
            val params = Uri.parse("https://x/?$cipher")
            val base = params.getQueryParameter("url") ?: return null
            val s = params.getQueryParameter("s") ?: return null
            val sp = params.getQueryParameter("sp") ?: "signature"
            val sig = YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s)
            url = "$base&$sp=${Uri.encode(sig)}"
        }
        return YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
    }

    companion object {
        const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private val JSON = "application/json".toMediaType()
    }
}
