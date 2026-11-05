package app.ember.tv.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.scale
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class HeroFocus(val x:Float=.65f,val y:Float=.32f)
data class HeroViewport(val left:Float,val top:Float,val width:Float,val height:Float)
internal data class HeroFrame(val fit:Boolean,val xBias:Float,val yBias:Float)
internal data class HeroProjection(val left:Float,val top:Float,val width:Float,val height:Float)

internal object HeroFraming {
    fun supplied(x:Float?,y:Float?):HeroFocus?=if(x!=null && y!=null && x.isFinite() && y.isFinite())HeroFocus(x.coerceIn(0f,1f),y.coerceIn(0f,1f)) else null
    fun plan(width:Int,height:Int,viewWidth:Float,viewHeight:Float,focus:HeroFocus=HeroFocus()):HeroFrame {
        if(width<=0 || height<=0 || !viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth<=0 || viewHeight<=0)return HeroFrame(true,1f,0f)
        val scale=maxOf(viewWidth/width,viewHeight/height)
        val w=width*scale;val h=height*scale
        val retained=minOf(viewWidth/w,viewHeight/h)
        if(fitHeroPortrait(width,height) || retained<.74f)return HeroFrame(true,1f,0f)
        fun bias(point:Float,target:Float,scaled:Float,view:Float):Float {
            val extra=scaled-view
            if(extra<.5f)return 0f
            val p=point.takeIf {it.isFinite()} ?: .5f
            return (((p.coerceIn(0f,1f)*scaled-target*view).coerceIn(0f,extra)/extra)*2f-1f).coerceIn(-1f,1f)
        }
        return HeroFrame(false,bias(focus.x,.72f,w,viewWidth),bias(focus.y,.40f,h,viewHeight))
    }
    fun projection(width:Int,height:Int,viewWidth:Float,viewHeight:Float,focus:HeroFocus=HeroFocus()):HeroProjection? {
        if(width<=0 || height<=0 || !viewWidth.isFinite() || !viewHeight.isFinite() || viewWidth<=0 || viewHeight<=0)return null
        val frame=plan(width,height,viewWidth,viewHeight,focus)
        val scale=if(frame.fit)minOf(viewWidth/width,viewHeight/height) else maxOf(viewWidth/width,viewHeight/height)
        val w=width*scale;val h=height*scale
        if(!w.isFinite() || !h.isFinite())return null
        return HeroProjection((viewWidth-w)*(frame.xBias+1)/2,(viewHeight-h)*(frame.yBias+1)/2,w,h)
    }
    fun glassProjection(width:Int,height:Int,viewport:HeroViewport,focus:HeroFocus=HeroFocus()):HeroProjection? {
        if(!viewport.left.isFinite() || !viewport.top.isFinite())return null
        return projection(width,height,viewport.width,viewport.height,focus)?.let {it.copy(left=it.left+viewport.left,top=it.top+viewport.top)}
    }
    /** Detail-weighted framing estimate, not face recognition. Flat art keeps the conservative default. */
    fun estimate(pixels:IntArray,width:Int,height:Int):HeroFocus {
        require(width>=3 && height>=3 && pixels.size==width*height)
        fun luma(pixel:Int)=((pixel shr 16 and 255)*54+(pixel shr 8 and 255)*183+(pixel and 255)*19)/256f
        var total=0.0;var sx=0.0;var sy=0.0
        for(y in 1 until height-1)for(x in 1 until width-1) {
            val energy=abs(luma(pixels[y*width+x+1])-luma(pixels[y*width+x-1]))+
                abs(luma(pixels[(y+1)*width+x])-luma(pixels[(y-1)*width+x]))
            val weight=energy*(.35+x.toDouble()/width)*(.95-.45*y.toDouble()/height)
            total+=weight;sx+=weight*x/width;sy+=weight*y/height
        }
        if(total<width*height*2)return HeroFocus()
        return HeroFocus((sx/total).toFloat().coerceIn(.25f,.85f),(sy/total).toFloat().coerceIn(.18f,.55f))
    }
}

/** Bounded points only; analysis finishes before publication so a settled image never pans later. */
private object HeroFocusCache {
    val points=object:LinkedHashMap<String,HeroFocus>(32,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,HeroFocus>?)=size>96
    }
}
internal suspend fun framedArtwork(art:ReadyArtwork,options:RenderOptions):ReadyArtwork {
    val meta=art.meta ?: return art
    val supplied=HeroFraming.supplied(meta.backgroundFocusX,meta.backgroundFocusY)
    if(supplied!=null)return art.copy(focus=supplied)
    if(!options.heroAnalysis)return art
    val bitmap=(art.drawable as? BitmapDrawable)?.bitmap ?: return art
    if(fitHeroPortrait(bitmap.width,bitmap.height))return art
    val shape=kotlin.math.round(bitmap.width.toFloat()/bitmap.height*10).toInt()
    val cacheKey="${meta.type}:${meta.id}:${meta.background ?: meta.poster}:$shape"
    synchronized(HeroFocusCache.points){HeroFocusCache.points[cacheKey]}?.let {return art.copy(focus=it)}
    val focus=withContext(Dispatchers.Default) {
        runCatching {
            val small=bitmap.scale(64,36,true)
            try {
                val pixels=IntArray(64*36);small.getPixels(pixels,0,64,0,0,64,36)
                HeroFraming.estimate(pixels,64,36)
            } finally {if(small!==bitmap)small.recycle()}
        }.getOrDefault(HeroFocus())
    }
    currentCoroutineContext().ensureActive()
    synchronized(HeroFocusCache.points){HeroFocusCache.points[cacheKey]=focus}
    return art.copy(focus=focus)
}

@Composable internal fun FramedHeroImage(art:ReadyArtwork,modifier:Modifier=Modifier) {
    val drawable=art.drawable ?: return
    val density=androidx.compose.ui.platform.LocalDensity.current.density
    BoxWithConstraints(modifier) {
        val frame=HeroFraming.plan(drawable.intrinsicWidth,drawable.intrinsicHeight,maxWidth.value*density,maxHeight.value*density,art.focus ?: HeroFocus())
        AsyncImage(drawable,null,Modifier.fillMaxSize(),contentScale=if(frame.fit)ContentScale.Fit else ContentScale.Crop,
            alignment=BiasAlignment(frame.xBias,frame.yBias))
    }
}
