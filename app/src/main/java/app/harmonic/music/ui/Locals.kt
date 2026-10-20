package app.harmonic.music.ui

import android.net.Uri
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import app.harmonic.music.AppContainer
import app.harmonic.music.data.model.Track
import app.harmonic.music.download.DownloadState

data class ExtraAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** App-wide UI actions, hosted once in [HarmonicRoot]. */
class UiActions(
    val openTrackMenu: (Track, List<ExtraAction>) -> Unit,
    val addToPlaylist: (List<Track>) -> Unit,
    val showMessage: (String) -> Unit,
    val navigate: (String) -> Unit,
)

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalUiActions = staticCompositionLocalOf<UiActions> { error("UiActions not provided") }
val LocalDownloadStates = compositionLocalOf<Map<String, DownloadState>> { emptyMap() }

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val JAM = "jam"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val LOGIN = "login"
    const val PLAYLIST = "playlist/{id}"
    const val ONLINE = "online?url={url}"
    const val ARTIST = "artist/{id}"
    const val ALBUM = "album/{id}"

    fun playlist(id: Long) = "playlist/$id"
    fun online(url: String) = "online?url=${Uri.encode(url)}"
    fun artist(id: String) = "artist/${Uri.encode(id)}"
    fun album(id: String) = "album/${Uri.encode(id)}"
}
