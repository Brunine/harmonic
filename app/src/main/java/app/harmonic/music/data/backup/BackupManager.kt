package app.harmonic.music.data.backup

import android.content.Context
import android.net.Uri
import app.harmonic.music.AppContainer
import app.harmonic.music.data.db.MatchEntity
import app.harmonic.music.data.db.PlaylistEntity
import app.harmonic.music.data.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Saves the library to one JSON file and brings it back: settings, songs with likes and
 * listening history, playlists (in order), Deezer/Apple matches and search history.
 * Downloaded audio is not included (it would be gigabytes); it can be downloaded again.
 *
 * Restoring merges into what's on the phone: likes and play counts keep the higher value, and a
 * playlist with the same name is replaced by the backup's version.
 */
class BackupManager(private val context: Context, private val app: AppContainer) {

    data class Summary(val songs: Int, val playlists: Int)

    suspend fun export(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val songDao = app.db.songDao()
        val playlistDao = app.db.playlistDao()
        val songs = songDao.allOnce()
        val playlists = playlistDao.allOnce()

        val json = JSONObject().apply {
            put("app", "harmonic")
            put("format", FORMAT)
            put("exportedAt", System.currentTimeMillis())
            put("settings", app.settings.exportJson())
            put("songs", JSONArray().apply { songs.forEach { put(it.toJson()) } })
            put(
                "playlists",
                JSONArray().apply {
                    playlists.forEach { p ->
                        put(
                            JSONObject()
                                .put("name", p.name)
                                .put("createdAt", p.createdAt)
                                .put("sourceUrl", p.sourceUrl ?: "")
                                .put("songs", JSONArray(playlistDao.songsOnce(p.id).map { it.id })),
                        )
                    }
                },
            )
            put(
                "matches",
                JSONArray().apply {
                    app.db.matchDao().all().forEach { put(JSONObject().put("sourceId", it.sourceId).put("youtubeId", it.youtubeId)) }
                },
            )
            put("searchHistory", JSONArray(app.searchHistory.items.value))
        }

        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toString(1).toByteArray()) }
            ?: throw IOException("Couldn't open the file")
        Summary(songs.size, playlists.size)
    }

    suspend fun import(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IOException("Couldn't open the file")
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null || json.optString("app") != "harmonic") throw IOException("NOT_A_BACKUP")

        val songDao = app.db.songDao()
        val playlistDao = app.db.playlistDao()

        // Songs first: playlists reference them.
        val songsJson = json.optJSONArray("songs") ?: JSONArray()
        val songs = (0 until songsJson.length()).mapNotNull { songFromJson(songsJson.getJSONObject(it)) }
        songDao.insertIgnoreAll(songs)
        songs.forEach { songDao.mergeStats(it.id, it.liked, it.likedAt, it.playCount, it.lastPlayedAt) }
        val known = songs.map { it.id }.toHashSet()

        val playlistsJson = json.optJSONArray("playlists") ?: JSONArray()
        var playlistCount = 0
        for (i in 0 until playlistsJson.length()) {
            val p = playlistsJson.getJSONObject(i)
            val name = p.optString("name")
            if (name.isBlank()) continue
            val ids = p.optJSONArray("songs")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                .orEmpty()
                .filter { it in known || songDao.get(it) != null }
            val existing = playlistDao.findByName(name)
            val id = if (existing != null) {
                playlistDao.clearEntries(existing.id)
                existing.id
            } else {
                playlistDao.insert(
                    PlaylistEntity(
                        name = name,
                        createdAt = p.optLong("createdAt", System.currentTimeMillis()),
                        sourceUrl = p.optString("sourceUrl").ifEmpty { null },
                    ),
                )
            }
            if (ids.isNotEmpty()) playlistDao.append(id, ids)
            playlistCount++
        }

        json.optJSONArray("matches")?.let { arr ->
            val dao = app.db.matchDao()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val source = m.optString("sourceId")
                val yt = m.optString("youtubeId")
                if (source.isNotEmpty() && yt.isNotEmpty()) dao.upsert(MatchEntity(source, yt, System.currentTimeMillis()))
            }
        }

        json.optJSONArray("searchHistory")?.let { arr ->
            app.searchHistory.replaceAll((0 until arr.length()).map { arr.getString(it) })
        }
        json.optJSONObject("settings")?.let { app.settings.importJson(it) }

        Summary(songs.size, playlistCount)
    }

    private fun SongEntity.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("durationSec", durationSec)
        .put("thumbnailUrl", thumbnailUrl ?: "")
        .put("isVideo", isVideo)
        .put("liked", liked)
        .put("likedAt", likedAt)
        .put("playCount", playCount)
        .put("lastPlayedAt", lastPlayedAt)

    private fun songFromJson(o: JSONObject): SongEntity? {
        val id = o.optString("id").ifEmpty { return null }
        return SongEntity(
            id = id,
            title = o.optString("title"),
            artist = o.optString("artist"),
            durationSec = o.optLong("durationSec", -1),
            thumbnailUrl = o.optString("thumbnailUrl").ifEmpty { null },
            isVideo = o.optBoolean("isVideo"),
            liked = o.optBoolean("liked"),
            likedAt = o.optLong("likedAt"),
            playCount = o.optInt("playCount"),
            lastPlayedAt = o.optLong("lastPlayedAt"),
        )
    }

    companion object {
        private const val FORMAT = 1
        const val MIME = "application/json"
    }
}
