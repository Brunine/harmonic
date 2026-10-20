package app.harmonic.music.data.model

import androidx.compose.runtime.Immutable

/**
 * A playable item.
 *
 * [id] identifies both the track and its source:
 *  - YouTube / YouTube Music: the 11-character video id
 *  - SoundCloud: "sc:<user>/<track>" (the permalink path)
 *  - Deezer: "dz:<track id>" (audio comes from the matching YouTube Music track)
 *  - Apple Music: "am:<track id>" (audio comes from the matching YouTube Music track)
 */
@Immutable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    /** Duration in seconds, or -1 when unknown. */
    val durationSec: Long = -1,
    val thumbnailUrl: String? = null,
    /** True for music videos / regular videos; false for YouTube Music "songs". */
    val isVideo: Boolean = false,
    /** YouTube Music artist (channel) id when known, e.g. "UC..."; opens the artist page. */
    val artistId: String? = null,
)

enum class MusicSource(val label: String, val shortLabel: String, val prefix: String) {
    YOUTUBE("YouTube Music", "YouTube", ""),
    SOUNDCLOUD("SoundCloud", "SoundCloud", "sc:"),
    DEEZER("Deezer", "Deezer", "dz:"),
    APPLE("Apple Music", "Apple", "am:"),
    ;

    /** Catalog-only sources: their audio is DRM-protected, so playback uses a YouTube match. */
    val playsViaYouTube: Boolean get() = this == DEEZER || this == APPLE

    companion object {
        fun of(id: String): MusicSource = when {
            id.startsWith("sc:") -> SOUNDCLOUD
            id.startsWith("dz:") -> DEEZER
            id.startsWith("am:") -> APPLE
            else -> YOUTUBE
        }
    }
}

val Track.source: MusicSource get() = MusicSource.of(id)

val Track.canDownload: Boolean get() = source != MusicSource.SOUNDCLOUD

fun soundCloudUrl(id: String) = "https://soundcloud.com/" + id.removePrefix("sc:")

fun Track.shareUrl(): String = when (source) {
    MusicSource.YOUTUBE -> "https://music.youtube.com/watch?v=$id"
    MusicSource.SOUNDCLOUD -> soundCloudUrl(id)
    MusicSource.DEEZER -> "https://www.deezer.com/track/${id.removePrefix("dz:")}"
    MusicSource.APPLE -> "https://music.apple.com/song/${id.removePrefix("am:")}"
}

/** An album or playlist found online. */
@Immutable
data class OnlineCollection(
    val url: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val isAlbum: Boolean,
    val source: MusicSource = MusicSource.YOUTUBE,
)

/** An artist found online (YouTube Music). [id] is the channel id, "UC...". */
@Immutable
data class ArtistRef(
    val id: String,
    val name: String,
    val thumbnailUrl: String?,
)

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
