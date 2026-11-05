package app.ember.tv.ui

import androidx.core.graphics.scale
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class FocusTileKind { APP, MEDIA, COLLECTION }
internal data class FocusTile(val kind: FocusTileKind, val id: String, val artwork: String?)
internal data class FocusTileImage(val drawable: Drawable, val key: String)

internal class FocusBackdropState {
    var tile by mutableStateOf<FocusTile?>(null); private set
    var image by mutableStateOf<FocusTileImage?>(null); private set
    fun focus(next: FocusTile, loaded: FocusTileImage?) { tile = next; image = loaded }
    fun loaded(owner: FocusTile, loaded: FocusTileImage) { if (tile == owner) image = loaded }
    fun clear() { tile = null; image = null }
}
internal val LocalFocusBackdrop = compositionLocalOf<FocusBackdropState?> { null }
internal val LocalMediaFocusKind = compositionLocalOf { FocusTileKind.MEDIA }
private val LocalFocusImageModes = compositionLocalOf { FocusBackground.COLORS to FocusBackground.COLORS }

/** Each visible tile retains only its existing drawable; no second network request for a palette. */
internal class FocusTileBinding(private val state: FocusBackdropState?, private val tile: FocusTile) {
    private var image: FocusTileImage? = null
    fun focused() { state?.focus(tile, image) }
    fun loaded(drawable: Drawable, key: String = tile.id) {
        image = FocusTileImage(drawable, key)
        state?.loaded(tile, image!!)
    }
}
@Composable internal fun rememberFocusTile(kind: FocusTileKind, id: String, artwork: String? = null): FocusTileBinding {
    val state = LocalFocusBackdrop.current
    return remember(state, kind, id, artwork) { FocusTileBinding(state, FocusTile(kind, id, artwork)) }
}
@Composable internal fun rememberMediaFocus(meta: Meta) =
    rememberFocusTile(LocalMediaFocusKind.current, meta.mediaKey(), meta.background?.takeIf { it.isNotBlank() } ?: meta.poster)
@Composable internal fun rememberAppFocus(app: AppEntry, bitmap: Bitmap?, kind: String = "banner"): FocusTileBinding {
    val binding = rememberFocusTile(FocusTileKind.APP, "${app.pkg}:${app.artworkVersion}")
    val resources = LocalResources.current
    LaunchedEffect(binding, bitmap, resources, kind) { if(bitmap!=null)binding.loaded(bitmap.toDrawable(resources), "${app.pkg}:${app.artworkVersion}:$kind") }
    return binding
}
internal fun Modifier.focusBackdrop(binding: FocusTileBinding) = onFocusChanged { if (it.hasFocus) binding.focused() }

/** Prefetch and visible cards must agree on whether palette sampling requires software pixels. */
@Composable internal fun tileNeedsSoftwarePixels():Boolean {
    val modes=LocalFocusImageModes.current
    val mode=if(LocalMediaFocusKind.current==FocusTileKind.COLLECTION)modes.second else modes.first
    return LocalRenderOptions.current.colorAnalysis && LocalFocusBackdrop.current!=null && mode==FocusBackground.COLORS
}

/** Software tile pixels can be sampled without copying a large hardware bitmap. */
@Composable internal fun tileImageRequest(model: Any?,fade:Boolean=false): Any? {
    val context = LocalContext.current
    val enabled = tileNeedsSoftwarePixels()
    val duration=if(fade)PolishMotion.duration(TvTokens.Motion.Image,LocalRenderOptions.current,LocalReduceMotion.current) else 0
    return remember(context, model, enabled,duration) {
        if(model==null || !enabled && duration==0)model else {
            val builder=if(model is ImageRequest)model.newBuilder() else ImageRequest.Builder(context).data(model)
            if(enabled)builder.allowHardware(false)
            builder.crossfade(duration).build()
        }
    }
}

/** Colors only: bounded cache holds at most 128 integers, never full-size bitmaps. */
private object FocusColors {
    private val colors = object : LinkedHashMap<String, Int>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }
    suspend fun resolve(key: String, drawable: Drawable): Int? = withContext(Dispatchers.Default) {
        synchronized(colors) { colors[key] }?.let { return@withContext it }
        val small = runCatching {
            if (drawable is BitmapDrawable) {
                // Tile requests explicitly disable hardware allocation; never copy a full hero here.
                if (drawable.bitmap.config == Bitmap.Config.HARDWARE) return@runCatching null
                drawable.bitmap.scale(48, 48)
            } else drawable.constantState?.newDrawable()?.mutate()?.toBitmap(48, 48)
        }.getOrNull() ?: return@withContext null
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            TilePalette.color(pixels)?.also { synchronized(colors) { colors[key] = it } }
        } finally {
            if (drawable !is BitmapDrawable || small !== drawable.bitmap) small.recycle()
        }
    }
}

/** Scope resets on page/profile/folder changes; canceled work cannot tint a different page. */
@Composable internal fun FocusBackdropHost(vm: HomeViewModel, scope: Any, enabled: Boolean, content: @Composable () -> Unit) {
    val state = remember(scope, enabled) { FocusBackdropState() }
    CompositionLocalProvider(LocalFocusBackdrop provides state.takeIf { enabled },
        LocalFocusImageModes provides (vm.look.mediaFocusBackground to vm.look.collectionFocusBackground)) {
        Box(Modifier.fillMaxSize()) {
            if (enabled) FocusBackdropLayer(state, vm.look.appFocusColors, vm.look.mediaFocusBackground, vm.look.collectionFocusBackground)
            content()
        }
    }
}

@Composable private fun FocusBackdropLayer(state: FocusBackdropState, apps: Boolean, media: String, collections: String) {
    val tile = state.tile
    val image = state.image
    val requestedMode = when (tile?.kind) {
        FocusTileKind.APP -> if (apps) FocusBackground.COLORS else FocusBackground.OFF
        FocusTileKind.MEDIA -> media
        FocusTileKind.COLLECTION -> collections
        null -> FocusBackground.OFF
    }
    val analysis=LocalRenderOptions.current.colorAnalysis
    val mode=if(requestedMode==FocusBackground.COLORS && !analysis)FocusBackground.OFF else requestedMode
    val base = Ink
    var color by remember(state) { mutableStateOf<Int?>(null) }
    var artwork by remember(state) { mutableStateOf<String?>(null) }
    var settled by remember(state) { mutableStateOf<Triple<FocusTile?, String, String?>?>(null) }
    val interactive = LocalSceneInteractive.current
    val sink = LocalArtworkPresented.current
    LaunchedEffect(tile, mode, image, interactive) {
        if (!interactive) return@LaunchedEffect
        val selection = Triple(tile, mode, image?.key.takeIf { mode == FocusBackground.COLORS })
        if (selection == settled) return@LaunchedEffect
        if (tile == null) { color = null; artwork = null; settled = null; return@LaunchedEffect }
        // Clear previous hero sampling when displaying a gradient or a new optional backdrop.
        sink(ReadyArtwork(null, null))
        if (mode == FocusBackground.OFF) { color = null; artwork = null; settled = selection; return@LaunchedEffect }
        delay(180)
        // Retain the previous settled tint during scrolling rather than flashing grey between tiles.
        color = if (mode == FocusBackground.COLORS && image != null)
            FocusColors.resolve("${tile.kind}:${tile.id}:${image.key}", image.drawable) else null
        artwork = tile.artwork.takeIf { mode == FocusBackground.ARTWORK }
        settled = selection
    }
    if (tile == null) return
    val render = LocalRenderOptions.current
    val tint by animateColorAsState(color?.let(::Color) ?: base,
        tween(render.animationMillis(if (render.lowPower) 0 else 240, LocalReduceMotion.current)), label = "tileColor")
    Box(Modifier.fillMaxSize().background(base)) {
        // A gradient is cheaper than another large image or a full-screen blur.
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(base, tint, if (Ui.isDay) .22f else .55f), base), radius = 1800f)))
        artwork?.let { url ->
            val context = LocalContext.current
            val request = remember(context, url, render.lowPower) { ImageRequest.Builder(context).data(url)
                .size(if (render.lowPower) 960 else 1920, if (render.lowPower) 540 else 1080).build() }
            AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = .45f)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, base.copy(alpha = .45f), base))))
    }
}

@Composable internal fun FocusBackgroundSettings(vm: HomeViewModel) {
    ToggleRow("App colors", "Use the focused app's colors behind the page.", vm.look.appFocusColors) { vm.look.updateAppFocusColors(!vm.look.appFocusColors) }
    TextBackgroundChoice("Media rows", vm.look.mediaFocusBackground, vm.look::updateMediaFocusBackground)
    TextBackgroundChoice("Collections", vm.look.collectionFocusBackground, vm.look::updateCollectionFocusBackground)
}
@Composable private fun TextBackgroundChoice(label: String, value: String, onChange: (String) -> Unit) {
    androidx.tv.material3.Text(label, color = Fg)
    ButtonRow { FocusBackground.choices.forEach { (id, name) -> Chip(name, selected = id == value) { onChange(id) } } }
}
