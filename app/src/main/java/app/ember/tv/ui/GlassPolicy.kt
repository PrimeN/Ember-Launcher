package app.ember.tv.ui

internal enum class GlassMode { TINT, FROSTED, LIQUID }
internal data class GlassPlacement(val x:Float=0f,val y:Float=0f,val scaleX:Float=1f,val scaleY:Float=1f)

/** Rendering budgets never change artwork resolution or playback quality. */
internal object GlassPolicy {
    fun mode(options: RenderOptions, sdk: Int, lowRam: Boolean = false, pressured: Boolean = false): GlassMode {
        if (lowRam || pressured || options.glassTier == GLASS_STATIC) return GlassMode.TINT
        return if (options.glassTier == GLASS_LIVE && sdk >= 33 && options.device != "medium") GlassMode.LIQUID else GlassMode.FROSTED
    }
    fun sampleSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val scale = minOf(1.0, 384.0 / maxOf(width, height))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
    fun radius(mode: GlassMode) = if (mode == GlassMode.LIQUID) 3 else 6
    fun localFrame(frame:HeroProjection,originX:Float,originY:Float,placement:GlassPlacement) = HeroProjection(
        (frame.left+originX-placement.x)/placement.scaleX,(frame.top+originY-placement.y)/placement.scaleY,
        frame.width/placement.scaleX,frame.height/placement.scaleY)
    fun tintAlpha(panel: Boolean, sampled: Boolean, day: Boolean): Float = when {
        panel -> if (sampled) .82f else .94f
        sampled -> if (day) .50f else .30f
        else -> if (day) .56f else .38f
    }
    /** A small surface stays readable over bright artwork without darkening every image. */
    fun readableTint(alpha:Float,brightness:Float,day:Boolean):Float {
        val light=brightness.coerceIn(0f,1f)
        if(day)return maxOf(alpha,.78f*(1f-light))
        val t=((light-.2f)/.2f).coerceIn(0f,1f)
        val target=.80f+.06f*((light-.4f)/.6f).coerceIn(0f,1f)
        return alpha+maxOf(0f,target-alpha)*t*t*(3f-2f*t)
    }
}
