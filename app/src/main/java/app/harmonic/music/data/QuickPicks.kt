package app.harmonic.music.data

import android.content.Context
import app.harmonic.music.data.db.SongDao
import app.harmonic.music.data.model.Track
import app.harmonic.music.data.remote.CatalogRepository
import app.harmonic.music.jam.JamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Recommendations based on the last song played, shared by the Home screen and Android Auto.
 * The last list is kept on the phone, so the car's "Quick picks" folder opens instantly (even
 * without signal) and a refresh happens in the background.
 *
 * Refreshing costs one small metadata request. It runs on its own only on Wi-Fi, or on mobile
 * data when there is nothing to show yet.
 */
class QuickPicks(
    context: Context,
    private val songDao: SongDao,
    private val catalog: () -> CatalogRepository,
    private val isMetered: () -> Boolean,
    private val hasNetwork: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("quick_picks", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Track>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val isStale: Boolean
        get() = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0) > MAX_AGE_MS ||
            prefs.getString(KEY_SEED, null) != lastSeedId

    /** Id of the song the list was built from; a new last-played song makes the list stale. */
    @Volatile
    private var lastSeedId: String? = prefs.getString(KEY_SEED, null)

    /** Refreshes in the background when the list is old, following the data rules above. */
    fun refreshIfNeeded() {
        scope.launch(Dispatchers.IO) {
            lastSeedId = songDao.recentOnce(1).firstOrNull()?.id
            if (!isStale || !hasNetwork()) return@launch
            if (isMetered() && _items.value.isNotEmpty()) return@launch
            refresh()
        }
    }

    /** Builds a fresh list now (also on mobile data: the user asked for it). */
    suspend fun refresh(): List<Track> = mutex.withLock {
        val seed = songDao.recentOnce(1).firstOrNull()?.toTrack() ?: return@withLock _items.value
        _loading.value = true
        try {
            val picks = runCatching { catalog().related(seed).take(MAX_PICKS) }.getOrNull()
            if (!picks.isNullOrEmpty()) {
                _items.value = picks
                lastSeedId = seed.id
                prefs.edit()
                    .putString(KEY_TRACKS, JamProtocol.tracksToJson(picks).toString())
                    .putString(KEY_SEED, seed.id)
                    .putLong(KEY_AT, System.currentTimeMillis())
                    .apply()
            }
            _items.value
        } finally {
            _loading.value = false
        }
    }

    private fun load(): List<Track> = runCatching {
        JamProtocol.tracksFromJson(JSONArray(prefs.getString(KEY_TRACKS, null) ?: return emptyList()))
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY_TRACKS = "tracks"
        const val KEY_SEED = "seed"
        const val KEY_AT = "at"
        const val MAX_PICKS = 20
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
