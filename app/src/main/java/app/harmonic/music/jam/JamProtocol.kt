package app.harmonic.music.jam

import app.harmonic.music.data.model.Track
import org.json.JSONArray
import org.json.JSONObject

/**
 * Messages exchanged between Jam phones, as small JSON objects. Only metadata travels between
 * phones; every phone fetches audio itself.
 *
 * Host -> guests:  state, pong
 * Guests -> host:  add, move, remove, playAt, ctl, ping
 */
internal object JamProtocol {
    const val STATE = "state"
    const val ADD = "add"
    const val MOVE = "move"
    const val REMOVE = "remove"
    const val PLAY_AT = "playAt"
    const val CONTROL = "ctl"
    const val PING = "ping"
    const val PONG = "pong"

    /** Keeps every message well under Nearby's limit for byte payloads. */
    const val MAX_MESSAGE_CHARS = 28_000

    fun message(type: String, build: JSONObject.() -> Unit = {}) =
        JSONObject().put("t", type).apply(build)

    fun Track.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("ti", title)
        .put("ar", artist)
        .put("d", durationSec)
        .put("th", thumbnailUrl ?: "")
        .put("v", isVideo)

    fun trackFromJson(o: JSONObject): Track? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return Track(
            id = id,
            title = o.optString("ti"),
            artist = o.optString("ar"),
            durationSec = o.optLong("d", -1),
            thumbnailUrl = o.optString("th").takeIf { it.isNotEmpty() },
            isVideo = o.optBoolean("v"),
        )
    }

    fun tracksToJson(tracks: List<Track>) = JSONArray().apply { tracks.forEach { put(it.toJson()) } }

    fun tracksFromJson(array: JSONArray?): List<Track> {
        array ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::trackFromJson) }
    }
}
