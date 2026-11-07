package app.ember.tv

import app.ember.tv.ui.StartupHeroFocus
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** First load lands on the hero once (#100). */
class StartupHeroFocusTest {
    @After fun reset() = StartupHeroFocus.resetForTest()

    @Test fun theHeroMayTakeFocusOnceOnFirstLoad() {
        StartupHeroFocus.resetForTest()
        StartupHeroFocus.begin(1_000)
        assertTrue(StartupHeroFocus.waiting(2_000))
        StartupHeroFocus.claimed()
        assertFalse("only once", StartupHeroFocus.waiting(2_000))
    }

    @Test fun aKeyPressOrTheEndOfTheWindowKeepsYourFocus() {
        StartupHeroFocus.resetForTest()
        StartupHeroFocus.begin(1_000)
        assertFalse(StartupHeroFocus.waiting(1_000 + StartupHeroFocus.WINDOW_MS))
        StartupHeroFocus.keyPressed()
        assertFalse(StartupHeroFocus.waiting(2_000))
        StartupHeroFocus.resetForTest()
        StartupHeroFocus.begin(1_000); StartupHeroFocus.begin(9_000)
        assertTrue("a re-created activity doesn't move the start", StartupHeroFocus.waiting(5_000))
    }
}
