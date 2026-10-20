package app.harmonic.music.ui

import app.harmonic.music.AppContainer
import app.harmonic.music.data.db.PlaylistEntity
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.remote.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small library mutations shared by several screens. */

suspend fun AppContainer.toggleLike(track: Track): Boolean = withContext(Dispatchers.IO) {
    val dao = db.songDao()
    dao.upsert(track)
    val liked = !(dao.get(track.id)?.liked ?: false)
    dao.setLiked(track.id, liked, System.currentTimeMillis())
    liked
}

suspend fun AppContainer.createPlaylist(name: String, tracks: List<Track> = emptyList(), sourceUrl: String? = null): Long =
    withContext(Dispatchers.IO) {
        val id = db.playlistDao().insert(
            PlaylistEntity(name = name.trim().ifEmpty { "Playlist" }, createdAt = System.currentTimeMillis(), sourceUrl = sourceUrl),
        )
        if (tracks.isNotEmpty()) addToPlaylist(id, tracks)
        id
    }

suspend fun AppContainer.addToPlaylist(playlistId: Long, tracks: List<Track>) = withContext(Dispatchers.IO) {
    db.songDao().upsertAll(tracks)
    db.playlistDao().append(playlistId, tracks.map { it.id })
}

/** Copies a playlist or album from any supported service into the local library. */
suspend fun AppContainer.importPlaylist(url: String): Pair<Long, Int> {
    val collectionUrl = when (val target = catalog.resolveLink(url)) {
        is CatalogRepository.LinkTarget.Collection -> target.url
        is CatalogRepository.LinkTarget.Song -> throw IllegalArgumentException(LINK_IS_SONG)
        null -> throw IllegalArgumentException(LINK_UNSUPPORTED)
    }
    val online = catalog.collectionAll(collectionUrl)
    val id = createPlaylist(online.title, online.tracks, collectionUrl)
    return id to online.tracks.size
}

/** Error codes thrown by [importPlaylist]; screens turn them into translated messages. */
const val LINK_IS_SONG = "LINK_IS_SONG"
const val LINK_UNSUPPORTED = "LINK_UNSUPPORTED"

fun extractUrl(text: String): String? =
    Regex("https?://\\S+").find(text)?.value
