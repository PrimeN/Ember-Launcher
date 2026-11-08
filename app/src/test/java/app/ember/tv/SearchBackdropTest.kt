package app.ember.tv

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
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

/** Search's theme-coloured background (#120). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchBackdropTest {
    @get:Rule val compose=createComposeRule()

    private fun render(accent:Int):android.graphics.Bitmap {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(accent) {Box(Modifier.size(960.dp,540.dp)) {SearchBackdrop()}}
            }
        }
        return compose.onNodeWithTag("search_backdrop").captureToImage().asAndroidBitmap()
    }

    @Test fun theGlowFollowsTheAccentAndFadesTowardsThePage() {
        val ember=render(0)
        val corner=ember.getPixel(120,12)
        val middleLeft=ember.getPixel(40,500)
        // Ember's orange: warm in the glow corner, close to the plain page further away.
        assertTrue(android.graphics.Color.red(corner)>android.graphics.Color.blue(corner)+20)
        assertTrue(android.graphics.Color.red(corner)>android.graphics.Color.red(middleLeft))
        val file=java.io.File("build/reports/ui/search-backdrop.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {ember.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun aBlueAccentGivesABlueGlow() {
        val blue=render(2).getPixel(120,12)
        assertTrue(android.graphics.Color.blue(blue)>android.graphics.Color.red(blue)+20)
    }

    @Test fun theNeighbourHueIsCloseAndTheStrengthSuitsEachTheme() {
        val hsv=FloatArray(3)
        android.graphics.Color.colorToHSV(SearchGlow.neighbour(Color(0xFFFF7A2F)).toArgb(),hsv)
        val base=FloatArray(3).also {android.graphics.Color.colorToHSV(Color(0xFFFF7A2F).toArgb(),it)}
        assertEquals((base[0]+38f)%360f,hsv[0],1.5f)
        assertTrue(SearchGlow.strength(day=true,oled=false)<SearchGlow.strength(day=false,oled=true))
        assertTrue(SearchGlow.strength(day=false,oled=true)<SearchGlow.strength(day=false,oled=false))
    }

    @Test fun searchNoLongerUsesTheFocusedTitlesArtwork() {
        val main=java.io.File("src/main/java/app/ember/tv/MainActivity.kt").readText()
        assertTrue(main.contains("enabled = vm.details == null && tab != NavTabs.SEARCH"))
        assertTrue(main.contains("if (current == NavTabs.SEARCH) app.ember.tv.ui.SearchBackdrop()"))
    }
}
