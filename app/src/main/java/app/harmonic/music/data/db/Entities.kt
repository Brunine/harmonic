package app.harmonic.music.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.harmonic.music.data.model.Track

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
    val isVideo: Boolean,
    val liked: Boolean = false,
    val likedAt: Long = 0,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
    /** Time the offline copy finished downloading, 0 when not downloaded. */
    val downloadedAt: Long = 0,
) {
    fun toTrack() = Track(id, title, artist, durationSec, thumbnailUrl, isVideo)
}

fun Track.toEntity() = SongEntity(
    id = id,
    title = title,
    artist = artist,
    durationSec = durationSec,
    thumbnailUrl = thumbnailUrl,
    isVideo = isVideo,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** YouTube URL when the playlist was imported. */
    val sourceUrl: String? = null,
)

@Entity(
    tableName = "playlist_songs",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("songId")],
)
data class PlaylistSongEntity(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long,
)

/** Remembers which audio format was cached for a video, so partial caches are never mixed. */
@Entity(tableName = "formats")
data class FormatEntity(
    @PrimaryKey val videoId: String,
    val itag: Int,
    val mimeType: String?,
    val bitrate: Int,
)

/** Which YouTube video plays a Deezer / Apple Music track. */
@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey val sourceId: String,
    val youtubeId: String,
    val matchedAt: Long,
)

data class PlaylistSummary(
    @Embedded val playlist: PlaylistEntity,
    val songCount: Int,
    val thumbnailUrl: String?,
)

data class PlaylistEntry(
    @Embedded val song: SongEntity,
    val entryId: Long,
)
