package app.ember.tv

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassMaterialRenderingTest {
    @get:Rule val compose = createComposeRule()
    @After fun clean() { FrostCache.clear() }

    @Test fun refractionShaderCompilesAndAcceptsRoundedSurfaceUpdates() {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val lens = Api33GlassLens()
        lens.source(BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        assertSame(lens.configure(160f, 48f, 24f, 1f), lens.configure(320f, 96f, 48f, 2f))
    }
    @Test fun repeatedPreparationReusesSamplesAndNeverRecyclesOriginalArtwork() = runBlocking {
        val source = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val first = FrostCache.prepare(source, GlassMode.FROSTED)!!
        assertSame(first, FrostCache.prepare(source, GlassMode.FROSTED))
        assertEquals(384, first.width); assertEquals(216, first.height)
        assertFalse(source.isRecycled)
        FrostCache.clear()
        assertNotSame(first, FrostCache.prepare(source, GlassMode.FROSTED))
        assertFalse(first.isRecycled); assertFalse(source.isRecycled)
    }
    @Test fun lowModeDoesNotPrepareAnImageAndSourceEditsInvalidateCachedBlur() = runBlocking {
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        assertNull(FrostCache.prepare(source, GlassMode.TINT))
        source.eraseColor(android.graphics.Color.RED)
        val first = FrostCache.prepare(source, GlassMode.FROSTED)!!
        source.eraseColor(android.graphics.Color.BLUE)
        val second = FrostCache.prepare(source, GlassMode.FROSTED)!!
        assertNotSame(first, second)
        assertEquals(android.graphics.Color.RED, first.getPixel(16, 16))
        assertEquals(android.graphics.Color.BLUE, second.getPixel(16, 16))
    }
    @Test fun softwareFallbackKeepsBackgroundAlignedAcrossSeparateSurfaces() {
        val scene = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(scene).apply {
            drawColor(android.graphics.Color.RED)
            drawRect(100f, 0f, 200f, 100f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
        }
        fun draw(offset:Float):Bitmap {
            val target = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val path = android.graphics.Path().apply { addRoundRect(0f, 0f, 100f, 100f, 20f, 20f, android.graphics.Path.Direction.CW) }
            GlassPainter().draw(android.graphics.Canvas(target), path, scene, offset, 0f, 200f, 100f, 100f, 100f, 20f, true, 1f)
            return target
        }
        assertEquals(android.graphics.Color.RED, draw(0f).getPixel(50, 50))
        assertEquals(android.graphics.Color.BLUE, draw(-100f).getPixel(50, 50))
        assertEquals(0, draw(0f).getPixel(0, 0))
        assertTrue(GlassPainter.brightness(scene,HeroProjection(-100f,0f,200f,100f),100f,100f)<.1f)
    }
    @Test fun glassSwitchesPreserveForegroundAndRemoteFocus() {
        var mode by mutableStateOf("cached")
        val focus = FocusRequester()
        compose.setContent {
            CompositionLocalProvider(LocalRenderOptions provides RenderOptions(glass = true, quality = "high", glassMethod = mode)) {
                EmberTheme(0) {
                    Row(Modifier.size(240.dp, 60.dp).navigationGlass(RoundedCornerShape(30.dp)).testTag("glass")) {
                        Box(Modifier.size(100.dp, 50.dp).focusRequester(focus).testTag("button").focusable()) {
                            androidx.tv.material3.Text("Watch now")
                        }
                    }
                }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        for (value in listOf("tint", "liquid", "cached")) {
            compose.runOnIdle { mode = value }
            compose.onNodeWithText("Watch now").assertExists()
            compose.onNodeWithTag("button").assertIsFocused()
        }
    }
}
