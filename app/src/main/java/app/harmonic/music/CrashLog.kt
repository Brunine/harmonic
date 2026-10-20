package app.harmonic.music

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a report when the app crashes, so it can be shared from Settings for fixing.
 * Reports stay on the phone (app-private storage) until the user shares or deletes them.
 */
object CrashLog {
    private const val KEEP = 5

    private fun dir(context: Context) = File(context.filesDir, "crashes").apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val time = Date()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Harmonic ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(time)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(stack)
        }
        val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(time) + ".txt"
        File(dir(context), name).writeText(report)
        reports(context).drop(KEEP).forEach { it.delete() }
    }

    /** Newest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    fun latest(context: Context): String? = reports(context).firstOrNull()?.readText()

    fun clear(context: Context) = reports(context).forEach { it.delete() }
}
