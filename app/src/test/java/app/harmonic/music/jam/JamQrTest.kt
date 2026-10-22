package app.harmonic.music.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamQrTest {
    @Test
    fun parsesCodesAndLinks() {
        assertEquals("K7QX", JamQr.parse("harmonic://jam/K7QX"))
        assertEquals("K7QX", JamQr.parse("harmonic://jam/k7qx/"))
        assertEquals("K7QX", JamQr.parse(" K7QX "))
        assertNull(JamQr.parse("https://example.com/K7QX"))
        assertNull(JamQr.parse(null))
    }

    @Test
    fun newCodesRoundTrip() {
        repeat(50) {
            val code = JamQr.newCode()
            assertEquals(code, JamQr.parse(JamQr.uri(code)))
        }
    }
}
