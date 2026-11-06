package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.ember.tv.data.DevicePerformance
import app.ember.tv.data.CustomPerformance
import kotlin.math.roundToInt

data class RenderOptions(val density: String = "standard", val glass: Boolean = false, val quality: String = "balanced",val device:String="high",val custom:CustomPerformance?=null,val glassMethod:String="live",val automaticEffectsReduced:Boolean=false,
    /** The performance profile in effect (low, medium, high, ultra or custom). */
    val profile:String="high") {
    /** Ultra: 4K card artwork and hero pictures on a 4K display. */
    val ultra get() = profile == "ultra"
    val lowPower get() = device == "low"
    /**
     * How glass is drawn, from the performance profile and image quality (Frosted navigation must be on):
     * High requests edge refraction where supported; every blurred surface reuses a prepared sample.
     * An explicit glass style is independent of image quality. Low and disabled glass stay tint-only.
     */
    val glassTier: String get() = when {
        !glass || lowPower || glassMethod == "tint" -> GLASS_STATIC
        glassMethod == "cached" -> GLASS_CACHED
        glassMethod == "liquid" -> GLASS_LIVE
        quality == "economy" -> GLASS_STATIC
        quality == "high" -> GLASS_LIVE
        else -> GLASS_CACHED
    }
    val expandHeroOnFocus get() = !lowPower
    val progressiveHero get() = custom?.progressiveArtwork ?: (!lowPower && quality != "economy")
    val heroAnalysis get() = custom?.heroAnalysis ?: (!lowPower && quality != "economy")
    val colorAnalysis get() = custom?.colorAnalysis ?: true
    val animationsEnabled get() = custom?.animations ?: true
    val collectionMotion get() = custom?.collectionMotion ?: true
    fun animationMillis(normal: Int, reducedMotion: Boolean): Int =
        if (reducedMotion || !animationsEnabled) 0 else if (lowPower) minOf(normal, 120) else normal
    fun focusScale(requested: Float, reducedMotion: Boolean): Float =
        if (reducedMotion || !animationsEnabled) 1f else if (lowPower) minOf(requested, 1.02f) else requested
    /** Basic D-pad feedback is cheaper than optional glow/pulse and survives automatic effect reductions. */
    fun cardFocusScale(requested:Float,style:String,reducedMotion:Boolean):Float {
        val effect=app.ember.tv.data.CardFocusStyle.normalize(style)
        if(reducedMotion || effect==app.ember.tv.data.CardFocusStyle.OFF || !animationsEnabled && !automaticEffectsReduced)return 1f
        val cap=when {
            lowPower || automaticEffectsReduced->1.02f
            effect==app.ember.tv.data.CardFocusStyle.LIFT->1.03f
            else->1.1f
        }
        return requested.takeIf {it.isFinite()}?.coerceIn(1f,cap) ?: 1f
    }
    val heroMaxWidth get()=if(device=="low" || quality=="economy")1280 else if(ultra)3840 else 1920
    val heroMaxHeight get()=if(device=="low" || quality=="economy")720 else if(ultra)2160 else 1080
    val hardwareHero get()=preciseHero && !heroAnalysis && glassTier==GLASS_STATIC
    val heroStorageKey get()=if(hardwareHero)"gpu" else if(preciseHero)"cpu32" else "cpu16"
    val preciseHero get()=device!="low" && quality!="economy"
    fun heroSize(width:Int,height:Int):Pair<Int,Int> {
        val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
        val scale=minOf(1.0,heroMaxWidth.toDouble()/w,heroMaxHeight.toDouble()/h)
        return (w*scale).roundToInt().coerceIn(1,heroMaxWidth) to (h*scale).roundToInt().coerceIn(1,heroMaxHeight)
    }
    val prefetchCount get() = custom?.prefetch?.coerceIn(0,2) ?: when (quality) { "economy" -> 0; "high" -> 2; else -> 1 }
    val heroPrefetchCount get() = if(custom!=null)prefetchCount else maxOf(1,prefetchCount)
    fun count(wide: Boolean): Float = when (density) {
        "comfortable" -> if (wide) 4.25f else 6.25f
        "dense" -> if (wide) 6.25f else 8.25f
        else -> if (wide) 5.25f else 7.25f
    }
    fun columns(widthDp: Float, fontScale: Float): Int {
        val minimum = when (density) { "comfortable" -> 170f; "dense" -> 115f; else -> 140f }
        return (widthDp / (minimum * fontScale.coerceAtLeast(1f))).toInt().coerceIn(3, 10)
    }
}

/**
 * Low: Economy images, no glass blur, minimal effects. Medium: Balanced images, prepared glass, subtle
 * effects. High: High images, refractive glass where supported, every effect. Ultra adds 4K artwork.
 * Custom: the saved image quality and individual effects. Auto picks Low, Medium or High.
 */
internal fun deviceRenderOptions(density:String,glass:Boolean,quality:String,mode:String,lowRam:Boolean,heapMb:Int,totalBytes:Long,custom:CustomPerformance=CustomPerformance(),observedSlow:Boolean=false,uhdDisplay:Boolean=false):RenderOptions {
    val profile=DevicePerformance.profile(mode,lowRam,heapMb,totalBytes,uhdDisplay)
    val requested=quality.takeIf {it in setOf("economy","balanced","high")} ?: "balanced"
    val profileQuality=when(profile){"low"->"economy";"medium"->"balanced";"custom"->requested;else->"high"}
    if(DevicePerformance.normalize(mode)=="auto" && profile!="low" && observedSlow)
        return RenderOptions(density,false,profileQuality,"high",CustomPerformance(false,false,false,false,false,0),automaticEffectsReduced=true,profile=profile)
    return when(profile) {
        "custom"->RenderOptions(density,glass && !lowRam,requested,"custom",custom.normalized(),profile=profile)
        "low"->RenderOptions(density,false,"economy","low",profile=profile)
        "medium"->RenderOptions(density,glass && !lowRam,"balanced","medium",DevicePerformance.MEDIUM_EFFECTS,profile=profile)
        else->RenderOptions(density,glass && !lowRam,"high","high",profile=profile)
    }
}

@Composable
internal fun rememberDeviceRenderOptions(density:String,glass:Boolean,quality:String,mode:String,custom:CustomPerformance=CustomPerformance(),observedSlow:Boolean=false,uhdDisplay:Boolean=Art.display4K):RenderOptions {
    val context=LocalContext.current
    val caps=remember(context) { DevicePerformance.capabilities(context) }
    return remember(density,glass,quality,mode,caps,custom,observedSlow,uhdDisplay) {deviceRenderOptions(density,glass,quality,mode,caps.lowRam,caps.heapMb,caps.totalBytes,custom,observedSlow,uhdDisplay)}
}

const val GLASS_STATIC = "static"
const val GLASS_CACHED = "cached"
const val GLASS_LIVE = "live"

val LocalRenderOptions = staticCompositionLocalOf { RenderOptions() }
val LocalHeroArtworkMode = compositionLocalOf { app.ember.tv.data.HeroArtworkMode.STANDARD }
val LocalBackdropArtwork = compositionLocalOf { ReadyArtwork(null, null) }
val LocalArtworkPresented = compositionLocalOf<(ReadyArtwork) -> Unit> { {} }
