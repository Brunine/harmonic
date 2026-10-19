package app.harmonic.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.harmonic.music.data.model.Track
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SongDao {
    // IGNORE + UPDATE instead of REPLACE: REPLACE deletes the row first, which would cascade
    // and silently remove the song from every playlist.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnoreAll(songs: List<SongEntity>)

    @Query(
        """UPDATE songs SET title = :title, artist = :artist,
           durationSec = CASE WHEN :durationSec > 0 THEN :durationSec ELSE durationSec END,
           thumbnailUrl = COALESCE(:thumbnailUrl, thumbnailUrl)
           WHERE id = :id""",
    )
    abstract suspend fun updateMeta(
        id: String,
        title: String,
        artist: String,
        durationSec: Long,
        thumbnailUrl: String?,
    )

    @Transaction
    open suspend fun upsert(track: Track) {
        if (insertIgnore(track.toEntity()) == -1L) {
            updateMeta(track.id, track.title, track.artist, track.durationSec, track.thumbnailUrl)
        }
    }

    @Transaction
    open suspend fun upsertAll(tracks: List<Track>) {
        tracks.forEach { upsert(it) }
    }

    @Query("SELECT * FROM songs WHERE id = :id")
    abstract suspend fun get(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id")
    abstract fun observe(id: String): Flow<SongEntity?>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    abstract suspend fun getAll(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC")
    abstract fun liked(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    abstract fun recent(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    abstract fun mostPlayed(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE downloadedAt > 0 ORDER BY downloadedAt DESC")
    abstract fun downloaded(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE liked = 1 ORDER BY likedAt DESC LIMIT :limit")
    abstract suspend fun likedOnce(limit: Int): List<SongEntity>

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    abstract suspend fun recentOnce(limit: Int): List<SongEntity>

    @Query("SELECT * FROM songs WHERE downloadedAt > 0 ORDER BY downloadedAt DESC LIMIT :limit")
    abstract suspend fun downloadedOnce(limit: Int): List<SongEntity>

    @Query(
        """SELECT * FROM songs WHERE (title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%')
           AND (downloadedAt > 0 OR liked = 1 OR lastPlayedAt > 0)
           ORDER BY downloadedAt DESC, playCount DESC LIMIT :limit""",
    )
    abstract suspend fun searchLocal(q: String, limit: Int): List<SongEntity>

    @Query("UPDATE songs SET liked = :liked, likedAt = :at WHERE id = :id")
    abstract suspend fun setLiked(id: String, liked: Boolean, at: Long)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    abstract suspend fun markPlayed(id: String, at: Long)

    @Query("UPDATE songs SET downloadedAt = :at WHERE id = :id")
    abstract suspend fun setDownloaded(id: String, at: Long)

    @Query("SELECT id FROM songs WHERE downloadedAt > 0")
    abstract suspend fun downloadedIds(): List<String>

    @Query("UPDATE songs SET lastPlayedAt = 0")
    abstract suspend fun clearHistory()
}

@Dao
abstract class PlaylistDao {
    @Insert
    abstract suspend fun insert(playlist: PlaylistEntity): Long

    @Query(
        """SELECT p.*,
             (SELECT COUNT(*) FROM playlist_songs ps WHERE ps.playlistId = p.id) AS songCount,
             (SELECT s.thumbnailUrl FROM playlist_songs ps JOIN songs s ON s.id = ps.songId
               WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 1) AS thumbnailUrl
           FROM playlists p ORDER BY p.createdAt DESC""",
    )
    abstract fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    abstract suspend fun allOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observe(id: Long): Flow<PlaylistEntity?>

    @Query(
        """SELECT s.*, ps.entryId AS entryId FROM playlist_songs ps
           JOIN songs s ON s.id = ps.songId
           WHERE ps.playlistId = :id ORDER BY ps.position""",
    )
    abstract fun entries(id: Long): Flow<List<PlaylistEntry>>

    @Query(
        """SELECT s.* FROM playlist_songs ps JOIN songs s ON s.id = ps.songId
           WHERE ps.playlistId = :id ORDER BY ps.position""",
    )
    abstract suspend fun songsOnce(id: Long): List<SongEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    abstract suspend fun maxPosition(id: Long): Int

    @Insert
    abstract suspend fun insertEntries(entries: List<PlaylistSongEntity>)

    @Transaction
    open suspend fun append(playlistId: Long, songIds: List<String>) {
        var pos = maxPosition(playlistId) + 1
        val now = System.currentTimeMillis()
        insertEntries(songIds.map { PlaylistSongEntity(0, playlistId, it, pos++, now) })
    }

    @Query("DELETE FROM playlist_songs WHERE entryId = :entryId")
    abstract suspend fun removeEntry(entryId: Long)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun delete(id: Long)
}

@Dao
abstract class FormatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsert(format: FormatEntity)

    @Query("SELECT * FROM formats WHERE videoId = :id")
    abstract fun get(id: String): FormatEntity?
}
