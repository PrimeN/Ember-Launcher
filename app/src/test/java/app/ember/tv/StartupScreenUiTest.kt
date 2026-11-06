package app.ember.tv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ember.tv.state.StartupPresentation
import app.ember.tv.state.StartupStage
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupScreenUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun statusAnnouncementsDescribeTheActualMilestoneWithoutAPercentage() {
        var stage by mutableStateOf(StartupStage.PREPARING)
        compose.setContent {EmberTheme(0) {Box(Modifier.size(800.dp,450.dp)) {StartupScreen(stage,RenderOptions(device="low"),true)}}}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Stage 2 of 4"))
            .assertContentDescriptionEquals("Starting Ember. Convincing the charcoal… Preparing your Home")
        compose.runOnIdle {stage=StartupStage.IGNITING}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Stage 3 of 4"))
            .assertContentDescriptionEquals("Starting Ember. Finding the spark… Getting your controls ready")
    }

    @Test fun completedStartupDoesNotShowTheCurtainOrWaitForArtwork() {
        val startup=StartupPresentation()
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                EmberTheme(0) {Box(Modifier.size(800.dp,450.dp)) {StartupCurtain(startup)}}
            }
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Starting Ember")).assertExists()
        compose.runOnIdle {startup.firstFrameShown();startup.controlsReady()}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Starting Ember")).assertDoesNotExist()
        compose.runOnIdle {startup.preferencesRestored();startup.firstFrameShown()}
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Starting Ember")).assertDoesNotExist()
    }

    @Test fun firstDirectionStillMovesFromHomeWhileTheCurtainIsPresent() {
        val nav=NavigationFocus()
        val startup=StartupPresentation()
        var selected by mutableStateOf("home")
        compose.mainClock.autoAdvance=false
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                EmberTheme(0) {
                    Box(Modifier.fillMaxSize().startupInput(startup)) {
                        Row(Modifier.fillMaxWidth().initialNavigationFocus(nav,selected,true)
                            .then(if(!startup.ready)Modifier.clearAndSetSemantics {} else Modifier)) {
                            val tabs=listOf("search","home","live","apps")
                            for((index,id) in tabs.withIndex())Box(Modifier.size(60.dp).navigationTabTarget(nav,id).focusProperties {
                                right=tabs.getOrNull(index+1)?.let(nav::tab) ?: FocusRequester.Cancel
                            }.openNavigationTab(id,selected==id){selected=id}.testTag(id).focusable())
                        }
                        StartupCurtain(startup)
                    }
                }
            }
        }
        compose.onRoot().performKeyInput {keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)}
        compose.runOnIdle {startup.firstFrameShown()}
        compose.mainClock.autoAdvance=true
        compose.onNodeWithTag("live",useUnmergedTree=true).assertIsFocused()
        assertEquals("live",selected)
        compose.runOnIdle {startup.controlsReady()}
        compose.onNodeWithTag("live").assertIsFocused()
    }

    @Test fun heldConfirmationCannotActivateAnActionHiddenUnderTheCurtain() {
        val startup=StartupPresentation()
        val focus=FocusRequester()
        var activated=0
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                EmberTheme(0) {
                    Box(Modifier.fillMaxSize().startupInput(startup)) {
                        Focusable({activated++},modifier=Modifier.size(120.dp,60.dp).focusRequester(focus).testTag("action")) {
                            androidx.tv.material3.Text("Watch now")
                        }
                        StartupCurtain(startup)
                    }
                }
            }
        }
        compose.runOnIdle {focus.requestFocus();startup.firstFrameShown()}
        compose.onRoot().performKeyInput {keyDown(Key.DirectionCenter)}
        compose.runOnIdle {assertTrue(startup.ready);assertEquals(0,activated)}
        compose.onRoot().performKeyInput {keyUp(Key.DirectionCenter)}
        compose.runOnIdle {assertEquals(0,activated)}
        compose.onRoot().performKeyInput {keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter)}
        compose.runOnIdle {assertEquals(1,activated)}
    }

    @Test fun largeTextAndACompactViewportKeepTheStatusOnScreen() {
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,1.5f)) {
                EmberTheme(0) {Box(Modifier.size(640.dp,360.dp).testTag("viewport")) {
                    StartupScreen(StartupStage.RESTORING,RenderOptions(device="low"),true)
                }}
            }
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,"Starting Ember")).assertIsDisplayed()
        compose.onNodeWithText("Rounding up the kindling…",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("Restoring your preferences",useUnmergedTree=true).assertIsDisplayed()
    }
    @Test fun theFirstTouchRevealsHomeWithoutClickingAnUnseenButton() {
        val startup=StartupPresentation()
        val focus=FocusRequester()
        var activated=0
        compose.setContent {
            EmberTheme(0) {
                Box(Modifier.fillMaxSize().startupInput(startup)) {
                    Box(Modifier.size(120.dp,60.dp).focusRequester(focus).testTag("action").clickable {activated++}) {
                        androidx.tv.material3.Text("Watch now")
                    }
                }
            }
        }
        compose.runOnIdle {focus.requestFocus();startup.firstFrameShown()}
        compose.onNodeWithTag("action").performTouchInput {click()}
        compose.runOnIdle {assertTrue(startup.ready);assertEquals(0,activated)}
        compose.onNodeWithTag("action").performTouchInput {click()}
        compose.runOnIdle {assertEquals(1,activated)}
    }
    @Test fun theTvDesignHasACharcoalBackgroundAndWarmProgressWithoutNumericPercentages() {
        compose.setContent {
            EmberTheme(0) {Box(Modifier.fillMaxSize().testTag("preview")) {
                StartupScreen(StartupStage.IGNITING,RenderOptions(quality="high"),true)
            }}
        }
        val screen=compose.onNodeWithTag("preview").captureToImage()
        val pixels=screen.toPixelMap()
        val corner=pixels[0,0]
        assertTrue(corner.red<.12f && corner.green<.12f && corner.blue<.12f)
        var warm=0
        for(y in 0 until pixels.height step 3)for(x in 0 until pixels.width step 3) {
            val c=pixels[x,y]
            if(c.red>.5f && c.green>.15f && c.red>c.blue*1.5f)warm++
        }
        assertTrue("The progress ring must have a visible ember/amber accent",warm>100)
        compose.onAllNodesWithText("%",substring=true,useUnmergedTree=true).assertCountEquals(0)
        // A normal build report, never a tracked design asset or a dependency of the app.
        val output=java.io.File("build/reports/ui/coal-to-glow.png")
        output.parentFile?.mkdirs()
        output.outputStream().use {screen.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
}
