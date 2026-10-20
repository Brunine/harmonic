package app.harmonic.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import app.harmonic.music.R
import app.harmonic.music.playback.PlaybackService
import app.harmonic.music.ui.MainActivity
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home screen widget: cover, song, artist, previous / play-pause / next. */
class PlayerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Newly placed widget, or the launcher asking again: show the last known song.
        PlayerWidget.refresh(context)
    }
}

/**
 * Draws the widget. The playback service calls [update] when the song or play state changes;
 * nothing runs on a timer, so the widget costs no battery while music is paused.
 */
object PlayerWidget {
    data class State(
        val title: String,
        val artist: String,
        val thumbnailUrl: String?,
        val isPlaying: Boolean,
    )

    private const val PREFS = "widget"
    private const val COVER_PX = 192

    private var coverUrl: String? = null
    private var cover: Bitmap? = null
    private var coverJob: Job? = null

    fun update(context: Context, state: State?, scope: CoroutineScope) {
        val app = context.applicationContext
        save(app, state)
        val ids = widgetIds(app)
        if (ids.isEmpty()) return
        val url = state?.thumbnailUrl
        if (url != coverUrl) {
            coverUrl = url
            cover = null
            coverJob?.cancel()
            if (url != null) {
                coverJob = scope.launch {
                    val bitmap = loadCover(app, url) ?: return@launch
                    if (coverUrl == url) {
                        cover = bitmap
                        push(app, ids, state)
                    }
                }
            }
        }
        push(app, ids, state)
    }

    /** Redraw from what was saved last (e.g. after a reboot or when the widget is added). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val ids = widgetIds(app)
        if (ids.isNotEmpty()) push(app, ids, load(app))
    }

    private fun push(context: Context, ids: IntArray, state: State?) {
        AppWidgetManager.getInstance(context).updateAppWidget(ids, views(context, state))
    }

    private fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))

    private fun views(context: Context, state: State?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_player)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_info, openApp)
        views.setOnClickPendingIntent(R.id.widget_cover, openApp)

        if (state == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_idle))
            views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
            views.setImageViewResource(R.id.widget_play, R.drawable.ic_widget_play)
            // Nothing to resume: the play button opens the app.
            views.setOnClickPendingIntent(R.id.widget_play, openApp)
            views.setViewVisibility(R.id.widget_prev, View.INVISIBLE)
            views.setViewVisibility(R.id.widget_next, View.INVISIBLE)
            return views
        }

        views.setTextViewText(R.id.widget_title, state.title)
        views.setTextViewText(R.id.widget_artist, state.artist)
        val bitmap = cover
        if (bitmap != null && coverUrl == state.thumbnailUrl) {
            views.setImageViewBitmap(R.id.widget_cover, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
        }
        views.setImageViewResource(R.id.widget_play, if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setContentDescription(
            R.id.widget_play,
            context.getString(if (state.isPlaying) R.string.pause else R.string.play),
        )
        views.setViewVisibility(R.id.widget_prev, View.VISIBLE)
        views.setViewVisibility(R.id.widget_next, View.VISIBLE)
        views.setOnClickPendingIntent(
            R.id.widget_play,
            if (state.isPlaying) {
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false)
            } else {
                // Starting playback may start the service: it must be allowed to go foreground.
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true)
            },
        )
        views.setOnClickPendingIntent(R.id.widget_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, foreground = false))
        views.setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT, foreground = false))
        return views
    }

    /** The same kind of media button intent Media3's own notification buttons send. */
    private fun mediaButton(context: Context, keyCode: Int, foreground: Boolean): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (foreground) {
            PendingIntent.getForegroundService(context, keyCode, intent, flags)
        } else {
            PendingIntent.getService(context, keyCode, intent, flags)
        }
    }

    private suspend fun loadCover(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(COVER_PX)
            .scale(Scale.FILL)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        withContext(Dispatchers.Default) { rounded(result.drawable.toBitmap()) }
    }.getOrNull()

    /** Center-cropped square with rounded corners (16:9 video thumbnails become square covers). */
    private fun rounded(source: Bitmap): Bitmap {
        val size = COVER_PX
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = size / minOf(source.width, source.height).toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - source.width * scale) / 2f, (size - source.height * scale) / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        val radius = size * 0.14f
        Canvas(output).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return output
    }

    private fun save(context: Context, state: State?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (state == null) {
            prefs.clear()
        } else {
            prefs.putString("title", state.title)
                .putString("artist", state.artist)
                .putString("thumb", state.thumbnailUrl)
                .putBoolean("playing", state.isPlaying)
        }
        prefs.apply()
    }

    private fun load(context: Context): State? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString("title", null) ?: return null
        // After the app was closed nothing is playing any more.
        return State(title, prefs.getString("artist", "").orEmpty(), prefs.getString("thumb", null), isPlaying = false)
    }
}
