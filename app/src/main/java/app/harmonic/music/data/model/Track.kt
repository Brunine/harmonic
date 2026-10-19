package app.harmonic.music.data.model

import androidx.compose.runtime.Immutable

/** A playable YouTube / YouTube Music item. [id] is the YouTube video id. */
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
)

/** An album or playlist found online. */
@Immutable
data class OnlineCollection(
    val url: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val isAlbum: Boolean,
)

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
