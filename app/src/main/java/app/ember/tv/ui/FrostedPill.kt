package app.ember.tv.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

val LocalGlassBitmap = compositionLocalOf<Bitmap?> { null }
val LocalGlassPreblurred = compositionLocalOf { true }
internal val LocalGlassOrigin = compositionLocalOf { Offset.Zero }
internal data class GlassArtwork(val bitmap: Bitmap? = null, val preblurred: Boolean = true, val origin: Offset = Offset.Zero)

/** Completed, app-owned samples only. Visible surfaces may still hold an evicted sample. */
internal object FrostCache {
    private val entries = object : android.util.LruCache<String, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val preparation = Mutex()
    @Volatile private var generation = 0
    fun clear() = synchronized(entries) { generation++; entries.evictAll() }
    fun get(key: String) = entries.get(key)
    fun put(key: String, value: Bitmap) { entries.put(key, value) }
    suspend fun prepare(source: Bitmap, mode: GlassMode): Bitmap? = withContext(Dispatchers.Default) {
        preparation.withLock {
            if (source.isRecycled || source.config == Bitmap.Config.HARDWARE || mode == GlassMode.TINT) return@withLock null
            val size = GlassPolicy.sampleSize(source.width, source.height) ?: return@withLock null
            val radius = GlassPolicy.radius(mode)
            val key = "glass:" + source.generationId + ":" + size + ":" + radius
            get(key)?.let { return@withLock it }
            val stamp = generation
            var small: Bitmap? = null
            var result: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                small = source.scale(size.first, size.second)
                val pixels = IntArray(size.first * size.second)
                small.getPixels(pixels, 0, size.first, 0, 0, size.first, size.second)
                result = Bitmap.createBitmap(FrostBlur.apply(pixels, size.first, size.second, radius), size.first, size.second, Bitmap.Config.ARGB_8888)
                currentCoroutineContext().ensureActive()
                val complete = result
                synchronized(entries) {
                    if (stamp != generation || RuntimeBudgets.pressured) { complete.recycle(); result = null }
                    else put(key, complete)
                }
                result
            } catch (cancelled: kotlinx.coroutines.CancellationException) { result?.recycle(); throw cancelled }
            catch (_: RuntimeException) { result?.recycle(); null }
            catch (_: OutOfMemoryError) { result?.recycle(); clear(); null }
            finally { if (small != null && small !== source) small.recycle() }
        }
    }
}

@Composable
internal fun rememberGlassBitmap(art: ReadyArtwork, options: RenderOptions, identity: Any?): GlassArtwork {
    val context = LocalContext.current
    val view = LocalView.current
    val lowRam = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true }
    val mode = GlassPolicy.mode(options, Build.VERSION.SDK_INT, lowRam, RuntimeBudgets.pressured)
    val source = (art.drawable as? BitmapDrawable)?.bitmap
    val point = remember { IntArray(2) }
    var origin by remember(view) { mutableStateOf(Offset.Zero) }
    SideEffect { view.getLocationOnScreen(point); origin = Offset(point[0].toFloat(), point[1].toFloat()) }
    var sample by remember(identity, source, mode) { mutableStateOf<Bitmap?>(null) }
    // Framing, layout and focus changes update draw transforms, not image processing.
    LaunchedEffect(identity, source, mode) { sample = if (source != null && mode != GlassMode.TINT) FrostCache.prepare(source, mode) else null }
    return GlassArtwork(if (mode == GlassMode.TINT) null else sample, origin = origin)
}

/** Round transport controls retain their own shape within either interface. */
internal fun navigationGlassShape(emberX: Boolean, shape: Shape? = null): Shape =
    shape ?: if (emberX) androidx.compose.foundation.shape.RoundedCornerShape(EmberXLayout.Radius.dp) else CircleShape

@Composable
internal fun Modifier.navigationGlass(shape: Shape? = null): Modifier = glassSurface(navigationGlassShape(isEmberX, shape))

/** The material belongs to the control, so backgrounds are never painted twice. */
@Composable
fun FrostedPill(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(navigationGlassShape(isEmberX)), content = content)
}
