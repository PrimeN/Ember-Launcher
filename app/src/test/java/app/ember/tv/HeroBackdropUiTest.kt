package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeroBackdropUiTest {
    @get:Rule val compose=createComposeRule()
    @After fun reset() {Ui.day=false}

    @Test fun dayHeroPreservesArtworkColourAndUsesNeutralTextShading() {
        Ui.day=true
        compose.setContent {
            EmberTheme(0) {
                Box(Modifier.size(300.dp,200.dp).background(Color.Red).testTag("art")) {
                    HeroArtworkShade(Color.White,1f)
                }
            }
        }
        val pixels=compose.onNodeWithTag("art").captureToImage().toPixelMap()
        val clear=pixels[pixels.width*9/10,pixels.height/2]
        assertTrue("Artwork must not get a white veil: $clear",clear.red>.98f && clear.green<.02f && clear.blue<.02f)
        val textEdge=pixels[pixels.width/10,pixels.height/2]
        assertTrue("Text shade must be neutral/dark rather than white: $textEdge",textEdge.green<.1f && textEdge.blue<.1f)
        val lowerArt=pixels[pixels.width*9/10,pixels.height*85/100]
        assertTrue("Day fade must not wash out the lower image: $lowerArt",lowerArt.green<.02f && lowerArt.blue<.02f)
        val bottom=pixels[pixels.width*9/10,pixels.height-1]
        assertTrue("The bottom must still join the light page",bottom.green>.9f && bottom.blue>.9f)
    }

    @Test fun pageBackdropUsesFullPageCoordinatesBehindTheNavbarAndClearsOnExit() {
        val state=PageBackdropState()
        var visible by mutableStateOf(true)
        var presented:ReadyArtwork?=null
        var density=1f
        compose.setContent {
            density=LocalDensity.current.density
            EmberTheme(0) {
                CompositionLocalProvider(LocalArtworkPresented provides {presented=it}) {
                    Box(Modifier.size(300.dp,400.dp)) {
                        PageBackdropHost(state) {
                            Column {
                                Spacer(Modifier.height(64.dp))
                                if(visible)Box(Modifier.fillMaxSize().testTag("content")) {
                                    val host=LocalPageBackdrop.current!!
                                    SideEffect {host.selection=PageBackdropSelection(null,0f,1f)}
                                    DisposableEffect(host) {onDispose {host.selection=null}}
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            val viewport=presented!!.viewport!!
            assertEquals(0f,viewport.top,.01f)
            assertEquals(400f*density,viewport.height,.01f)
            assertNotNull(state.selection)
            visible=false
        }
        compose.runOnIdle {assertNull(state.selection)}
    }
}
