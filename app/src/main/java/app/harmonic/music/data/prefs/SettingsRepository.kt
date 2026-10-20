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

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

/** Colour palettes. [seed] is the key colour the whole scheme is derived from. */
enum class ThemePalette(val label: String, val seed: Long?) {
    WALLPAPER("Wallpaper colours", null),
    ALBUM("Current song's cover", null),
    EMBER("Ember", 0xFFFF6B4A),
    OCEAN("Ocean", 0xFF2F80ED),
    FOREST("Forest", 0xFF2E9E5B),
    VIOLET("Violet", 0xFF8E5CF7),
    ROSE("Rose", 0xFFE8508C),
    SUNSET("Sunset", 0xFFF2A33A),
    MONO("Monochrome", 0xFF808080),
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
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.EMBER,
    /** True black backgrounds in dark mode: saves battery on OLED screens. */
    val pureBlack: Boolean = false,
    /** Name shown to friends in a Jam; empty means the phone model. */
    val jamName: String = "",
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
        val themeMode = stringPreferencesKey("theme_mode")
        val palette = stringPreferencesKey("palette")
        val pureBlack = booleanPreferencesKey("pure_black")
        val jamName = stringPreferencesKey("jam_name")
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
            themeMode = this[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            palette = this[Keys.palette]?.let { runCatching { ThemePalette.valueOf(it) }.getOrNull() } ?: d.palette,
            pureBlack = this[Keys.pureBlack] ?: d.pureBlack,
            jamName = this[Keys.jamName] ?: d.jamName,
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
    suspend fun setThemeMode(v: ThemeMode) = store.edit { it[Keys.themeMode] = v.name }
    suspend fun setPalette(v: ThemePalette) = store.edit { it[Keys.palette] = v.name }
    suspend fun setPureBlack(v: Boolean) = store.edit { it[Keys.pureBlack] = v }
    suspend fun setJamName(v: String) = store.edit { it[Keys.jamName] = v.trim().take(30) }
}
