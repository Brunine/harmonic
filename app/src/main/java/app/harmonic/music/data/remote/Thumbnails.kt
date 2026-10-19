package app.harmonic.music.data.remote

import org.schabi.newpipe.extractor.Image

/**
 * Picks the smallest thumbnail that is still sharp enough, so list screens and the
 * notification never download full-resolution artwork.
 */
object Thumbnails {
    const val NORMAL_PX = 544
    const val DATA_SAVER_PX = 226

    private val sizedPattern = Regex("=w\\d+-h\\d+")
    private val squarePattern = Regex("=s\\d+")

    fun pick(images: List<Image>, videoId: String?, targetPx: Int): String? {
        if (images.isEmpty()) return videoId?.let { fallback(it) }
        val first = images.first().url
        // YouTube Music artwork is resizable through URL parameters: ask for exactly what we need.
        if (isResizable(first)) return resize(first, targetPx)

        val known = images.filter { it.height > 0 }
        val chosen = known.filter { it.height >= targetPx * 0.6 }.minByOrNull { it.height }
            ?: known.maxByOrNull { it.height }
            ?: images.last()
        return chosen.url
    }

    fun isResizable(url: String) =
        url.contains("googleusercontent.com") || url.contains("ggpht.com")

    fun resize(url: String, px: Int): String = url
        .replace(sizedPattern, "=w$px-h$px")
        .replace(squarePattern, "=s$px")

    /** mqdefault is 16:9 without letterbox bars, so it crops cleanly to a square. */
    fun fallback(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
}
