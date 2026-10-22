package app.harmonic.music.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongVersionsTest {
    private fun key(title: String, artist: String) = SongVersions.key(Track(id = title, title = title, artist = artist))

    @Test
    fun versionsOfTheSameSongShareAKey() {
        val expected = "shape of you"
        assertEquals(expected, key("Shape of You (Acoustic)", "Ed Sheeran"))
        assertEquals(expected, key("Ed Sheeran - Shape of You [Official Video]", "EdSheeranVEVO"))
        assertEquals(expected, key("Shape of You - Radio Edit", "Ed Sheeran - Topic"))
        assertEquals(expected, key("Shape of You feat. Stormzy", "Ed Sheeran"))
        assertEquals("hotel california", key("Hotel California - 2013 Remaster", "Eagles"))
        assertEquals(key("Evidências (Ao Vivo)", "Chitãozinho & Xororó"), key("Chitãozinho & Xororó - Evidências", "Chitãozinho e Xororó"))
    }

    @Test
    fun differentSongsStayDifferent() {
        assertEquals("stay with me", key("Stay With Me", "Sam Smith"))
        assertNotEquals(key("Hello", "Adele"), key("Someone Like You", "Adele"))
    }
}
