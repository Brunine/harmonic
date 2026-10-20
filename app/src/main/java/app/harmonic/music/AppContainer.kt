package app.harmonic.music

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.room.Room
import app.harmonic.music.data.db.AppDatabase
import app.harmonic.music.data.prefs.SettingsRepository
import app.harmonic.music.data.remote.AccountManager
import app.harmonic.music.data.remote.AppleMusicClient
import app.harmonic.music.data.remote.AuthenticatedPlayer
import app.harmonic.music.data.remote.CatalogRepository
import app.harmonic.music.data.remote.DeezerClient
import app.harmonic.music.data.remote.NewPipeDownloader
import app.harmonic.music.data.remote.SponsorBlockClient
import app.harmonic.music.data.remote.Thumbnails
import app.harmonic.music.data.remote.TrackMatcher
import app.harmonic.music.data.remote.YouTubeRepository
import app.harmonic.music.data.remote.YouTubeStreamInterceptor
import app.harmonic.music.download.DownloadUtil
import app.harmonic.music.jam.JamManager
import app.harmonic.music.playback.RoutingDataSource
import app.harmonic.music.playback.StreamResolver
import app.harmonic.music.playback.trackIdFromPlaceholder
import app.harmonic.music.ui.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Hand-written dependency graph; everything is created lazily on first use. */
@OptIn(UnstableApi::class)
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Shares the connection pool with [okHttp]; adds YouTube's request shaping for audio. */
    private val streamOkHttp: OkHttpClient by lazy {
        okHttp.newBuilder().addInterceptor(YouTubeStreamInterceptor()).build()
    }

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    fun isMetered(): Boolean = connectivity?.isActiveNetworkMetered ?: true
    fun hasNetwork(): Boolean = connectivity?.activeNetwork != null

    val settings = SettingsRepository(app, appScope)
    val account = AccountManager(app)

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "harmonic.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()

    private val thumbnailPx: () -> Int = {
        if (settings.current.dataSaverImages && isMetered()) Thumbnails.DATA_SAVER_PX else Thumbnails.NORMAL_PX
    }

    val youtube = YouTubeRepository(thumbnailPx)
    private val matcher by lazy { TrackMatcher(youtube, db.songDao(), db.matchDao()) }
    val catalog: CatalogRepository by lazy {
        CatalogRepository(
            youtube,
            DeezerClient(okHttp, thumbnailPx),
            AppleMusicClient(okHttp, thumbnailPx),
            matcher,
            okHttp,
        )
    }
    val sponsorBlock = SponsorBlockClient(okHttp)
    private val authPlayer by lazy { AuthenticatedPlayer(okHttp, account) }

    private val databaseProvider by lazy { StandaloneDatabaseProvider(app) }

    /** Streaming cache: replaying a recent song costs no data. Size applies on next start. */
    val playerCache: SimpleCache by lazy {
        SimpleCache(
            File(app.cacheDir, "exo_player"),
            LeastRecentlyUsedCacheEvictor(settings.current.cacheSizeMb * 1024L * 1024L),
            databaseProvider,
        )
    }

    /** Offline downloads; never evicted automatically. Lives in filesDir so the OS keeps it. */
    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "exo_downloads"), NoOpCacheEvictor(), databaseProvider)
    }

    val resolver: StreamResolver by lazy {
        StreamResolver(
            app, youtube, matcher, authPlayer, account, db.formatDao(), settings,
            { playerCache }, { downloadCache }, appScope,
        )
    }

    private fun resolvingFactory(forDownload: Boolean): DataSource.Factory =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(streamOkHttp)) { spec ->
            val id = spec.key ?: trackIdFromPlaceholder(spec.uri) ?: throw IOException("Missing track id")
            spec.withUri(Uri.parse(resolver.resolve(id, forDownload).url))
        }

    /** Download cache (read-only) -> player cache -> resolve + network. */
    private val cachedFactory: DataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(resolvingFactory(forDownload = false))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
            )
    }

    /**
     * Playback read path. YouTube / Deezer / Apple Music audio goes through the caches and the
     * stream URL is only resolved when bytes are actually missing. SoundCloud HLS playlists are
     * resolved without caching, and their segments are fetched directly.
     */
    val playbackDataSourceFactory: DataSource.Factory by lazy {
        val soundCloudFactory = resolvingFactory(forDownload = false)
        val plainFactory = OkHttpDataSource.Factory(okHttp)
        RoutingDataSource.Factory { spec ->
            when (spec.uri.host) {
                "music.youtube.com" -> cachedFactory.createDataSource()
                "soundcloud.com" -> soundCloudFactory.createDataSource()
                else -> plainFactory.createDataSource()
            }
        }
    }

    val downloads: DownloadUtil by lazy {
        DownloadUtil(
            context = app,
            databaseProvider = { databaseProvider },
            downloadCache = { downloadCache },
            playerCache = { playerCache },
            upstreamFactory = { resolvingFactory(forDownload = true) },
            songDao = db.songDao(),
            scope = appScope,
            wifiOnly = { settings.current.downloadWifiOnly },
        )
    }

    val playerConnection by lazy { PlayerConnection(app) { jam } }

    val jam: JamManager by lazy { JamManager(app, this) }

    fun init() {
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(okHttp),
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
    }
}
