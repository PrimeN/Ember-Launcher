package app.ember.tv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class PlayerSettingsModeTest {
    @get:Rule val compose = createComposeRule()

    @Before fun clean() = SettingsOptionIndex.clearForTest()

    @Test fun simpleShowsAFewEverydayOptionsAndAdvancedShowsThemAll() {
        // #387
        assertEquals(listOf(PLAYER_MODE_KEY, "player-choice", "resume", "next-episode"), playerKeys(PLAYER_KEYS, advanced = false))
        assertEquals(listOf(PLAYER_MODE_KEY, "audio-timing"), playerKeys(AUDIO_VIDEO_KEYS, advanced = false))
        assertEquals(PLAYER_KEYS, playerKeys(PLAYER_KEYS, advanced = true))
        // A search hit on an advanced option opens the whole page, so the hit is there to land on.
        assertEquals(AUDIO_VIDEO_KEYS, playerKeys(AUDIO_VIDEO_KEYS, advanced = false, target = "decoding"))
        assertEquals(listOf(PLAYER_MODE_KEY, "audio-timing"), playerKeys(AUDIO_VIDEO_KEYS, advanced = false, target = "audio-timing"))
    }

    @Test fun choiceValuesSitAtTheRightEdgeLikeTheSwitches() {
        // #386: a choice row is as wide as a toggle row, so its value and arrow line up with the switches.
        compose.setContent {
            EmberTheme(0) {
                Column(Modifier.width(900.dp)) {
                    Section("Fixture") {
                        Box(Modifier.testTag("toggle")) { ToggleRow("A switch", null, true) {} }
                        Box(Modifier.testTag("choice")) { Choice(listOf("a" to "First", "b" to "Second"), "a", "A choice") {} }
                    }
                }
            }
        }
        val toggle = compose.onNodeWithTag("toggle").fetchSemanticsNode().boundsInRoot
        val choice = compose.onNodeWithTag("choice").fetchSemanticsNode().boundsInRoot
        assertEquals(toggle.right, choice.right, 1f)
        // #402: the value itself sits at the right, not in the middle of the row.
        val value = compose.onNodeWithText("First", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("value ends at ${value.right}, row at ${choice.right}", choice.right - value.right < 80f)
    }

    @Test fun aResetRowIsReachedWithTheRemoteAndSaysItRan() {
        // #386: resets are full-width rows between the others, not small chips the d-pad skips past.
        var reset = 0
        compose.setContent {
            EmberTheme(0) {
                Column(Modifier.width(900.dp)) {
                    Section("Fixture") {
                        Box(Modifier.testTag("above")) { ToggleRow("Remember what works", "Learns", true) {} }
                        Box(Modifier.testTag("reset")) { SettingActionRow("Reset compatibility and decoder history", "Forget") { reset++ } }
                        Box(Modifier.testTag("below")) { Choice(listOf("a" to "First"), "a", "Below") {} }
                    }
                }
            }
        }
        compose.onNodeWithText("Remember what works").requestFocus()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Reset compatibility and decoder history").assertIsFocused()
        compose.onNodeWithText("Reset compatibility and decoder history").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, reset) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Below").assertIsFocused()
    }

    @Test fun aLongExplanationOpensFromTheInfoButton() {
        compose.setContent {
            EmberTheme(0) {
                Section("Fixture", "Short.", info = "The long explanation.") { ToggleRow("Option", null, false) {} }
            }
        }
        compose.onNodeWithText("The long explanation.").assertDoesNotExist()
        compose.onNodeWithContentDescription("About Fixture").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("The long explanation.").assertExists()
        compose.onNodeWithText("Got it").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("The long explanation.").assertDoesNotExist()
    }
}
