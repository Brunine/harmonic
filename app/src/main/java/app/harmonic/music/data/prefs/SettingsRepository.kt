package app.harmonic.music.data.prefs

import android.content.Context
import androidx.annotation.StringRes
import app.harmonic.music.R
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
import org.json.JSONObject

enum class AudioQuality(@StringRes val label: Int, @StringRes val description: Int) {
    LOW(R.string.quality_low, R.string.quality_low_desc),
    NORMAL(R.string.quality_normal, R.string.quality_normal_desc),
    HIGH(R.string.quality_high, R.string.quality_high_desc),
}

/** What the music does when something else needs the sound. */
enum class InterruptAction(@StringRes val label: Int) {
    PAUSE(R.string.action_pause),
    LOWER(R.string.action_lower),
    KEEP(R.string.action_keep),
}

enum class ThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
}

/** Colour palettes. [seed] is the key colour the whole scheme is derived from. */
enum class ThemePalette(@StringRes val label: Int, val seed: Long?) {
    SYNTHWAVE(R.string.palette_synthwave, 0xFFFF2E97),
    WALLPAPER(R.string.palette_wallpaper, null),
    ALBUM(R.string.palette_album, null),
    EMBER(R.string.palette_ember, 0xFFFF6B4A),
    OCEAN(R.string.palette_ocean, 0xFF2F80ED),
    FOREST(R.string.palette_forest, 0xFF2E9E5B),
    VIOLET(R.string.palette_violet, 0xFF8E5CF7),
    ROSE(R.string.palette_rose, 0xFFE8508C),
    SUNSET(R.string.palette_sunset, 0xFFF2A33A),
    MONO(R.string.palette_mono, 0xFF808080),
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
    val themeMode: ThemeMode = ThemeMode.DARK,
    val palette: ThemePalette = ThemePalette.SYNTHWAVE,
    /** True black backgrounds in dark mode: saves battery on OLED screens. */
    val pureBlack: Boolean = false,
    /** Name shown to friends in a Jam; empty means the phone model. */
    val jamName: String = "",
    val onCalls: InterruptAction = InterruptAction.PAUSE,
    val onRecording: InterruptAction = InterruptAction.PAUSE,
    val onOtherSounds: InterruptAction = InterruptAction.PAUSE,
    val onNavigation: InterruptAction = InterruptAction.LOWER,
    val onOtherMusic: InterruptAction = InterruptAction.PAUSE,
    val resumeAfterInterruption: Boolean = true,
    /** Turn loud songs down to YouTube's reference level. */
    val normalizeVolume: Boolean = true,
    /** Download liked songs by themselves while on Wi-Fi. */
    val autoDownloadLiked: Boolean = false,
    /** Look for a new Harmonic version once a day. */
    val checkUpdates: Boolean = true,
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
        val onCalls = stringPreferencesKey("on_calls")
        val onRecording = stringPreferencesKey("on_recording")
        val onOtherSounds = stringPreferencesKey("on_other_sounds")
        val onNavigation = stringPreferencesKey("on_navigation")
        val onOtherMusic = stringPreferencesKey("on_other_music")
        val resumeAfterInterruption = booleanPreferencesKey("resume_after_interruption")
        val normalizeVolume = booleanPreferencesKey("normalize_volume")
        val autoDownloadLiked = booleanPreferencesKey("auto_download_liked")
        val checkUpdates = booleanPreferencesKey("check_updates")
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        fun action(key: Preferences.Key<String>, def: InterruptAction) =
            this[key]?.let { runCatching { InterruptAction.valueOf(it) }.getOrNull() } ?: def
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
            onCalls = action(Keys.onCalls, d.onCalls),
            onRecording = action(Keys.onRecording, d.onRecording),
            onOtherSounds = action(Keys.onOtherSounds, d.onOtherSounds),
            onNavigation = action(Keys.onNavigation, d.onNavigation),
            onOtherMusic = action(Keys.onOtherMusic, d.onOtherMusic),
            resumeAfterInterruption = this[Keys.resumeAfterInterruption] ?: d.resumeAfterInterruption,
            normalizeVolume = this[Keys.normalizeVolume] ?: d.normalizeVolume,
            autoDownloadLiked = this[Keys.autoDownloadLiked] ?: d.autoDownloadLiked,
            checkUpdates = this[Keys.checkUpdates] ?: d.checkUpdates,
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

    /** All stored settings as JSON, for backups. */
    suspend fun exportJson(): JSONObject {
        val json = JSONObject()
        store.data.first().asMap().forEach { (key, value) -> json.put(key.name, value) }
        return json
    }

    /** Applies settings from a backup. Unknown keys are kept as they are, so older backups work. */
    suspend fun importJson(json: JSONObject) {
        store.edit { prefs ->
            json.keys().forEach { name ->
                when (val value = json.get(name)) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is Int -> prefs[intPreferencesKey(name)] = value
                    is Number -> prefs[intPreferencesKey(name)] = value.toInt()
                    is String -> prefs[stringPreferencesKey(name)] = value
                }
            }
        }
    }

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
    suspend fun setOnCalls(v: InterruptAction) = store.edit { it[Keys.onCalls] = v.name }
    suspend fun setOnRecording(v: InterruptAction) = store.edit { it[Keys.onRecording] = v.name }
    suspend fun setOnOtherSounds(v: InterruptAction) = store.edit { it[Keys.onOtherSounds] = v.name }
    suspend fun setOnNavigation(v: InterruptAction) = store.edit { it[Keys.onNavigation] = v.name }
    suspend fun setOnOtherMusic(v: InterruptAction) = store.edit { it[Keys.onOtherMusic] = v.name }
    suspend fun setResumeAfterInterruption(v: Boolean) = store.edit { it[Keys.resumeAfterInterruption] = v }
    suspend fun setCheckUpdates(v: Boolean) = store.edit { it[Keys.checkUpdates] = v }
    suspend fun setAutoDownloadLiked(v: Boolean) = store.edit { it[Keys.autoDownloadLiked] = v }
    suspend fun setNormalizeVolume(v: Boolean) = store.edit { it[Keys.normalizeVolume] = v }
    suspend fun setJamName(v: String) = store.edit { it[Keys.jamName] = v.trim().take(30) }
}
