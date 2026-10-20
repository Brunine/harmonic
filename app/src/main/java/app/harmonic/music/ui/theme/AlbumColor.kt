package app.harmonic.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Key colour of the current song's cover, for the "Current song's cover" palette. Uses a tiny
 * 96px copy of the artwork (usually already in Coil's cache), so it costs almost nothing.
 */
@Composable
fun rememberAlbumSeed(artworkUrl: String?, enabled: Boolean): Int? {
    val context = LocalContext.current
    var seed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(artworkUrl, enabled) {
        if (!enabled || artworkUrl == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(96)
            .allowHardware(false)
            .build()
        val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
            ?: return@LaunchedEffect
        seed = withContext(Dispatchers.Default) {
            val palette = Palette.from(drawable.toBitmap()).generate()
            palette.vibrantSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: palette.dominantSwatch?.rgb
        }
    }
    return seed
}
