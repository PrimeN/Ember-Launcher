package app.ember.tv

import app.ember.tv.ui.GLASS_CROSSFADE_MS
import app.ember.tv.ui.GLASS_SAMPLE_GRACE_MS
import org.junit.Assert.assertTrue
import org.junit.Test

/** #40/#44: a new glass sample fades in quickly, well within the time an empty backdrop is kept. */
class GlassCrossfadeTest {
    @Test fun fadeIsShortAndShorterThanTheGrace() {
        assertTrue(GLASS_CROSSFADE_MS in 150..400)
        assertTrue(GLASS_CROSSFADE_MS < GLASS_SAMPLE_GRACE_MS)
    }
}
