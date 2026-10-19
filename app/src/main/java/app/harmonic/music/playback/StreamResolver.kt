package app.harmonic.music.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import app.harmonic.music.data.db.FormatDao
import app.harmonic.music.data.db.FormatEntity
import app.harmonic.music.data.prefs.AudioQuality
import app.harmonic.music.data.prefs.SettingsRepository
import app.harmonic.music.data.remote.AccountManager
import app.harmonic.music.data.remote.AuthenticatedPlayer
import app.harmonic.music.data.remote.YouTubeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Turns a video id into a direct audio-only URL, choosing the stream by network type and the
 * user's quality settings. Results are kept in memory until shortly before YouTube's expiry.
 *
 * This is only consulted on a cache miss: songs that are downloaded or fully in the player
 * cache never trigger any extraction request.
 */
@OptIn(UnstableApi::class)
class StreamResolver(
    context: Context,
    private val youtube: YouTubeRepository,
    private val authPlayer: AuthenticatedPlayer,
    private val account: AccountManager,
    private val formatDao: FormatDao,
    private val settings: SettingsRepository,
    private val playerCache: () -> Cache,
    private val downloadCache: () -> Cache,
    private val scope: CoroutineScope,
) {
    data class Resolved(val url: String, val itag: Int, val expiresAt: Long)

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val resolved = ConcurrentHashMap<String, Resolved>()

    private data class Candidate(val url: String, val itag: Int, val mimeType: String?, val bitrate: Int)

    fun isMetered(): Boolean = connectivity?.isActiveNetworkMetered ?: true

    fun resolve(videoId: String, forDownload: Boolean): Resolved {
        val now = System.currentTimeMillis()
        val cacheKey = if (forDownload) "$videoId:dl" else videoId
        resolved[cacheKey]?.let { if (it.expiresAt > now) return it }

        val s = settings.current
        val quality = when {
            forDownload -> s.qualityDownload
            isMetered() -> s.qualityMobile
            else -> s.qualityWifi
        }
        // If part of this track is already in the player cache, keep the same format so the
        // cached bytes and the new bytes belong to the same file.
        val preferredItag = if (!forDownload && hasPartialCache(videoId)) {
            formatDao.get(videoId)?.itag
        } else {
            null
        }

        val candidates = try {
            anonymousCandidates(videoId)
        } catch (e: AgeRestrictedContentException) {
            signedInCandidates(videoId, e)
        } catch (e: SignInConfirmNotBotException) {
            signedInCandidates(videoId, e)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Could not load stream", e)
        }

        val chosen = choose(candidates, quality, preferredItag)
            ?: throw IOException("No playable audio stream for $videoId")
        if (preferredItag != null && chosen.itag != preferredItag) {
            // The cached format is no longer offered: drop the stale partial copy.
            runCatching { playerCache().removeResource(videoId) }
        }

        formatDao.upsert(FormatEntity(videoId, chosen.itag, chosen.mimeType, chosen.bitrate))
        val result = Resolved(chosen.url, chosen.itag, expiryOf(chosen.url))
        resolved[cacheKey] = result
        return result
    }

    /** Resolve the next track ahead of time so skipping starts instantly. Costs only metadata. */
    fun prefetch(videoId: String) {
        if (resolved[videoId]?.let { it.expiresAt > System.currentTimeMillis() } == true) return
        scope.launch(Dispatchers.IO) {
            // Cached or downloaded audio needs no URL at all; skip the metadata request.
            if (hasPartialCache(videoId)) return@launch
            runCatching { resolve(videoId, forDownload = false) }
        }
    }

    fun invalidate(videoId: String) {
        resolved.remove(videoId)
        resolved.remove("$videoId:dl")
        youtube.invalidate(videoId)
    }

    private fun anonymousCandidates(videoId: String): List<Candidate> =
        youtube.streamInfoBlocking(videoId).audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .let { streams ->
                // Skip dubbed / descriptive tracks when the original audio exists.
                streams.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                    .ifEmpty { streams }
            }
            .map { Candidate(it.content, it.itag, it.format?.mimeType, bitrateOf(it)) }

    private fun signedInCandidates(videoId: String, cause: Exception): List<Candidate> {
        if (!account.isLoggedIn) {
            throw IOException("This track is age-restricted. Sign in from Settings to play it.", cause)
        }
        return authPlayer.audioFormats(videoId).map { Candidate(it.url, it.itag, it.mimeType, it.bitrate) }
    }

    private fun choose(candidates: List<Candidate>, quality: AudioQuality, preferredItag: Int?): Candidate? {
        if (candidates.isEmpty()) return null
        preferredItag?.let { itag -> candidates.firstOrNull { it.itag == itag }?.let { return it } }
        val sorted = candidates.sortedWith(
            // Lowest bitrate first; at equal bitrate prefer Opus (better quality per byte).
            compareBy<Candidate> { it.bitrate }.thenBy { if (it.mimeType?.contains("webm") == true) 0 else 1 },
        )
        return when (quality) {
            AudioQuality.LOW -> sorted.first()
            AudioQuality.NORMAL -> sorted.minByOrNull { abs(it.bitrate - 128_000) }
            AudioQuality.HIGH -> sorted.last()
        }
    }

    private fun bitrateOf(stream: AudioStream): Int =
        if (stream.bitrate > 0) stream.bitrate else stream.averageBitrate * 1000

    private fun hasPartialCache(videoId: String): Boolean = runCatching {
        playerCache().getCachedSpans(videoId).isNotEmpty() ||
            downloadCache().getCachedSpans(videoId).isNotEmpty()
    }.getOrDefault(false)

    private fun expiryOf(url: String): Long {
        val expireSec = runCatching { Uri.parse(url).getQueryParameter("expire")?.toLong() }.getOrNull()
        val fallback = System.currentTimeMillis() + 3 * 60 * 60 * 1000L
        // Refresh 10 minutes early to avoid starting a download with an about-to-expire URL.
        return expireSec?.let { it * 1000 - 10 * 60 * 1000 } ?: fallback
    }
}
