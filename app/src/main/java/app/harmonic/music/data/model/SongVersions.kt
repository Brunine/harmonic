package app.harmonic.music.data.model

/**
 * Recognizes different versions of the same song ("Song (Acoustic)", "Song - Radio Edit",
 * "Artist - Song (Official Video)", "Song [Live]"...) so radio and recommendations play each
 * song once.
 */
object SongVersions {
    private val BRACKETS = Regex("\\s*[(\\[{][^)\\]}]*[)\\]}]")
    private val FEAT = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val DASH = Regex("\\s+[-–—|]\\s+")
    private val VERSION_WORDS = Regex(
        "\\b(acoustic|ac[uú]stico|radio|edit|version|vers[aã]o|live|ao vivo|en vivo|remix|mix|remaster(ed)?|" +
            "demo|instrumental|karaoke|sped up|slowed|reverb|nightcore|cover|extended|unplugged|lyrics?|letra|" +
            "official|video|audio|visuali[sz]er|clipe|mono|stereo|bonus|single|deluxe|\\d{4})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** A key that is the same for every version of a song. */
    fun key(track: Track): String {
        val artist = compact(track.artist.removeSuffix(" - Topic"))
        var title = track.title.replace(BRACKETS, " ").trim()
        val parts = title.split(DASH).filter { it.isNotBlank() }
        if (parts.size > 1) {
            // "Artist - Song": drop the artist part. "Song - Acoustic Version": drop the version.
            val first = compact(parts[0])
            val startsWithArtist = artist.length >= 3 && first.length >= 3 &&
                (first == artist || artist.contains(first) || first.contains(artist))
            val rest = if (startsWithArtist) {
                parts.drop(1)
            } else {
                parts
            }
            title = rest.filterIndexed { i, part -> i == 0 || !VERSION_WORDS.containsMatchIn(part) }.joinToString(" ")
        }
        title = title.replace(FEAT, "")
        val key = normalize(title)
        return key.ifEmpty { track.id }
    }

    /** First version of each song, skipping songs whose key is in [exclude]. Order is kept. */
    fun distinct(tracks: List<Track>, exclude: Set<String> = emptySet()): List<Track> {
        val seen = HashSet(exclude)
        return tracks.filter { seen.add(key(it)) }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(NON_WORD, " ").trim()

    /** Artist / channel names without spaces or a "VEVO" suffix, for loose comparison. */
    private fun compact(text: String): String =
        normalize(text).split(' ').filter { it !in CONNECTORS }.joinToString("")
            .removeSuffix("vevo").removeSuffix("official")

    /** "Chitãozinho & Xororó" and "Chitãozinho e Xororó" are the same artist. */
    private val CONNECTORS = setOf("e", "and", "y", "the", "x")
}
