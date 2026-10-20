package app.harmonic.music.ui.screens

import app.harmonic.music.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.harmonic.music.ui.LocalContainer
import app.harmonic.music.ui.LocalUiActions

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue" +
        "%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"

/**
 * Google sign-in in a WebView. When the flow lands on music.youtube.com we keep the session
 * cookies (stored in app-private storage only) and close the screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalUiActions.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sign_in_title)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, stringResource(R.string.close)) } },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                CookieManager.getInstance().removeAllCookies(null)
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var finished = false

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (finished || url == null || !url.startsWith("https://music.youtube.com")) return
                            val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
                            if (cookies != null && cookies.contains("SAPISID")) {
                                finished = true
                                CookieManager.getInstance().flush()
                                container.account.save(cookies, null)
                                actions.showMessage(ctx.getString(R.string.signed_in))
                                onDone()
                            }
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
