package app.ember.tv.ui

import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.ember.tv.data.*
import app.ember.tv.mediaserver.ServerImagePolicy
import app.ember.tv.mediaserver.loadFirstServerImage
import coil.compose.AsyncImage
import coil.request.SuccessResult
import kotlinx.coroutines.ensureActive

/** Public decoration is lazy for servers: native images can work without API keys or internet. */
@Composable
internal fun rememberServerFallback(meta: Meta, enabled: Boolean): HeroArtwork {
    return if(enabled)rememberHeroDecoration(meta) else HeroArtwork()
}

@Composable
internal fun rememberServerReadyArtwork(meta: Meta, size: Pair<Int, Int>): ReadyArtwork {
    val context = LocalContext.current
    val options = LocalRenderOptions.current
    val identity = LocalNavigationFocus.current
    val key = meta.mediaKey()
    var ready by remember(identity) { mutableStateOf(ReadyArtwork(null, null)) }
    var nativeFailed by remember(identity, key, meta.background) { mutableStateOf(false) }
    val fallback = rememberServerFallback(meta, nativeFailed || meta.background.isNullOrBlank())
    val decorated = meta.copy(logo = meta.logo ?: fallback.logo)
    val latest by rememberUpdatedState(decorated)
    LaunchedEffect(identity, decorated) {
        if(ready.meta?.mediaKey()==key)ready=ready.copy(meta=decorated.copy(background=ready.meta?.background))
    }
    // Neither provider enrichment nor native failure is a key of this request.
    LaunchedEffect(identity, key, meta.background, size, options) {
        if(nativeFailed)return@LaunchedEffect
        val url = meta.background?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val result = HeroImages.loader(context).execute(heroRequest(context, heroArtworkUrl(url, options), size, options)) as? SuccessResult
        ensureActive()
        if(result!=null)ready=framedArtwork(ReadyArtwork(latest, result.drawable,source=url),options) else nativeFailed=true
    }
    val candidates = ServerImagePolicy.backgrounds(meta, fallback, if(nativeFailed) setOfNotNull(meta.background) else emptySet())
    LaunchedEffect(identity, key, nativeFailed, candidates, size, options) {
        if(!nativeFailed && !meta.background.isNullOrBlank())return@LaunchedEffect
        val result = loadFirstServerImage(candidates) { url, wide ->
            (HeroImages.loader(context).execute(heroRequest(context, heroArtworkUrl(url, options, wide), size, options)) as? SuccessResult)?.drawable
        }
        ensureActive()
        if(result!=null) {
            val background=result.first.first.takeIf {result.first.second}
            ready=framedArtwork(ReadyArtwork(latest.copy(background=background,
                backgroundFocusX=latest.backgroundFocusX.takeIf {background==latest.background},backgroundFocusY=latest.backgroundFocusY.takeIf {background==latest.background}),result.second,source=result.first.first),options)
        }
        else if(ready.meta?.mediaKey()!=key)ready=ReadyArtwork(latest,null)
    }
    return ready
}

/** Honor an explicit poster enhancement, then native/public portraits; never stretch a backdrop. */
@Composable
internal fun ServerPosterImage(meta: Meta, modifier: Modifier, config: PosterConfig, onImage: (Drawable, String) -> Unit) {
    var failed by remember(meta.mediaKey(),meta.poster,config,Posters.keyRev) { mutableStateOf(emptySet<String>()) }
    val fallback = rememberServerFallback(meta, meta.poster.isNullOrBlank() || meta.poster in failed)
    val enhanced = Posters.url(meta, config)
    val model = ServerImagePolicy.posters(meta,fallback,enhanced,failed).firstOrNull()
    ArtworkImage(model, meta.name, modifier, contentScale=ContentScale.Crop,
        onError={if(model!=null)failed=failed+model},
        onSuccess={onImage(it,model.orEmpty())})
}
