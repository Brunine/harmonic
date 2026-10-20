package app.harmonic.music.data.remote

import app.harmonic.music.data.db.MatchDao
import app.harmonic.music.data.db.MatchEntity
import app.harmonic.music.data.db.SongDao
import app.harmonic.music.data.model.Track
import java.io.IOException
import java.text.Normalizer
import kotlin.math.abs

/**
 * Finds the YouTube Music track that plays a Deezer / Apple Music track. Matches are stored,
 * so each track is searched once; downloads and the cache then work exactly like YouTube.
 */
class TrackMatcher(
    private val youtube: YouTubeRepository,
    private val songDao: SongDao,
    private val matchDao: MatchDao,
) {
    /** Blocking. Returns the YouTube video id for a "dz:" / "am:" track. */
    fun youtubeIdFor(id: String): String {
        matchDao.get(id)?.let { return it.youtubeId }
        val song = songDao.getBlocking(id)?.toTrack()
            ?: throw IOException("Unknown track $id")
        val best = bestMatch(song) ?: throw IOException("No YouTube Music match for “${song.title}”")
        matchDao.upsert(MatchEntity(id, best.id, System.currentTimeMillis()))
        return best.id
    }

    /** Forget a match so the next play searches again (e.g. after a wrong match). */
    fun forget(id: String) = matchDao.delete(id)

    private fun bestMatch(song: Track): Track? {
        val mainArtist = song.artist.split(",", "&", " feat", " ft.", " x ").first().trim()
        val candidates = youtube.searchSongsBlocking("$mainArtist ${song.title}")
        if (candidates.isEmpty()) return null
        val wantTitle = normalize(song.title)
        val wantArtist = normalize(mainArtist)
        return candidates.take(10).minByOrNull { c ->
            val durationPenalty = if (song.durationSec > 0 && c.durationSec > 0) {
                abs(c.durationSec - song.durationSec).coerceAtMost(60).toInt()
            } else {
                15
            }
            val title = normalize(c.title)
            val titlePenalty = if (title.contains(wantTitle) || wantTitle.contains(title)) 0 else 25
            val artistPenalty = if (normalize(c.artist).contains(wantArtist)) 0 else 15
            // Prefer official audio over live / cover / karaoke versions.
            val versionPenalty = if (unwanted.any { it in c.title.lowercase() && it !in song.title.lowercase() }) 30 else 0
            durationPenalty + titlePenalty + artistPenalty + versionPenalty
        }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("[^a-z0-9]+"), "")

    private companion object {
        val unwanted = listOf("live", "cover", "karaoke", "instrumental", "remix", "sped up", "slowed")
    }
}
