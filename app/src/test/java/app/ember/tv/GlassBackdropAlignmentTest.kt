package app.ember.tv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassBackdropAlignmentTest {
    @get:Rule val compose=createComposeRule()
    private fun scene()=Bitmap.createBitmap(300,100,Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas=android.graphics.Canvas(bitmap)
        for((index,color) in listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE).withIndex())
            canvas.drawRect(index*100f,0f,(index+1)*100f,100f,Paint().apply {this.color=color})
    }
    @Test fun liquidGlassSamplesEachCapsulesOwnPosition() = checkAlignment("liquid")
    @Test fun cachedGlassSamplesEachCapsulesOwnPosition() = checkAlignment("cached")

    private fun checkAlignment(method: String) {
        val image=scene()
        var gap by mutableStateOf(200)
        compose.setContent {
            val density=LocalDensity.current.density
            val view=LocalView.current
            var origin by remember {mutableStateOf(Offset.Zero)}
            SideEffect {val point=IntArray(2);view.getLocationOnScreen(point);origin=Offset(point[0].toFloat(),point[1].toFloat())}
            CompositionLocalProvider(LocalGlassBitmap provides image,LocalGlassOrigin provides origin,
                LocalBackdropArtwork provides ReadyArtwork(null,null,HeroFocus(.5f,.5f),HeroViewport(0f,0f,300*density,100*density,true)),
                LocalNight provides true,LocalRenderOptions provides RenderOptions(glass=true,quality="high",glassMethod=method)) {
                EmberTheme(0) {
                    val material=Modifier.navigationGlass(RoundedCornerShape(16.dp))
                    Row(Modifier.size(300.dp,100.dp)) {
                        Box(Modifier.size(100.dp,80.dp).then(material).testTag("left"))
                        Spacer(Modifier.width((gap-100).dp))
                        Box(Modifier.size(100.dp,80.dp).then(material).testTag("right"))
                    }
                }
            }
        }
        val left=compose.onNodeWithTag("left").captureToImage().toPixelMap()
        val right=compose.onNodeWithTag("right").captureToImage().toPixelMap()
        val a=left[left.width/2,left.height/2];val b=right[right.width/2,right.height/2]
        assertTrue("Left capsule must retain the red backdrop: $a",a.red>a.blue+.2f)
        assertTrue("Right capsule must retain the blue backdrop: $b",b.blue>b.red+.2f)
        compose.runOnIdle {gap=100}
        val moved=compose.onNodeWithTag("right").captureToImage().toPixelMap()
        val c=moved[moved.width/2,moved.height/2]
        assertTrue("Moving a capsule must update its sample: $c",c.green>c.blue+.1f && c.green>c.red+.1f)
        val stationary=compose.onNodeWithTag("left").captureToImage().toPixelMap()
        val d=stationary[stationary.width/2,stationary.height/2]
        assertTrue("Moving another capsule must not shift the stationary sample: $d",d.red>d.blue+.2f)
    }
}
