package app.harmonic.music.jam

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Jam codes: a host gets a short code ("K7QX") that it adds to its Nearby name. The QR code
 * holds "harmonic://jam/K7QX"; scanning it (in Harmonic, or a camera app that opens links)
 * makes a guest join the host with that code, without picking it from a list.
 */
object JamQr {
    /** No 0/O, 1/I/L: easy to read aloud and to type. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 4
    private val PATTERN = Regex("^(?:harmonic://jam/)?([A-Z0-9]{$LENGTH})/?$", RegexOption.IGNORE_CASE)

    fun newCode(): String = buildString { repeat(LENGTH) { append(ALPHABET[Random.nextInt(ALPHABET.length)]) } }

    fun uri(code: String) = "harmonic://jam/$code"

    /** The code inside a scanned QR or link, or null when it isn't a Jam code. */
    fun parse(text: String?): String? = text?.trim()?.let { PATTERN.find(it)?.groupValues?.get(1)?.uppercase() }

    /** Dark modules on white with a quiet zone: high contrast scans well, even off a car screen. */
    fun bitmap(code: String, sizePx: Int, dark: Int = 0xFF0D0221.toInt(), light: Int = 0xFFFFFFFF.toInt()): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(uri(code), BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val row = y * sizePx
            for (x in 0 until sizePx) pixels[row + x] = if (matrix[x, y]) dark else light
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /** PNG bytes, used as album art on the car screen and in the notification. */
    fun png(code: String, sizePx: Int = 512): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap(code, sizePx).compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
}
