package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.data.*
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Backgrounds that blend: Shadow Ember's charcoal glow (#139), Live TV (#141), the Home hero (#142), row labels (#144). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageBackgroundBlendTest {
    @get:Rule val compose=createComposeRule()
    @get:Rule val temp=TemporaryFolder()

    private fun scene(accent:Int,content:@Composable ()->Unit):android.graphics.Bitmap {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(accent) {Box(Modifier.size(960.dp,540.dp).testTag("scene")) {content()}}
            }
        }
        return compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap()
    }

    @Test fun shadowEmbersGlowIsNeutralCharcoal() {
        val image=scene(ColorThemes.SHADOW_EMBER) {SearchBackdrop()}
        for((x,y) in listOf(120 to 12,600 to 200,900 to 520)) {
            val c=image.getPixel(x,y)
            val r=android.graphics.Color.red(c);val g=android.graphics.Color.green(c);val b=android.graphics.Color.blue(c)
            assertTrue("($x,$y) should be grey, was $r,$g,$b",maxOf(r,g,b)-minOf(r,g,b)<=10)
            assertTrue("($x,$y) should stay dark",maxOf(r,g,b)<90)
        }
        // The glow corner is a touch lighter than the far side of the page.
        assertTrue(android.graphics.Color.red(image.getPixel(120,12))>android.graphics.Color.red(image.getPixel(40,500)))
    }

    @Test fun theHomeHeroFadesIntoWhateverIsBelowIt() {
        // No artwork: the hero shows its Panel gradient. Over a red page, its bottom edge must be red.
        val image=scene(0) {
            Box(Modifier.fillMaxSize().background(Color.Red)) {ModernBackdrop(null,0f,heightFraction=.6f)}
        }
        val bottom=image.getPixel(480,(540*.6f).toInt()-2)
        assertTrue("the hero's last row lets the page show through",android.graphics.Color.red(bottom)>200 && android.graphics.Color.green(bottom)<60)
        val middle=image.getPixel(480,(540*.25f).toInt())
        assertTrue("higher up the hero is still drawn",android.graphics.Color.red(middle)<200)
        assertTrue(HeroFade.start(day=false)<HeroFade.start(day=true))
    }

    @Test fun liveTvDrawsNoPageFillOverTheGlow() {
        var cell=Color.Unspecified
        scene(0) {cell=LiveColors.Cell}
        assertTrue("guide cells let the glow through",cell.alpha<1f)
    }

    @Test fun addonNamesOnRowsCanBeTurnedOffAndStayOff() {
        val context=PersistenceContext(temp.newFolder())
        assertTrue(Prefs(context).rowAddonLabels)
        Prefs(context).rowAddonLabels=false
        assertFalse(Prefs(context).rowAddonLabels)
    }
}
