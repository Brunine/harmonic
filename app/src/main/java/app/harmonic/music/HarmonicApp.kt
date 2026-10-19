package app.harmonic.music

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class HarmonicApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.init()
        // Create the download manager on the main thread so it can resume pending downloads.
        container.downloads.downloadManager
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.okHttp)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        // YouTube image URLs are content-addressed, so cached copies never go stale.
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}

val Context.container: AppContainer get() = (applicationContext as HarmonicApp).container
