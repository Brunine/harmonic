package app.harmonic.music.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.model.soundCloudUrl
import app.harmonic.music.data.model.source

private const val EXTRA_THUMB = "harmonic.thumb"
private const val EXTRA_VIDEO = "harmonic.video"

/** Browse ids for Android Auto children are "<parentId>|<videoId>"; queue items are the bare id. */
fun MediaItem.trackId(): String = mediaId.substringAfterLast('|')

fun watchUri(videoId: String): Uri = Uri.parse("https://music.youtube.com/watch?v=" + Uri.encode(videoId))

/** Reverse of the placeholder URIs below, for data specs that carry no cache key. */
fun trackIdFromPlaceholder(uri: Uri): String? = when (uri.host) {
    "music.youtube.com" -> uri.getQueryParameter("v")
    "soundcloud.com" -> uri.path?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "sc:$it" }
    else -> null
}

/**
 * Builds a playable item. The URI is a placeholder: the real stream URL is resolved lazily by
 * the data source on a cache miss, and the custom cache key (the track id) ties the download
 * cache, the player cache and the resolver together.
 *
 * SoundCloud items are HLS playlists, which are streamed without the byte cache.
 */
fun Track.toMediaItem(context: Context, mediaId: String = id): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(toMetadata(context))
    if (source == MusicSource.SOUNDCLOUD) {
        builder.setUri(Uri.parse(soundCloudUrl(id))).setMimeType(MimeTypes.APPLICATION_M3U8)
    } else {
        builder.setUri(watchUri(id)).setCustomCacheKey(id)
    }
    return builder.build()
}

fun Track.toMetadata(context: Context): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setDisplayTitle(title)
        // Shown under the title in Android Auto and the notification: say where the song comes from.
        .setSubtitle(if (artist.isEmpty()) source.label else "$artist · ${source.shortLabel}")
        .setArtworkUri(ArtworkProvider.uriFor(context, thumbnailUrl))
        .setDurationMs(if (durationSec > 0) durationSec * 1000 else null)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(if (isVideo) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(Bundle().apply {
            putString(EXTRA_THUMB, thumbnailUrl)
            putBoolean(EXTRA_VIDEO, isVideo)
        })
        .build()

fun MediaItem.toTrack(): Track {
    val m = mediaMetadata
    return Track(
        id = trackId(),
        title = m.title?.toString().orEmpty(),
        artist = m.artist?.toString().orEmpty(),
        durationSec = m.durationMs?.let { it / 1000 } ?: -1,
        thumbnailUrl = m.extras?.getString(EXTRA_THUMB),
        isVideo = m.extras?.getBoolean(EXTRA_VIDEO) ?: false,
    )
}

/** True when the item carries enough metadata to be played without a database lookup. */
fun MediaItem.hasMetadata(): Boolean = !mediaMetadata.title.isNullOrEmpty()
