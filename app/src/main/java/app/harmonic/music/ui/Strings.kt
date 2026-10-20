package app.harmonic.music.ui

import android.content.res.Resources
import app.harmonic.music.R

/** "Downloading 12 songs (2 SoundCloud tracks can't be downloaded)", translated. */
fun Resources.downloadMessage(count: Int, skipped: Int): String {
    if (count <= 0 && skipped > 0) return getString(R.string.soundcloud_no_download)
    val base = getQuantityString(R.plurals.downloading_songs, count, count)
    return if (skipped > 0) base + getQuantityString(R.plurals.soundcloud_skipped, skipped, skipped) else base
}

/** Translated message for a failed playlist/album import or save. */
fun Resources.importError(error: Throwable): String = when (error.message) {
    LINK_IS_SONG -> getString(R.string.link_is_song)
    LINK_UNSUPPORTED -> getString(R.string.unsupported_link)
    else -> getString(R.string.import_failed)
}
