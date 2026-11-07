package app.ember.tv

import app.ember.tv.state.StartupPresentation
import app.ember.tv.ui.InitialNavigationFocusState
import app.ember.tv.ui.MAX_STARTUP_FOCUS_FRAMES
import org.junit.Assert.*
import org.junit.Test

/** #33: the startup handoff and curtain are bounded. */
class StartupLimitsTest {
    @Test fun aTargetThatNeverTakesFocusIsGivenUpAfterTheFrameLimit() {
        val state = InitialNavigationFocusState()
        var asks = 0
        var frames = 0
        while (!state.established) {
            state.ensure(true, false) { asks++; false }
            if (!state.established && ++frames >= MAX_STARTUP_FOCUS_FRAMES) state.giveUp()
        }
        assertEquals(MAX_STARTUP_FOCUS_FRAMES, asks)
        assertTrue(state.ensure(true, false) { error("no more requests once given up") })
    }

    @Test fun curtainLimitIsShortAndRevealNeedsAFirstFrame() {
        assertTrue(StartupPresentation.MAX_CURTAIN_MS in 500L..3_000L)
        val p = StartupPresentation()
        p.revealForInput(); assertFalse(p.ready)
        p.preferencesRestored(); p.firstFrameShown(); p.revealForInput(); assertTrue(p.ready)
    }
}
