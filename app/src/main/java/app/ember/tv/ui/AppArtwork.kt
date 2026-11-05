package app.ember.tv.ui

import app.ember.tv.home.AppArtImport
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.LayerDrawable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import app.ember.tv.data.firstUsableMetadata
import app.ember.tv.data.visibleAppArtwork
import app.ember.tv.data.AppEntry
import app.ember.tv.data.DevicePerformance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AppArtworkKey(val pkg:String,val revision:Long,val circle:Boolean,val configuration:String,val activity:String="",val custom:String="")

/** Shared byte-bounded bitmaps; all package lookups and drawable rasterization run off the UI thread. */
internal object AppArtwork {
    private val drawing=Mutex()
    private var cache:WeightedArtworkCache<AppArtworkKey,Bitmap>?=null
    private var generation=0L
    private fun cache(context:Context):WeightedArtworkCache<AppArtworkKey,Bitmap> {
        synchronized(this){cache}?.let {return it}
        // Do not hold the monitor used by UI cache peeks across ActivityManager IPC.
        val caps=DevicePerformance.capabilities(context)
        return synchronized(this) {
            cache ?: WeightedArtworkCache<AppArtworkKey,Bitmap>(ArtworkStorageBudget.apps(caps.heapMb,caps.lowRam)){it.allocationByteCount}
                .also {cache=it}
        }
    }
    fun peek(key:AppArtworkKey)=synchronized(this){cache?.get(key)}
    @Synchronized fun clear() {generation++;cache?.clear()}
    suspend fun load(context:Context,app:AppEntry,key:AppArtworkKey,custom:app.ember.tv.home.AppArt?=null):Bitmap?=withContext(Dispatchers.Default) {
        peek(key)?.let {return@withContext it}
        drawing.withLock {
            val store=cache(context)
            store.get(key)?.let {return@withLock it}
            val started=synchronized(this@AppArtwork){generation}
            fun render(original:android.graphics.drawable.Drawable):Bitmap? {
                val drawable=original.constantState?.newDrawable(context.resources)?.mutate() ?: original
                val size=if(key.circle)192 to 192 else ArtworkStorageBudget.banner(drawable.intrinsicWidth,drawable.intrinsicHeight)
                return drawable.toBitmap(size.first,size.second).let {if(it.config==Bitmap.Config.HARDWARE)it.copy(Bitmap.Config.ARGB_8888,false) else it}
            }
            val customBitmap=custom?.let {runCatching {AppArtImport.decode(java.util.Base64.getDecoder().decode(it.data))}.getOrNull()}
            val bitmap=customBitmap ?: runCatching {
                val work=currentCoroutineContext()
                val pm=context.packageManager
                fun image(read:()->android.graphics.drawable.Drawable?):Bitmap? {
                    work.ensureActive()
                    val icon=read() ?: return null
                    val source=if(key.circle && icon is AdaptiveIconDrawable)LayerDrawable(listOfNotNull(icon.background,icon.foreground).toTypedArray()) else icon
                    return render(source)
                }
                visibleAppArtwork(key.circle,
                    {image {pm.getActivityBanner(app.intent)}}, {image {pm.getApplicationBanner(app.pkg)}},
                    {image {app.icon}}, {image {pm.getActivityIcon(app.intent)}},
                    {image {pm.getApplicationIcon(app.pkg)}}, {image {pm.defaultActivityIcon}})
            }.getOrNull()
            currentCoroutineContext().ensureActive()
            if(bitmap!=null)synchronized(this@AppArtwork) {if(started==generation)store.put(key,bitmap)}
            bitmap
        }
    }
}

val LocalAppArtwork=staticCompositionLocalOf<Map<String,app.ember.tv.home.AppArt>> {emptyMap()}

@Composable internal fun rememberAppArtwork(app:AppEntry,circle:Boolean=false):Bitmap? {
    val context=LocalContext.current
    val custom=LocalAppArtwork.current[app.pkg]
    val configuration=androidx.compose.ui.platform.LocalConfiguration.current
    val activity=app.intent.component?.flattenToString().orEmpty()
    val key=remember(app.pkg,app.artworkVersion,circle,activity,custom?.id,configuration.densityDpi,configuration.uiMode,configuration.locales) {
        AppArtworkKey(app.pkg,app.artworkVersion,circle,"${configuration.densityDpi}:${configuration.uiMode}:${configuration.locales.toLanguageTags()}",activity,custom?.id.orEmpty())
    }
    val initial=remember(key){AppArtwork.peek(key)}
    val bitmap by produceState(initial,key,app.icon) {value=initial;value=AppArtwork.load(context.applicationContext,app,key,custom)}
    return bitmap
}
