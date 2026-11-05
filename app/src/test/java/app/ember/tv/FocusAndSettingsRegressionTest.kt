package app.ember.tv

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.node.ModifierNodeElement
import app.ember.tv.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercise the actual Compose modifier callbacks, not only the chosen tab's ID. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class FocusAndSettingsRegressionTest {
    private class Scope(direction: FocusDirection) {
        var canceled = false
        // The library's sealed Kotlin interface cannot be implemented in another module.
        // Its inline FocusDirection is an Int at this JVM boundary.
        val receiver = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FocusEnterExitScope::class.java)) { _, method, _ ->
            when {
                method.name.startsWith("getRequestedFocusDirection") -> direction.hashCode()
                method.name == "cancelFocusChange" -> { canceled = true; null }
                else -> null
            }
        } as FocusEnterExitScope
    }

    private fun callbacks(modifier: Modifier): Map<String, Any?> {
        val values = mutableMapOf<String, Any?>()
        val props = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(FocusProperties::class.java)) { _, method, args ->
            when {
                method.name.startsWith("set") -> {
                    values[method.name.removePrefix("set").replaceFirstChar { it.lowercase() }] = args[0]
                    null
                }
                else -> null
            }
        } as FocusProperties
        // Compose applies outer properties last. This catches a restorer overwriting onExit.
        modifier.foldOut(Unit) { element, _ ->
            val node = (element as? ModifierNodeElement<*>)?.create()
            if (node is FocusPropertiesModifierNode) node.applyFocusProperties(props)
        }
        return values
    }

    @Suppress("UNCHECKED_CAST")
    private fun invoke(values: Map<String, Any?>, name: String, direction: FocusDirection): Scope {
        val scope = Scope(direction)
        (values.getValue(name) as (FocusEnterExitScope) -> Unit)(scope.receiver)
        return scope
    }

    @Test fun pageExitRetainsItsNavigationCallbackAfterComposeModifiersAreApplied() {
        val requests = mutableListOf<FocusDirection>()
        val modifier = Modifier.sceneFocusGroup(FocusRequester(), restore = { false }, leave = {
            requests += it
            it == FocusDirection.Up
        })
        val callbacks = callbacks(modifier)
        assertFalse(invoke(callbacks, "onExit", FocusDirection.Up).canceled)
        assertFalse(invoke(callbacks, "onExit", FocusDirection.Down).canceled)
        assertEquals(listOf(FocusDirection.Up, FocusDirection.Down), requests)
    }

    @Test fun explicitSettingsDestinationIsNotReplacedByOldPageFocus() {
        var restorations = 0
        val callbacks = callbacks(Modifier.sceneFocusGroup(FocusRequester(), restore = { restorations++; true }, leave = { false }))
        assertFalse(invoke(callbacks, "onEnter", FocusDirection.Enter).canceled)
        assertEquals(0, restorations)
        assertFalse(invoke(callbacks, "onEnter", FocusDirection.Down).canceled)
        assertEquals(1, restorations)
    }

    @Test fun wholeNavbarEntryRedirectsUpButAllowsProfileAndHorizontalEntry() {
        var requests = 0
        val callbacks = callbacks(Modifier.navigationFocusGroup { requests++; true })
        assertFalse(invoke(callbacks, "onEnter", FocusDirection.Up).canceled)
        assertFalse(invoke(callbacks, "onEnter", FocusDirection.Right).canceled)
        assertFalse(invoke(callbacks, "onEnter", FocusDirection.Enter).canceled)
        assertEquals(1, requests)
    }

    @Test fun detailsExitRoutesOnlyToItsBackButtonAndRestoresContentOnDown() {
        var backRequests=0;var contentRequests=0
        val modifier=Modifier.detailsFocusGroup(FocusRequester(),restore={contentRequests++;true},back={backRequests++;true})
        val callbacks=callbacks(modifier)
        assertFalse(invoke(callbacks,"onExit",FocusDirection.Up).canceled)
        assertEquals(1,backRequests)
        invoke(callbacks,"onExit",FocusDirection.Left)
        assertEquals(1,backRequests)
        invoke(callbacks,"onEnter",FocusDirection.Down)
        assertEquals(1,contentRequests)
    }

    @Test fun guideRulerKeepsBothVerticalFocusRoutesAndLeavesHorizontalNavigationAlone() {
        var up=0;var down=0
        val modifier=Modifier.liveRulerFocusGroup(FocusRequester(),up={up++;true},down={down++;true})
        val callbacks=callbacks(modifier)
        assertFalse(invoke(callbacks,"onExit",FocusDirection.Up).canceled)
        assertFalse(invoke(callbacks,"onExit",FocusDirection.Down).canceled)
        assertFalse(invoke(callbacks,"onExit",FocusDirection.Left).canceled)
        assertEquals(1,up);assertEquals(1,down)
    }

    @Test fun normalHeaderReturnsToTheTopWhenItsActionsRegainFocus() {
        assertTrue(shouldRevealDetailsHeader(420, 500, 16, true, false, true))
        assertFalse(shouldRevealDetailsHeader(420, 500, 16, false, false, true))
        assertFalse(shouldRevealDetailsHeader(420, 500, 16, true, false, false))
        assertFalse(shouldRevealDetailsHeader(0, 500, 16, true, false, true))
    }

    @Test fun oversizedHeaderCanRevealItsTitleWithoutPullingActionsOffscreen() {
        assertFalse(shouldRevealDetailsHeader(700, 500, 16, true, false, true))
        assertTrue(shouldRevealDetailsHeader(700, 500, 16, false, true, true))
        assertTrue(shouldRevealDetailsHeader(484, 500, 16, true, false, true))
        assertFalse(shouldRevealDetailsHeader(485, 500, 16, true, false, true))
    }

    @Test fun searchHandoffOnlyFinishesAfterDestinationFocusOrPageFallback() = runBlocking {
        val target = SettingsTarget("density")
        assertFalse(target.ready); assertFalse(target.fulfilled)
        target.prepare(); assertTrue(target.claim()); assertFalse(target.claim())
        assertFalse(target.fulfilled)
        target.failed(); assertFalse(target.completion.await()); assertFalse(target.fulfilled)
        target.complete(); assertTrue(target.fulfilled)
        // Selecting the same destination again gets a new focus handoff.
        assertFalse(SettingsTarget("density").fulfilled)
    }

    @Test fun pageOnlySearchHitStillGetsAFocusHandoff() = runBlocking {
        val target = SettingsTarget(null)
        assertNull(target.id); assertFalse(target.fulfilled)
        target.prepare(); target.complete()
        assertTrue(target.completion.await()); assertTrue(target.fulfilled)
    }
    @Test fun theFirstDirectionalRootEntryTargetsTheActiveTabForEveryDpadDirection() {
        listOf(FocusDirection.Up,FocusDirection.Down,FocusDirection.Left,FocusDirection.Right).forEach {direction->
            val state=InitialNavigationFocusState();var requests=0
            val props=callbacks(Modifier.initialNavigationFocusGroup(state,{true},{false},{requests++;true}))
            assertFalse(invoke(props,"onEnter",direction).canceled)
            assertEquals(1,requests)
            invoke(props,"onEnter",direction)
            assertEquals(1,requests)
        }
    }
    @Test fun explicitEntryAndModalWindowsDoNotTriggerTheStartupRedirect() {
        val state=InitialNavigationFocusState();var requests=0;var ready=false
        val props=callbacks(Modifier.initialNavigationFocusGroup(state,{ready},{false},{requests++;true}))
        invoke(props,"onEnter",FocusDirection.Enter)
        invoke(props,"onEnter",FocusDirection.Down)
        assertEquals(0,requests);assertFalse(state.established)
        ready=true;invoke(props,"onEnter",FocusDirection.Right)
        assertEquals(1,requests);assertTrue(state.established)
    }
}
