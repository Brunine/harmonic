package app.harmonic.music.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.harmonic.music.data.db.SongDao
import app.harmonic.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Download liked songs": while the setting is on and the phone is on Wi-Fi (or another
 * unmetered network), every liked song that isn't downloaded yet is queued for download.
 * Liking a song on Wi-Fi downloads it right away; songs liked on mobile data follow the next
 * time Wi-Fi is available. Unliking never deletes a download.
 */
class AutoDownloader(
    context: Context,
    private val songDao: SongDao,
    private val settings: SettingsRepository,
    private val downloads: () -> DownloadUtil,
    private val scope: CoroutineScope,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun start() {
        val enabled = settings.state.map { it.autoDownloadLiked }.distinctUntilChanged()
        // Only the set of liked ids matters: other song changes (play counts...) are ignored,
        // which also keeps our own database writes from re-triggering this.
        val liked = songDao.liked().distinctUntilChangedBy { list -> list.map { it.id }.toSet() }
        scope.launch(Dispatchers.Main) {
            combine(enabled, unmeteredNetwork(), liked) { on, unmetered, songs ->
                if (on && unmetered) songs else emptyList()
            }
                .conflate()
                .collect { songs ->
                    if (songs.isEmpty()) return@collect
                    val manager = downloads()
                    val states = manager.states.value
                    val missing = songs
                        .filter { song -> states[song.id]?.let { it.isDone || it.isActive } != true }
                        .map { it.toTrack() }
                    if (missing.isNotEmpty()) manager.download(missing)
                }
        }
    }

    /** True while the default network is unmetered (Wi-Fi, Ethernet). */
    private fun unmeteredNetwork(): Flow<Boolean> = callbackFlow {
        val cm = connectivity
        if (cm == null) {
            trySend(false)
            awaitClose()
            return@callbackFlow
        }
        fun current(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        trySend(current())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) {
                trySend(current())
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
