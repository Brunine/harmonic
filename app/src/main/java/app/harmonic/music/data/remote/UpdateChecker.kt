package app.harmonic.music.data.remote

import android.content.Context
import app.harmonic.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** A newer Harmonic published on GitHub Releases. */
data class AppUpdate(
    val version: String,
    val notes: String,
    /** Direct link to the APK file, when the release has one. */
    val apkUrl: String?,
    val pageUrl: String,
)

/**
 * "Check for updates": asks GitHub for the latest release of [BuildConfig.UPDATE_REPO]
 * (a few kilobytes) and compares its version with this app's. The automatic check runs at
 * most once a day.
 */
class UpdateChecker(
    context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val hasNetwork: () -> Boolean,
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _available = MutableStateFlow<AppUpdate?>(null)
    /** Set when the daily check found a version the user hasn't dismissed yet. */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Checks now. Returns the update, or null when this is the newest version. */
    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Harmonic/${BuildConfig.VERSION_NAME}")
            .build()
        val json = client.newCall(request).execute().use { response ->
            // 404: no release published yet (or the repository is private).
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("Empty response"))
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty() || !isNewer(version, currentVersion)) return@withContext null
        val assets = json.optJSONArray("assets")
        val apk = assets?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?.optString("browser_download_url")
        }
        AppUpdate(
            version = version,
            notes = json.optString("body").trim(),
            apkUrl = apk?.takeIf { it.isNotEmpty() },
            pageUrl = json.optString("html_url").ifEmpty { "https://github.com/${BuildConfig.UPDATE_REPO}/releases" },
        )
    }

    /** The automatic check: once a day, quietly, and only for versions not dismissed before. */
    fun checkDaily() {
        if (!enabled() || !hasNetwork()) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < DAY_MS) return
        scope.launch {
            val update = runCatching { check() }.getOrNull() ?: return@launch
            if (update.version != prefs.getString(KEY_DISMISSED, null)) _available.value = update
        }
    }

    /** "Later": don't offer this version again automatically. */
    fun dismiss(update: AppUpdate) {
        prefs.edit().putString(KEY_DISMISSED, update.version).apply()
        _available.value = null
    }

    companion object {
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_DISMISSED = "dismissed"
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** "1.10.0" > "1.9.2"; missing parts count as 0. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val b = current.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
