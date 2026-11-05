package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import app.ember.tv.data.TilePalette
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal val LocalLightTint=staticCompositionLocalOf {Color(0xFFBFD4FF)}
private val lightPalettes=linkedMapOf<String,Color>()

/**
 * The wallpaper tint for a picture. It samples its own tiny software copy (usually from the disk
 * cache of the card-size picture) so the full-screen hero can stay a hardware bitmap.
 */
@Composable internal fun rememberLightPalette(key:String?,enabled:Boolean,base:Color):Color {
    val context=LocalContext.current
    var color by remember(key,base) {mutableStateOf(synchronized(lightPalettes){lightPalettes[key]} ?: base)}
    LaunchedEffect(key,enabled) {
        if(!enabled || key==null)return@LaunchedEffect
        synchronized(lightPalettes){lightPalettes[key]}?.let {color=it;return@LaunchedEffect}
        val source=if(key.startsWith("http"))cardArt(key,true,uhd=false) else key
        val image=(context.imageLoader.execute(ImageRequest.Builder(context).data(source).size(32,32)
            .allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).build()) as? SuccessResult)?.drawable ?: return@LaunchedEffect
        val result=withContext(Dispatchers.Default) {runCatching {
            val bitmap=image.toBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888)
            val pixels=IntArray(1024);bitmap.getPixels(pixels,0,32,0,0,32,32)
            TilePalette.color(pixels)?.let(::Color)
        }.getOrNull()}
        ensureActive()
        if(result!=null){color=result;synchronized(lightPalettes){lightPalettes[key]=result;while(lightPalettes.size>32)lightPalettes.remove(lightPalettes.keys.first())}}
    }
    return if(enabled)color else base
}
