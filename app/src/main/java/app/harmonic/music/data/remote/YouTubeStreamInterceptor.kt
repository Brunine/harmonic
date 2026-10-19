package app.harmonic.music.data.remote

import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Makes googlevideo.com audio requests look like the official clients, the same way
 * NewPipe's player does:
 *  - byte ranges go in a `range=` URL parameter instead of a Range header,
 *  - a request counter `rn=` is appended,
 *  - the request is a POST with a tiny protobuf body,
 *  - the User-Agent matches the client the URL was issued to.
 *
 * Without this YouTube throttles or rejects the requests. The rewritten 200 response is
 * reported back as 206 so ExoPlayer does not try to skip bytes a second time.
 */
class YouTubeStreamInterceptor : Interceptor {
    private val requestNumber = AtomicLong(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        if (!url.host.endsWith("googlevideo.com") || !url.encodedPath.startsWith("/videoplayback")) {
            return chain.proceed(original)
        }

        val rangeHeader = original.header("Range")
        val range = rangeHeader?.removePrefix("bytes=")?.takeIf { it.isNotBlank() && it != "0-" }

        val newUrl = url.newBuilder().apply {
            if (url.queryParameter("rn") == null) {
                addQueryParameter("rn", requestNumber.incrementAndGet().toString())
            }
            if (range != null) setQueryParameter("range", range)
        }.build()

        val urlString = newUrl.toString()
        val userAgent = when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(urlString) ->
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            urlString.contains("c=TVHTML5") -> AuthenticatedPlayer.TV_USER_AGENT
            else -> BROWSER_USER_AGENT
        }

        val request = original.newBuilder()
            .url(newUrl)
            .removeHeader("Range")
            .header("User-Agent", userAgent)
            .apply {
                if (YoutubeParsingHelper.isWebStreamingUrl(urlString)) {
                    header("Origin", "https://www.youtube.com")
                    header("Referer", "https://www.youtube.com/")
                }
            }
            .post(POST_BODY.toRequestBody(null))
            .build()

        val response = chain.proceed(request)
        if (range == null || response.code != 200) return response

        val start = range.substringBefore('-').toLongOrNull() ?: return response
        val length = response.body?.contentLength() ?: -1L
        return response.newBuilder()
            .code(206)
            .apply {
                if (length > 0) header("Content-Range", "bytes $start-${start + length - 1}/*")
            }
            .build()
    }

    private companion object {
        val POST_BODY = byteArrayOf(0x78, 0)
    }
}
