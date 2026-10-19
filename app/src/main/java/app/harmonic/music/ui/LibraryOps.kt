package app.harmonic.music.ui

import app.harmonic.music.AppContainer
import app.harmonic.music.data.db.PlaylistEntity
import app.harmonic.music.data.model.Track
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

/** Copies a YouTube / YouTube Music playlist or album into the local library. */
suspend fun AppContainer.importPlaylist(url: String): Pair<Long, Int> {
    val online = youtube.playlistAll(url)
    val id = createPlaylist(online.title, online.tracks, url)
    return id to online.tracks.size
}

fun isPlaylistUrl(text: String) = text.contains("list=") && !text.contains("list=RD")

fun extractUrl(text: String): String? =
    Regex("https?://\\S+").find(text)?.value
