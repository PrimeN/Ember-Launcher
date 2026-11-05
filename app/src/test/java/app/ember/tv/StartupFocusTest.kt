package app.ember.tv

import app.ember.tv.ui.*
import androidx.compose.ui.focus.FocusDirection
import org.junit.Assert.*
import org.junit.Test

class StartupFocusTest {
    @Test fun waitsForAnInteractiveWindowInsteadOfFocusingBehindAnOverlay() {
        val state=InitialNavigationFocusState();var calls=0
        assertFalse(state.ensure(false,false){calls++;true})
        assertFalse(state.established);assertEquals(0,calls)
        assertTrue(state.ensure(true,false){calls++;true})
        assertTrue(state.established);assertEquals(1,calls)
    }
    @Test fun successfulStartupDoesNotRefocusOnLaterWindowReturns() {
        val state=InitialNavigationFocusState();var calls=0
        assertTrue(state.ensure(true,false){calls++;true})
        repeat(3){assertTrue(state.ensure(true,false){calls++;true})}
        assertEquals(1,calls)
    }
    @Test fun anExistingPointerOrRestoredContentTargetIsPreserved() {
        val state=InitialNavigationFocusState();var calls=0
        assertTrue(state.ensure(true,true){calls++;true})
        assertTrue(state.established);assertEquals(0,calls)
    }
    @Test fun unattachedTargetsAndRejectedRequestsRemainRetryable() {
        val state=InitialNavigationFocusState()
        assertFalse(state.ensure(true,false){false});assertFalse(state.established)
        assertFalse(state.ensure(true,false){error("not attached")});assertFalse(state.established)
        assertTrue(state.ensure(true,false){true});assertTrue(state.established)
    }
    @Test fun redirectedEntryCannotRecursivelyRequestTheSameTarget() {
        val state=InitialNavigationFocusState();var nested=0
        assertTrue(state.ensure(true,false){
            assertFalse(state.ensure(true,false){nested++;true})
            true
        })
        assertEquals(0,nested);assertTrue(state.established)
    }
    @Test fun profileNavigationOwnersDoNotShareStartupCompletion() {
        val first=NavigationFocus();first.startup.ensure(true,false){true}
        val second=NavigationFocus()
        assertTrue(first.startup.established);assertFalse(second.startup.established)
    }
    @Test fun compactChoiceKeysFollowTheVisiblePreviousAndNextArrowsInBothDirections() {
        assertEquals(1,choiceStep(right=true,rtl=false));assertEquals(-1,choiceStep(right=false,rtl=false))
        assertEquals(-1,choiceStep(right=true,rtl=true));assertEquals(1,choiceStep(right=false,rtl=true))
    }
    @Test fun physicalTabNeighborsMatchTheirPositionInMirroredRows() {
        val tabs=listOf("search","home","discover")
        assertEquals("search",tabs[1+choiceStep(right=false,rtl=false)])
        assertEquals("discover",tabs[1+choiceStep(right=true,rtl=false)])
        assertEquals("discover",tabs[1+choiceStep(right=false,rtl=true)])
        assertEquals("search",tabs[1+choiceStep(right=true,rtl=true)])
    }
    @Test fun theSideRailExitUsesTheCorrectWindowEdgeInEachLayoutDirection() {
        assertTrue(returnsToNavigation(FocusDirection.Left,true,false))
        assertFalse(returnsToNavigation(FocusDirection.Right,true,false))
        assertTrue(returnsToNavigation(FocusDirection.Right,true,true))
        assertFalse(returnsToNavigation(FocusDirection.Left,true,true))
        assertTrue(returnsToNavigation(FocusDirection.Up,false,true))
        assertFalse(returnsToNavigation(FocusDirection.Down,true,true))
        assertFalse(returnsToNavigation(FocusDirection.Left,false,false))
    }
}
