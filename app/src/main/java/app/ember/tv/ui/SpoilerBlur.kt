package app.ember.tv.ui

import androidx.core.graphics.scale
import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation
internal object SpoilerPolicy {
    fun blur(enabled:Boolean,watched:Boolean,ready:Boolean)=enabled && (!ready || !watched)
}

/** Small, three-pass box blur works on Android 10 as well as TVs without GPU blur support. */
internal object SpoilerPixels {
    fun blur(input:IntArray,width:Int,height:Int,radius:Int=4):IntArray {
        require(width>0 && height>0 && input.size==width*height)
        var current=input.copyOf()
        repeat(3) {
            val next=IntArray(current.size)
            for(y in 0 until height)for(x in 0 until width) {
                var r=0;var g=0;var b=0;var count=0
                for(yy in maxOf(0,y-radius)..minOf(height-1,y+radius))for(xx in maxOf(0,x-radius)..minOf(width-1,x+radius)) {
                    val p=current[yy*width+xx];r+=p shr 16 and 255;g+=p shr 8 and 255;b+=p and 255;count++
                }
                next[y*width+x]=(255 shl 24) or ((r/count) shl 16) or ((g/count) shl 8) or (b/count)
            }
            current=next
        }
        return current
    }
}
internal object SpoilerBlurTransformation:Transformation {
    override val cacheKey="ember-episode-spoiler-v1"
    override suspend fun transform(input:Bitmap,size:Size):Bitmap {
        val small=input.scale(64,36)
        try {
            val pixels=IntArray(64*36);small.getPixels(pixels,0,64,0,0,64,36)
            return Bitmap.createBitmap(SpoilerPixels.blur(pixels,64,36),64,36,Bitmap.Config.ARGB_8888)
        } finally {if(small!==input)small.recycle()}
    }
}
