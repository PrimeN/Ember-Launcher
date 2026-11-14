package app.ember.tv

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.ember.tv.player.*
import app.ember.tv.ui.EmberTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PlayerActionMenuTest {
    @get:Rule val compose=createComposeRule()
    @Test fun exitConfirmationStartsOnKeepWatchingAndRunsOnlyTheChosenAction() {
        var stopped=0;var cancelled=0
        compose.setContent {EmberTheme(0) {
            PlayerActionMenu("Stop watching?",listOf(PlayerDialogAction("Keep watching"){},PlayerDialogAction("Stop",destructive=true){stopped++}),"Film · 10:00 of 90:00",{cancelled++})
        }}
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Keep watching").assertIsFocused()
        compose.onNodeWithContentDescription("Keep watching").performKeyInput {keyDown(Key.DirectionDown);keyUp(Key.DirectionDown)}
        compose.onNodeWithContentDescription("Stop").assertIsFocused().performKeyInput {keyDown(Key.Enter);keyUp(Key.Enter)}
        assertEquals(1,stopped);assertEquals(0,cancelled)
    }
    @Test fun queueSelectionsExposeTheirSelectedStateAndCloseUsesTheDismissAction() {
        var closed=0
        compose.setContent {EmberTheme(0) {
            PlayerActionMenu("Queue",listOf(PlayerDialogAction("Episode with a long descriptive title",selected=true){}),null,{closed++})
        }}
        compose.onNodeWithContentDescription("Episode with a long descriptive title").assertIsSelected()
        compose.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.OnClick) {it()}
        assertEquals(1,closed)
    }
}
