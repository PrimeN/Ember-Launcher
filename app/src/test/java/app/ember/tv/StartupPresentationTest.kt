package app.ember.tv

import app.ember.tv.state.StartupPresentation
import app.ember.tv.state.StartupStage
import app.ember.tv.ui.RenderOptions
import app.ember.tv.ui.startupMotion
import org.junit.Assert.*
import org.junit.Test

class StartupPresentationTest {
    @Test fun onlyRealLocalMilestonesAdvanceTheStage() {
        val startup=StartupPresentation()
        assertEquals(StartupStage.RESTORING,startup.stage)
        startup.preferencesRestored()
        assertEquals(StartupStage.PREPARING,startup.stage)
        assertFalse(startup.ready)
        startup.firstFrameShown()
        assertEquals(StartupStage.IGNITING,startup.stage)
        startup.controlsReady()
        assertEquals(StartupStage.READY,startup.stage)
    }
    @Test fun aReadyControlDoesNotSkipTheFirstActualLayout() {
        val startup=StartupPresentation()
        startup.controlsReady();startup.revealForInput()
        assertFalse(startup.ready)
        startup.firstFrameShown();startup.controlsReady()
        assertTrue(startup.ready)
    }
    @Test fun firstInputCanRevealTheShellWithoutAnArtworkOrNetworkResult() {
        val startup=StartupPresentation()
        startup.preferencesRestored();startup.firstFrameShown()
        startup.revealForInput()
        assertTrue(startup.ready)
    }
    @Test fun profileChangesAndWindowReturnsCannotReopenACompletedPresentation() {
        val startup=StartupPresentation()
        startup.firstFrameShown();startup.controlsReady()
        repeat(5){startup.preferencesRestored();startup.firstFrameShown();startup.controlsReady()}
        assertTrue(startup.ready)
        assertEquals(StartupStage.RESTORING,StartupPresentation().stage)
    }
    @Test fun lowPowerReducedMotionAndDisabledAnimationAvoidThePulse() {
        val high=RenderOptions(quality="high")
        assertTrue(startupMotion(high,false))
        assertFalse(startupMotion(high,true))
        assertFalse(startupMotion(high.copy(device="low"),false))
        assertFalse(startupMotion(high.copy(custom=app.ember.tv.data.CustomPerformance(animations=false)),false))
    }
}
