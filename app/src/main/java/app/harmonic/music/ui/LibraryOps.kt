package app.harmonic.music.ui

import android.content.res.Resources
import app.harmonic.music.AppContainer
import app.harmonic.music.R
import app.harmonic.music.data.db.PlaylistEntity
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.remote.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        is CatalogRepository.LinkTarget.Album ->
            browser.album(target.id).playlistUrl ?: throw IllegalArgumentException(LINK_UNSUPPORTED)
        is CatalogRepository.LinkTarget.Song -> throw IllegalArgumentException(LINK_IS_SONG)
        is CatalogRepository.LinkTarget.Artist, null -> throw IllegalArgumentException(LINK_UNSUPPORTED)
    }
    val online = catalog.collectionAll(collectionUrl)
    val id = createPlaylist(online.title, online.tracks, collectionUrl)
    return id to online.tracks.size
}

/**
 * Opens the artist page for [track]'s artist, looking the artist up by name when the song
 * didn't come with an artist id.
 */
fun AppContainer.openArtist(track: Track, actions: UiActions, res: Resources) {
    appScope.launch(Dispatchers.Main) {
        val id = runCatching { catalog.artistIdFor(track) }.getOrNull()
        if (id != null) {
            actions.navigate(Routes.artist(id))
        } else {
            actions.showMessage(res.getString(R.string.artist_not_found))
        }
    }
}

/** Error codes thrown by [importPlaylist]; screens turn them into translated messages. */
const val LINK_IS_SONG = "LINK_IS_SONG"
const val LINK_UNSUPPORTED = "LINK_UNSUPPORTED"

fun extractUrl(text: String): String? =
    Regex("https?://\\S+").find(text)?.value
