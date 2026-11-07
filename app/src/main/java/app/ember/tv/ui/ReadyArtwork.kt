package app.ember.tv.ui

import androidx.core.graphics.drawable.toDrawable

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import app.ember.tv.data.Meta
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import app.ember.tv.data.mediaKey
import app.ember.tv.data.ApiKeys
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

data class ReadyArtwork(val meta: Meta?, val drawable: Drawable?,val focus:HeroFocus?=null,val viewport:HeroViewport?=null,val source:String?=null)

@Composable
private fun heroSize(options:RenderOptions):Pair<Int,Int> {
    val view=LocalView.current
    // Before the first layout the view has no size yet; the window's size (pixels) stands in.
    val window=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val width=view.width.takeIf {it>0} ?: window.width.coerceAtLeast(1)
    val height=view.height.takeIf {it>0} ?: window.height.coerceAtLeast(1)
    // EmberX draws the backdrop full height; decoding it shorter would upscale (soften) it.
    val fraction=if(Ui.emberX)1f else app.ember.tv.data.HeroBanner.BACKDROP_FRACTION
    return options.heroSize(width,(height*fraction).toInt())
}

/**
 * The picture on screen right now loads through its own request lane (#56). It shares the app
 * loader's memory and disk caches (and its fetchers), so a prefetched or already shown picture
 * is reused; only the network queue differs.
 */
internal object HeroImages {
    @Volatile private var loader: coil.ImageLoader? = null
    fun loader(context: android.content.Context): coil.ImageLoader = loader ?: synchronized(this) {
        loader ?: context.imageLoader.newBuilder().okHttpClient { app.ember.tv.data.Net.heroArtworkClient() }.build().also { loader = it }
    }
}

/** Display and prefetch must use the same bounds, precision and memory-cache identity. */
internal fun heroRequest(context:android.content.Context,url:String,size:Pair<Int,Int>,options:RenderOptions):ImageRequest =
    ImageRequest.Builder(context).data(url).size(size.first,size.second)
        .precision(coil.size.Precision.EXACT).scale(coil.size.Scale.FIT)
        .allowHardware(options.hardwareHero).allowRgb565(!options.preciseHero)
        // Coil only uses GPU memory when the requested config is HARDWARE; asking for ARGB_8888
        // here would keep the "gpu" hero on the Java heap despite allowHardware(true).
        .bitmapConfig(if(options.hardwareHero)Bitmap.Config.HARDWARE else if(options.preciseHero)Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        .memoryCacheKey("ember-hero:${options.heroStorageKey}:${size.first}x${size.second}:$url").build()

/**
 * Keep the displayed scene intact until the replacement is decoded. Cancellation wins.
 * A full-screen picture can take seconds to download and decode, so the card-size picture the Home
 * rows already use (usually cached) is shown first and the sharp one replaces it when it is ready.
 * A sharp picture already in memory is shown at once.
 */
@Composable
fun rememberReadyArtwork(meta: Meta?): ReadyArtwork {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val options = LocalRenderOptions.current
    val artworkMode = LocalHeroArtworkMode.current
    val size=heroSize(options)
    if(meta!=null && app.ember.tv.mediaserver.ServerRef.of(meta)!=null)return rememberServerReadyArtwork(meta,size)
    val identity = LocalNavigationFocus.current
    var ready by remember(identity) { mutableStateOf(ReadyArtwork(null, null)) }
    val decoration = rememberHeroDecoration(meta)
    val key = meta?.mediaKey()
    // Low freezes the background for this selection. Metadata warms the next selection and
    // logos can appear now, without canceling/restarting a large image already in flight.
    val hasFallback = !meta?.background.isNullOrBlank() || !meta?.poster.isNullOrBlank()
    val selectedBackgrounds = if (!options.progressiveHero && hasFallback) remember(identity,key,meta?.background,ApiKeys.revision,artworkMode) { decoration.backgrounds } else decoration.backgrounds
    val decorated = meta?.copy(logo = decoration.logo ?: meta.logo)
    val latest by rememberUpdatedState(decorated)
    val original = listOfNotNull(
        meta?.background?.takeIf { it.isNotBlank() }?.let { it to true },
        meta?.poster?.takeIf { it.isNotBlank() }?.let { it to false })
    val candidates = (selectedBackgrounds.map {it to true} + original).distinctBy {it.first}.take(4)
    val latestCandidates by rememberUpdatedState(candidates)
    val imageSelection = candidates.map {it.first}
    LaunchedEffect(identity, decorated) {
        val displayed = ready.meta
        if (displayed != null && displayed.mediaKey() == key && decorated != null)
            ready = ready.copy(meta = decorated.copy(background = displayed.background))
    }
    LaunchedEffect(identity, key, imageSelection, options,size) {
        val first = candidates.firstOrNull()
        if (first == null || meta == null) { ready = ReadyArtwork(latest, null); return@LaunchedEffect }
        val (raw, wide) = first
        suspend fun shown(background: String?, drawable: Drawable?):ReadyArtwork {
            val value=latest ?: meta
            return framedArtwork(ReadyArtwork(value.copy(background=background,
                backgroundFocusX=value.backgroundFocusX.takeIf {background==value.background},backgroundFocusY=value.backgroundFocusY.takeIf {background==value.background}),drawable),options)
        }
        val request = heroRequest(context,heroArtworkUrl(raw,options,wide),size,options)
        val loader = HeroImages.loader(context)
        val cached = request.memoryCacheKey?.let { loader.memoryCache?.get(it) }?.bitmap
        if (cached != null) { ready = shown(if(wide)raw else null, cached.toDrawable(resources)); return@LaunchedEffect }
        val previewUrl = cardArt(raw, wide, uhd = false)
        if (!options.progressiveHero) {
            // Reuse an already visible card immediately, without a second fetch/decode on Low.
            loader.memoryCache?.get(coil.memory.MemoryCache.Key(previewUrl))?.bitmap?.let { card ->
                ready = shown(if(wide)raw else null, card.toDrawable(resources))
            }
        }
        // Economy/Low and providers with only one URL do not compete with a second request.
        suspend fun sharpImage(): ReadyArtwork? {
            for ((candidate, landscape) in latestCandidates) {
                val image = (loader.execute(heroRequest(context,heroArtworkUrl(candidate,options,landscape),size,options)) as? SuccessResult)?.drawable
                ensureActive()
                if (image != null) return shown(if(landscape)candidate else null,image)
            }
            return null
        }
        val sharp = if (!options.progressiveHero || previewUrl == heroArtworkUrl(raw,options,wide)) sharpImage() else loadHeroArtwork(
            preview = {
                (loader.execute(ImageRequest.Builder(context).data(previewUrl)
                    .size(640, 360).allowHardware(false).build()) as? SuccessResult)?.drawable?.let { shown(if(wide)raw else null,it) }
            },
            sharp = { delay(120); sharpImage() },
            publishPreview = { preview ->
                if (ready.meta?.mediaKey() != key) ready = preview
            },
        )
        ensureActive()
        val sameTitle = ready.meta?.mediaKey() == key
        ready = sharp?.copy(meta = (latest ?: meta).copy(background = sharp.meta?.background))
            ?: shown(ready.meta?.background.takeIf { sameTitle }, ready.drawable.takeIf { sameTitle })
    }
    return ready
}

@Composable
fun PrefetchHeroArtwork(items: List<Meta>, index: Int, enabled: Boolean = true) {
    val context = LocalContext.current
    val options=LocalRenderOptions.current
    val size=heroSize(options)
    val active = LocalSceneInteractive.current && !RuntimeBudgets.pressured
    val count = options.prefetchCount
    val identity = LocalNavigationFocus.current
    val locale = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"
    val revision = ApiKeys.revision
    val artworkMode = LocalHeroArtworkMode.current
    LaunchedEffect(identity,items,index,active,enabled,count,options,size,locale,revision,artworkMode) {
        if (!active || !enabled || items.size < 2 || options.heroPrefetchCount==0) return@LaunchedEffect
        delay(if(options.lowPower)600 else 300)
        coroutineScope {
            (1..minOf(if(options.lowPower)1 else options.heroPrefetchCount,items.size-1)).map {step->async {
                val original = items[(index+step).mod(items.size)]
                val meta = if(app.ember.tv.mediaserver.ServerRef.of(original)!=null)original
                    else HeroArtworkState.load(context,original,locale,revision,artworkMode).applyTo(original)
                val wide = !meta.background.isNullOrBlank()
                if(count>0)(meta.background?.takeIf {it.isNotBlank()} ?: meta.poster)?.let {url->
                    context.imageLoader.execute(heroRequest(context,heroArtworkUrl(url,options,wide),size,options))
                }
            }}.awaitAll()
        }
    }
}

/** Only the next one/two images of an attached visible row; economy mode performs no prefetch. */
@Composable
fun PrefetchRowArtwork(items:List<Meta>,state:androidx.compose.foundation.lazy.LazyListState,wide:Boolean,width:androidx.compose.ui.unit.Dp) {
    val context=LocalContext.current
    val identity=LocalNavigationFocus.current
    val options=LocalRenderOptions.current
    val count=options.prefetchCount
    val active=LocalSceneInteractive.current && !RuntimeBudgets.pressured
    val last by remember(state) {derivedStateOf {state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1}}
    val scrolling by remember(state) {derivedStateOf {state.isScrollInProgress}}
    val uhd=Art.uhd && !options.lowPower
    val software=tileNeedsSoftwarePixels()
    val size=rowArtworkSize(with(androidx.compose.ui.platform.LocalDensity.current){width.roundToPx()},wide)
    val posters=app.ember.tv.data.Posters.config
    val keyRevision=app.ember.tv.data.Posters.keyRev
    DisposableEffect(identity,items,last,count,active,scrolling,wide,uhd,software,size,posters,keyRevision) {
        val requests=if(!active || scrolling || last<0 || count==0)emptyList() else items.drop(last+1).take(count).mapNotNull {meta ->
            val backdrop = meta.background?.takeIf { it.isNotBlank() }
            val useWide = wide && backdrop != null
            val enhanced=if(wide) {
                app.ember.tv.data.Posters.landscapeUrl(meta,posters) ?: if(!useWide)app.ember.tv.data.Posters.url(meta,posters) else null
            } else app.ember.tv.data.Posters.url(meta,posters)
            val url=enhanced ?: (if(useWide)backdrop else meta.poster)?.let {cardArt(it,useWide,uhd)}
            url?.let {
                context.imageLoader.enqueue(ImageRequest.Builder(context).data(it)
                    .size(size.first,size.second).scale(coil.size.Scale.FILL).precision(coil.size.Precision.INEXACT)
                    .allowHardware(!software).build())
            }
        }
        onDispose {requests.forEach {it.dispose()}}
    }
}


/** A failed sharp request must not cancel a still-useful preview. Sharp success wins. */
internal suspend fun <T : Any> loadHeroArtwork(preview: suspend () -> T?, sharp: suspend () -> T?, publishPreview: (T) -> Unit): T? = coroutineScope {
    val quick = launch { preview()?.let { ensureActive(); publishPreview(it) } }
    val full = sharp()
    ensureActive()
    if (full != null) quick.cancelAndJoin() else quick.join()
    full
}

/** Use the visible card's actual pixel bounds rather than a fixed phone-size prefetch. */
internal fun rowArtworkSize(width:Int,wide:Boolean):Pair<Int,Int> {
    val w=width.coerceAtLeast(1)
    return w to kotlin.math.ceil(w.toDouble()*(if(wide)9.0/16.0 else 1.5)).toInt().coerceAtLeast(1)
}
