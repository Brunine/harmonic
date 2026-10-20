package app.harmonic.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Picks a different data source per request: cached YouTube audio, resolved SoundCloud
 * playlists and plain HLS segments all go through one player.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(private val route: (DataSpec) -> DataSource) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = route(dataSpec)
        listeners.forEach { source.addTransferListener(it) }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current) { "Not opened" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val route: (DataSpec) -> DataSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(route)
    }
}
