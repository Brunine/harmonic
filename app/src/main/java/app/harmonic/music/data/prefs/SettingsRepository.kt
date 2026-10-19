package app.harmonic.music.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

enum class AudioQuality(val label: String, val description: String) {
    LOW("Low", "~50 kbps Opus. About 22 MB per hour"),
    NORMAL("Normal", "~128 kbps. About 58 MB per hour"),
    HIGH("High", "Best available, ~160 kbps Opus"),
}

data class AppSettings(
    val qualityWifi: AudioQuality = AudioQuality.HIGH,
    val qualityMobile: AudioQuality = AudioQuality.LOW,
    val qualityDownload: AudioQuality = AudioQuality.HIGH,
    val downloadWifiOnly: Boolean = true,
    val cacheSizeMb: Int = 512,
    val sponsorBlock: Boolean = true,
    val autoplayRelated: Boolean = true,
    val audioOffload: Boolean = true,
    val dataSaverImages: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.dataStore

    private object Keys {
        val qualityWifi = stringPreferencesKey("quality_wifi")
        val qualityMobile = stringPreferencesKey("quality_mobile")
        val qualityDownload = stringPreferencesKey("quality_download")
        val downloadWifiOnly = booleanPreferencesKey("download_wifi_only")
        val cacheSizeMb = intPreferencesKey("cache_size_mb")
        val sponsorBlock = booleanPreferencesKey("sponsorblock")
        val autoplayRelated = booleanPreferencesKey("autoplay_related")
        val audioOffload = booleanPreferencesKey("audio_offload")
        val dataSaverImages = booleanPreferencesKey("data_saver_images")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        fun q(key: Preferences.Key<String>, def: AudioQuality) =
            this[key]?.let { runCatching { AudioQuality.valueOf(it) }.getOrNull() } ?: def
        return AppSettings(
            qualityWifi = q(Keys.qualityWifi, d.qualityWifi),
            qualityMobile = q(Keys.qualityMobile, d.qualityMobile),
            qualityDownload = q(Keys.qualityDownload, d.qualityDownload),
            downloadWifiOnly = this[Keys.downloadWifiOnly] ?: d.downloadWifiOnly,
            cacheSizeMb = this[Keys.cacheSizeMb] ?: d.cacheSizeMb,
            sponsorBlock = this[Keys.sponsorBlock] ?: d.sponsorBlock,
            autoplayRelated = this[Keys.autoplayRelated] ?: d.autoplayRelated,
            audioOffload = this[Keys.audioOffload] ?: d.audioOffload,
            dataSaverImages = this[Keys.dataSaverImages] ?: d.dataSaverImages,
        )
    }

    val flow: Flow<AppSettings> = store.data.map { it.toSettings() }

    /**
     * Snapshot readable from any thread (the player's loader threads read it synchronously).
     * The first read happens once at startup and is tiny.
     */
    val state: StateFlow<AppSettings> = flow.stateIn(
        scope,
        SharingStarted.Eagerly,
        runBlocking { flow.first() },
    )

    val current: AppSettings get() = state.value

    suspend fun setQualityWifi(q: AudioQuality) = store.edit { it[Keys.qualityWifi] = q.name }
    suspend fun setQualityMobile(q: AudioQuality) = store.edit { it[Keys.qualityMobile] = q.name }
    suspend fun setQualityDownload(q: AudioQuality) = store.edit { it[Keys.qualityDownload] = q.name }
    suspend fun setDownloadWifiOnly(v: Boolean) = store.edit { it[Keys.downloadWifiOnly] = v }
    suspend fun setCacheSizeMb(v: Int) = store.edit { it[Keys.cacheSizeMb] = v }
    suspend fun setSponsorBlock(v: Boolean) = store.edit { it[Keys.sponsorBlock] = v }
    suspend fun setAutoplayRelated(v: Boolean) = store.edit { it[Keys.autoplayRelated] = v }
    suspend fun setAudioOffload(v: Boolean) = store.edit { it[Keys.audioOffload] = v }
    suspend fun setDataSaverImages(v: Boolean) = store.edit { it[Keys.dataSaverImages] = v }
}
