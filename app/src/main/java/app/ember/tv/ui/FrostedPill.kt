package app.ember.tv.ui

import androidx.core.graphics.scale
import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalGlassBitmap = compositionLocalOf<Bitmap?> { null }
val LocalGlassPreblurred = compositionLocalOf {false}
internal data class GlassArtwork(val bitmap:Bitmap?=null,val preblurred:Boolean=false)

/** Only finished samples are retained; the original hero bitmap is never owned by this cache. */
internal object FrostCache {
    private val entries=object:android.util.LruCache<String,Bitmap>(2*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    fun clear()=entries.evictAll()
    fun get(key:String)=entries.get(key)
    fun put(key:String,value:Bitmap){entries.put(key,value)}
}

@Composable
internal fun rememberGlassBitmap(art: ReadyArtwork, options: RenderOptions, identity: Any?): GlassArtwork {
    val context = LocalContext.current
    val lowRam = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice==true }
    val cached=options.glassMethod=="cached"
    // A prepared (cached) blur needs no RenderEffect, so it also works before Android 12.
    val enabled = !RuntimeBudgets.pressured && (Build.VERSION.SDK_INT >= 31 || cached) && options.glassTier != GLASS_STATIC && !lowRam
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    var sample by remember(identity) { mutableStateOf(GlassArtwork()) }
    LaunchedEffect(identity, art.drawable, art.viewport,art.focus,enabled,cached,density) {
        sample=GlassArtwork()
        if(enabled)sample=art.drawable?.let {drawable->withContext(Dispatchers.Default) {
            val source=(drawable as? BitmapDrawable)?.bitmap ?: return@withContext GlassArtwork()
            val scale=minOf(1f,512f/maxOf(source.width,source.height))
            val w=(source.width*scale).toInt().coerceAtLeast(1);val h=(source.height*scale).toInt().coerceAtLeast(1)
            val projection=art.viewport?.let {HeroFraming.glassProjection(w,h,it,art.focus ?: HeroFocus())}
            val radius=if(cached && projection!=null)(12*density*w/projection.width).toInt().coerceIn(1,32) else 0
            val key="${source.generationId}:$w:$h:$radius"
            if(radius>0)FrostCache.get(key)?.let {return@withContext GlassArtwork(it,true)}
            runCatching {
                val scaled=source.scale(w,h)
                if(radius==0)GlassArtwork(scaled) else {
                    val pixels=IntArray(w*h);scaled.getPixels(pixels,0,w,0,0,w,h)
                    val blurred=Bitmap.createBitmap(FrostBlur.apply(pixels,w,h,radius),w,h,Bitmap.Config.ARGB_8888)
                    // createScaledBitmap can return source; never recycle a shared image.
                    if(scaled!==source)scaled.recycle()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(!RuntimeBudgets.pressured)FrostCache.put(key,blurred)
                    GlassArtwork(blurred,true)
                }
            }.getOrDefault(GlassArtwork())
        }} ?: GlassArtwork()
    }
    return if(enabled)sample else GlassArtwork()
}

/** One tint, highlight and edge for navigation capsules and media actions. */
@Composable
internal fun Modifier.navigationGlass(shape:Shape?=null):Modifier {
    val edge=navigationGlassShape(isEmberX,shape)
    return if(isEmberX)background(Color.White.copy(alpha=.10f),edge).border(1.dp,Color.White.copy(alpha=.16f),edge)
    else background(PanelHi.copy(alpha=.48f),edge)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha=.14f),Color.White.copy(alpha=.03f))),edge)
        .border(1.dp,if(Ui.isDay)Color.Black.copy(alpha=.08f) else Color.White.copy(alpha=.16f),edge)
}

/** Round transport controls retain their own edge even within a rounded-square interface. */
internal fun navigationGlassShape(emberX:Boolean,shape:Shape?=null):Shape =
    shape ?: if(emberX)androidx.compose.foundation.shape.RoundedCornerShape(EmberXLayout.Radius.dp) else CircleShape

/** Both pills sample one shared downsampled image; text never enters the blur layer. */
@Composable
fun FrostedPill(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val bitmap = LocalGlassBitmap.current
    val preblurred=LocalGlassPreblurred.current
    val focus=LocalBackdropArtwork.current.focus ?: HeroFocus()
    val viewport=LocalBackdropArtwork.current.viewport
    var position by remember { mutableStateOf(Offset.Zero) }
    val shape=if(isEmberX)androidx.compose.foundation.shape.RoundedCornerShape(EmberXLayout.Radius.dp) else CircleShape
    Box(modifier.clip(shape).onGloballyPositioned { position = it.positionInRoot() }) {
        bitmap?.takeIf {viewport!=null}?.let { image ->
            Canvas(Modifier.matchParentSize().graphicsLayer {
                renderEffect = if(preblurred)null else BlurEffect(12.dp.toPx(), 12.dp.toPx(), TileMode.Clamp)
            }) {
                val frame=HeroFraming.glassProjection(image.width,image.height,viewport!!,focus) ?: return@Canvas
                drawImage(image.asImageBitmap(),
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width,image.height),
                    dstOffset = IntOffset((frame.left-position.x).toInt(),(frame.top-position.y).toInt()),
                    dstSize = IntSize(frame.width.toInt().coerceAtLeast(1),frame.height.toInt().coerceAtLeast(1)))
            }
        }
        content()
    }
}
