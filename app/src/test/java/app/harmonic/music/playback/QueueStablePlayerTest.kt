package app.harmonic.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueStablePlayerTest {

    /**
     * Every Player.Listener callback is a Java default method; a wrapper that doesn't override
     * one silently swallows that event. In 1.6.0 / 1.7.0 that hid "is playing" from the media
     * session (app player stuck on play, Android Auto "app unavailable").
     */
    @Test
    fun wrapperForwardsEveryListenerCallback() {
        val wrapper = Class.forName("app.harmonic.music.playback.QueueStablePlayer\$StableListener")
        val missing = Player.Listener::class.java.methods
            .filter { it.isDefault }
            .filter { m -> runCatching { wrapper.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure }
            .map { m -> m.name + m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName } }
        assertTrue("Callbacks not forwarded: $missing", missing.isEmpty())
    }
}
