package app.harmonic.music.playback

import app.harmonic.music.data.db.FormatDao
import app.harmonic.music.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/**
 * Volume normalization: songs mastered louder than YouTube's reference level are turned down
 * to it, the way YouTube itself does. Quiet songs are left as they are (raising them could
 * distort), so the difference between loud and normal songs disappears.
 *
 * The loudness value comes with the stream information Harmonic already fetches, and is
 * remembered in the database, so downloaded and cached songs are normalized offline too.
 */
class Normalizer(
    private val formatDao: FormatDao,
    private val settings: SettingsRepository,
    private val volume: VolumeMixer,
    private val scope: CoroutineScope,
) {
    /** Known loudness per track id; NaN = we looked and YouTube gave none. */
    private val known = ConcurrentHashMap<String, Float>()
    private var currentId: String? = null

    init {
        scope.launch(Dispatchers.Main) {
            settings.state.map { it.normalizeVolume }.distinctUntilChanged().collect { reapply() }
        }
    }

    /** Main thread: the player moved to another song. */
    fun onTrackChanged(id: String?) {
        currentId = id
        if (id == null) {
            volume.normalization = 1f
            return
        }
        val cached = known[id]
        if (cached != null) {
            apply(cached)
            return
        }
        // Not seen this session: the database knows it if the song was played or downloaded
        // before. Until then, play at full volume (as before normalization existed).
        volume.normalization = 1f
        scope.launch(Dispatchers.IO) {
            val stored = runCatching { formatDao.get(id)?.loudnessDb }.getOrNull() ?: return@launch
            known[id] = stored
            withContext(Dispatchers.Main) { if (currentId == id) apply(stored) }
        }
    }

    /** Any thread: the stream resolver just learned a song's loudness. */
    fun onLoudnessKnown(id: String, loudnessDb: Float?) {
        val value = loudnessDb ?: Float.NaN
        known[id] = value
        scope.launch(Dispatchers.Main) { if (currentId == id) apply(value) }
    }

    private fun reapply() {
        val id = currentId ?: return
        apply(known[id] ?: return)
    }

    private fun apply(loudnessDb: Float) {
        volume.normalization = if (settings.current.normalizeVolume && !loudnessDb.isNaN() && loudnessDb > 0f) {
            10f.pow(-loudnessDb / 20f)
        } else {
            1f
        }
    }
}
