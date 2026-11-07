package app.ember.tv.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import coil.request.SuccessResult
import kotlinx.coroutines.ensureActive

internal fun focusedArtworkCandidates(tile:FocusTile)=listOfNotNull(tile.artwork,tile.poster)
    .filter(String::isNotBlank).distinct()

/** Wide enough to stand in for a backdrop: landscape cards and banners, not posters or square covers. */
internal fun widePreview(drawable:Drawable)=widePreview(drawable.intrinsicWidth,drawable.intrinsicHeight)
internal fun widePreview(width:Int,height:Int)=width>0 && height>0 && width>=height*1.3f

/** The selected card is usable immediately while authenticated/full-size artwork loads or fails. */
@Composable
internal fun FocusArtworkBackdrop(tile:FocusTile,preview:FocusTileImage?) {
    val context=LocalContext.current
    val options=LocalRenderOptions.current
    val window=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val size=options.heroSize(window.width,window.height)
    val candidates=remember(tile){focusedArtworkCandidates(tile)}
    var full by remember(tile,options.heroStorageKey,size) {mutableStateOf<Drawable?>(null)}
    var loadedSource by remember(tile,options.heroStorageKey,size) {mutableStateOf<String?>(null)}
    var viewport by remember {mutableStateOf<HeroViewport?>(null)}
    val sink=LocalArtworkPresented.current
    LaunchedEffect(tile,options.heroStorageKey,size) {
        for(url in candidates) {
            val request=heroRequest(context,heroArtworkUrl(url,options,url!=tile.poster),size,options)
            val result=HeroImages.loader(context).execute(request) as? SuccessResult
            ensureActive()
            if(result!=null) {loadedSource=url;full=result.drawable;break}
        }
    }
    // The tile's own picture stands in only when it is wide; a poster stretched across the screen
    // looks broken (#56), so the tinted background stays until the wide picture is decoded.
    val drawable=full ?: preview?.drawable?.takeIf(::widePreview)
    val privateSource=listOfNotNull(tile.artwork,tile.poster).firstOrNull {it.startsWith("ember-art://")}
    val art=ReadyArtwork(null,drawable,viewport=viewport,source=privateSource ?: loadedSource ?: preview?.key ?: tile.artwork ?: tile.poster)
    LaunchedEffect(drawable,viewport,art.source) {sink(art)}
    Box(Modifier.fillMaxSize().onGloballyPositioned {coordinates->
        val point=coordinates.positionInRoot()
        viewport=HeroViewport(point.x,point.y,coordinates.size.width.toFloat(),coordinates.size.height.toFloat())
    }) {
        FramedHeroImage(art,Modifier.fillMaxSize())
    }
}
