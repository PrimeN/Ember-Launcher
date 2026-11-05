package app.ember.tv.ui

import androidx.compose.runtime.*
import android.graphics.drawable.Drawable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ember.tv.data.CollectionMediaKind
import app.ember.tv.data.CollectionMotion
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay

@Composable
internal fun collectionMotionActive(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return !RuntimeBudgets.pressured && LocalRenderOptions.current.collectionMotion && resumed && LocalSceneInteractive.current && LocalWindowInfo.current.isWindowFocused
}

/** Off/unfocused GIFs are decoded as a still frame, with a separate, bounded memory-cache key. */
@Composable
internal fun CollectionImage(url: String, description: String?, modifier: Modifier = Modifier,
    animate: Boolean = false, alpha: Float = 1f, contentScale: ContentScale = ContentScale.Crop,
    onSuccess: () -> Unit = {}, onError: () -> Unit = {}, onDrawable: (Drawable) -> Unit = {}) {
    val context = LocalContext.current
    val low = LocalRenderOptions.current.lowPower
    val bound = CollectionMotion.imageBound(low)
    val allowed = animate && collectionMotionActive() && !LocalReduceMotion.current
    var settled by remember(url, allowed) { mutableStateOf(false) }
    var animationFailed by remember(url, allowed, bound) { mutableStateOf(false) }
    var still by remember(url, bound) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(url, allowed) { if (allowed) { delay(750); settled = true } }
    val motion = allowed && settled && !animationFailed
    val request = remember(context, url, motion, bound) {
        ImageRequest.Builder(context).data(url).size(bound, bound).allowRgb565(false).allowHardware(false)
            .precision(coil.size.Precision.EXACT).scale(coil.size.Scale.FIT)
            .setParameter("ember.collection.frames", motion, CollectionMotion.imageCacheKey(motion, low))
            .placeholder(still).error(still)
            .apply { if (!motion) decoderFactory(BitmapFactoryDecoder.Factory()) }.build()
    }
    ArtworkImage(request, description, modifier.then(Modifier.alpha(alpha)), contentScale = contentScale,fade=!motion,
        onSuccess = { if (!motion) { still = it; onDrawable(it) }; onSuccess() },
        onError = { if (motion && still != null) animationFailed = true else onError() })
}

/** Known GIFs and direct videos work in either media slot; extensionless endpoints get one fallback. */
@Composable
internal fun CollectionMediaPreview(url: String?, enabled: Boolean, modifier: Modifier = Modifier,
    videoHint: Boolean = false, alpha: Float = 1f) {
    if (url.isNullOrBlank() || !enabled) return
    val active = collectionMotionActive()
    val play = enabled && active && !LocalReduceMotion.current && CollectionMotion.remote(url)
    var attempt by remember(url, play) { mutableIntStateOf(0) }
    if (!play || attempt > 1) return
    val primary = CollectionMotion.kind(url, videoHint)
    val kind = if (attempt == 0) primary else if (primary == CollectionMediaKind.IMAGE) CollectionMediaKind.VIDEO else CollectionMediaKind.IMAGE
    fun failed() { attempt = CollectionMotion.nextAttempt(url, attempt) }
    if (kind == CollectionMediaKind.VIDEO) CollectionVideoPreview(url, true, modifier, alpha, ::failed)
    else {
        var loaded by remember(url, attempt) { mutableStateOf(false) }
        CollectionImage(url, null, modifier, animate = true, alpha = if (loaded) alpha else 0f,
            onSuccess = { loaded = true }, onError = { loaded = false; failed() })
    }
}
