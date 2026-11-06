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
import app.ember.tv.data.ArtworkDiskCache
import app.ember.tv.data.ArtworkCaches
import app.ember.tv.data.CacheKind
import app.ember.tv.data.CachePolicy

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
    suspend fun prepare(source: Bitmap, mode: GlassMode, disk:ArtworkDiskCache?=null,realm:String="public",
        valid:suspend ()->Boolean={true},retain:Boolean=true,blur:(IntArray,Int,Int,Int)->IntArray=FrostBlur::apply): Bitmap? = withContext(Dispatchers.Default) {
        preparation.withLock {
            if (source.isRecycled || source.config == Bitmap.Config.HARDWARE || mode == GlassMode.TINT) return@withLock null
            val size = GlassPolicy.sampleSize(source.width, source.height) ?: return@withLock null
            val radius = GlassPolicy.radius(mode)
            val ticket=disk?.stamp()
            val epoch=CachePolicy.epoch(CacheKind.IMAGES)
            val bucket=System.currentTimeMillis()/CachePolicy.config.millis(CacheKind.IMAGES)
            val key = "glass:" + source.generationId + ":" + size + ":" + radius+":"+realm+":"+epoch+":"+bucket+":"+ticket
            if(!valid())return@withLock null
            if(retain)get(key)?.let { return@withLock it }
            val stamp = generation
            var small: Bitmap? = null
            var result: Bitmap? = null
            try {
                currentCoroutineContext().ensureActive()
                small = source.scale(size.first, size.second)
                val pixels = IntArray(size.first * size.second)
                small.getPixels(pixels, 0, size.first, 0, 0, size.first, size.second)
                // Hash exactly the sampled pixels used by the algorithm: changed bytes cannot reuse stale blur.
                val content=java.nio.ByteBuffer.allocate(pixels.size*4+8).putInt(size.first).putInt(size.second)
                pixels.forEach(content::putInt)
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(content.array()).joinToString(""){"%02x".format(it)}
                val diskKey="frost-v1:$realm:$radius:$digest"
                val saved=if(disk!=null && ticket!=null)disk.read(diskKey,ticket) else null
                if(saved!=null) {
                    val bounds=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
                    android.graphics.BitmapFactory.decodeByteArray(saved,0,saved.size,bounds)
                    if(bounds.outWidth==size.first && bounds.outHeight==size.second)
                        result=android.graphics.BitmapFactory.decodeByteArray(saved,0,saved.size,
                            android.graphics.BitmapFactory.Options().apply {inPreferredConfig=Bitmap.Config.ARGB_8888})
                    if(result==null && disk!=null && ticket!=null)disk.remove(diskKey,ticket)
                }
                val reused=result!=null
                if(result==null)result = Bitmap.createBitmap(blur(pixels, size.first, size.second, radius), size.first, size.second, Bitmap.Config.ARGB_8888)
                currentCoroutineContext().ensureActive()
                val complete = result!!
                if(!valid() || ticket!=null && disk?.current(ticket)!=true) {complete.recycle();result=null;return@withLock null}
                if(!reused && disk!=null && ticket!=null) {
                    val encoded=java.io.ByteArrayOutputStream()
                    if(complete.compress(Bitmap.CompressFormat.PNG,100,encoded))disk.write(diskKey,encoded.toByteArray(),ticket)
                }
                currentCoroutineContext().ensureActive()
                if(!valid() || ticket!=null && disk?.current(ticket)!=true) {complete.recycle();result=null;return@withLock null}
                synchronized(entries) {
                    if (stamp != generation || RuntimeBudgets.pressured) { complete.recycle(); result = null }
                    else if(retain)put(key, complete)
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
    val revision by ArtworkCaches.revision.collectAsState()
    val privateRef=ArtworkCaches.ref(art.meta,art.source)
    val privateSource=art.source ?: art.meta?.background ?: art.meta?.poster
    val point = remember { IntArray(2) }
    var origin by remember(view) { mutableStateOf(Offset.Zero) }
    SideEffect { view.getLocationOnScreen(point); origin = Offset(point[0].toFloat(), point[1].toFloat()) }
    var sample by remember(identity, source, mode,privateRef,privateSource,revision) { mutableStateOf<Bitmap?>(null) }
    // Framing, layout and focus changes update draw transforms, not image processing.
    LaunchedEffect(identity, source, mode,privateRef,privateSource,revision) {
        if(source==null || mode==GlassMode.TINT) {sample=null;return@LaunchedEffect}
        app.ember.tv.data.CacheManagement.awaitReady()
        val realm=privateRef?.let {ArtworkCaches.realm(context,it) ?: return@LaunchedEffect}
        val allowed=realm==null || ArtworkCaches.persistenceAllowed(realm,privateSource)
        val disk=if(allowed)ArtworkCaches.blur(privateRef!=null) else null
        sample=FrostCache.prepare(source,mode,disk,realm ?: "public",valid={
            privateRef==null || ArtworkCaches.realm(context,privateRef)==realm
        },retain=allowed)
    }
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
