package app.harmonic.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.harmonic.music.HarmonicApp
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Serves album art as square JPEGs from a small on-disk cache.
 *
 * Android Auto and the media notification read artwork through content:// URIs; serving
 * them from here means each thumbnail is downloaded once, cropped from the 16:9 video frame
 * to a square "album cover", and shrunk to at most 512px.
 *
 * The provider is exported (the car host must read it), so it only fetches https images from
 * the music services' own image hosts and never arbitrary URLs.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val source = uri.getQueryParameter(PARAM) ?: throw FileNotFoundException("No url")
        val parsed = Uri.parse(source)
        if (parsed.scheme != "https" || !isAllowedHost(parsed.host)) {
            throw FileNotFoundException("Host not allowed")
        }

        val dir = File(ctx.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1(source) + ".jpg")
        if (!file.exists()) {
            synchronized(lockFor(file.name)) {
                if (!file.exists()) fetch(ctx, source, file)
            }
            trim(dir)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun fetch(ctx: Context, url: String, target: File) {
        val client = (ctx.applicationContext as HarmonicApp).container.okHttp
        val bytes = try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw FileNotFoundException("HTTP ${r.code}")
                r.body?.bytes() ?: throw FileNotFoundException("Empty body")
            }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw FileNotFoundException(e.message)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw FileNotFoundException("Undecodable image")

        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side,
        )
        val scaled = if (side > MAX_PX) Bitmap.createScaledBitmap(square, MAX_PX, MAX_PX, true) else square

        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(target)
        if (scaled !== square) scaled.recycle()
        if (square !== decoded) square.recycle()
        decoded.recycle()
    }

    /** Keeps the artwork cache bounded (~600 covers, roughly 20-30 MB). */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES + 100).forEach { it.delete() }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        private const val PARAM = "u"
        private const val MAX_PX = 512
        private const val MAX_FILES = 600
        private val locks = HashMap<String, Any>()

        private fun lockFor(key: String): Any = synchronized(locks) { locks.getOrPut(key) { Any() } }

        private fun isAllowedHost(host: String?): Boolean {
            host ?: return false
            return host == "i.ytimg.com" || host.endsWith(".ytimg.com") ||
                host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com") ||
                host.endsWith(".sndcdn.com") || // SoundCloud
                host.endsWith(".dzcdn.net") || // Deezer
                host.endsWith(".mzstatic.com") // Apple Music
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        fun uriFor(context: Context, url: String?): Uri? {
            if (url.isNullOrEmpty()) return null
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.artwork")
                .appendPath("art")
                .appendQueryParameter(PARAM, url)
                .build()
        }
    }
}
