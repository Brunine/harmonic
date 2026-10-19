package app.harmonic.music.data.remote

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Holds the YouTube session cookies captured by the in-app sign-in screen.
 *
 * The cookies are only ever sent to youtube.com, and only when a track is age-restricted
 * (see [AuthenticatedPlayer]). Everything else stays anonymous, which keeps the account
 * out of normal playback and avoids tying listening history to it.
 */
class AccountManager(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    private val _cookies = MutableStateFlow(prefs.getString(KEY_COOKIES, null))
    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, null))

    val cookies: StateFlow<String?> = _cookies.asStateFlow()
    val accountName: StateFlow<String?> = _name.asStateFlow()

    val isLoggedIn: Boolean get() = sapisid() != null

    fun save(cookies: String, name: String?) {
        prefs.edit().putString(KEY_COOKIES, cookies).putString(KEY_NAME, name).apply()
        _cookies.value = cookies
        _name.value = name
    }

    fun logout() {
        prefs.edit().clear().apply()
        _cookies.value = null
        _name.value = null
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun cookieMap(): Map<String, String> = _cookies.value.orEmpty()
        .split(';')
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
        .toMap()

    private fun sapisid(): String? {
        val map = cookieMap()
        return map["SAPISID"] ?: map["__Secure-3PAPISID"]
    }

    /** Headers that authenticate an InnerTube request as the signed-in user. */
    fun authHeaders(origin: String): Map<String, String>? {
        val cookie = _cookies.value ?: return null
        val sid = sapisid() ?: return null
        val ts = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "Cookie" to cookie,
            "Authorization" to "SAPISIDHASH ${ts}_$digest",
            "X-Origin" to origin,
            "Origin" to origin,
            "X-Goog-AuthUser" to "0",
        )
    }

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val KEY_NAME = "name"
    }
}
