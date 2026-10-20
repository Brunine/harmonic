package app.harmonic.music.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        FormatEntity::class,
        MatchEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun formatDao(): FormatDao
    abstract fun matchDao(): MatchDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `matches` (`sourceId` TEXT NOT NULL, " +
                        "`youtubeId` TEXT NOT NULL, `matchedAt` INTEGER NOT NULL, PRIMARY KEY(`sourceId`))",
                )
            }
        }

        /** 1.4.0: loudness per song for volume normalization. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `formats` ADD COLUMN `loudnessDb` REAL")
            }
        }
    }
}
