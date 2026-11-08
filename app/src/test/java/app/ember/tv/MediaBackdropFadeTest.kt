package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ember.tv.data.MediaPageLayouts
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
class MediaBackdropFadeTest {
    @get:Rule val compose=createComposeRule()
    private fun show(layout:String,scale:Float=1f) {
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {MediaPageLayoutScope(layout) {
            Box(Modifier.size(960.dp,540.dp).background(Color.White)) {MediaPageRightFade(Color(0xFF18202C))}
        }}}
    }
    @Test fun cinemaArtworkFadesIntoTheThemeAroundThreeQuartersWithoutTouchingTheLeft() {
        show(MediaPageLayouts.EMBER_X)
        val pixels=compose.onRoot().captureToImage().toPixelMap()
        assertEquals(Color.White,pixels[100,200])
        assertEquals(Color(0xFF18202C),pixels[800,200])
        assertTrue(pixels[600,200].red<pixels[100,200].red)
    }
    @Test fun modernArtworkKeepsItsExistingPresentation() {
        show(MediaPageLayouts.MODERN)
        val pixels=compose.onRoot().captureToImage().toPixelMap()
        assertEquals(Color.White,pixels[800,200])
    }
    @Test fun largeTextSingleColumnFallbackDoesNotReceiveASidePanelFade() {
        show(MediaPageLayouts.EMBER_X,1.5f)
        assertEquals(Color.White,compose.onRoot().captureToImage().toPixelMap()[800,200])
    }
}
