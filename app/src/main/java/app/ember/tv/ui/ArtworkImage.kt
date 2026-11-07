package app.ember.tv.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil.compose.AsyncImage

internal enum class ArtworkPhase { LOADING, READY, MISSING }
internal fun artworkPhase(hasModel:Boolean,loaded:Boolean,failed:Boolean)=when {
    !hasModel || failed->ArtworkPhase.MISSING
    loaded->ArtworkPhase.READY
    else->ArtworkPhase.LOADING
}

/** Fixed geometry, quiet tonal fallback and no repeating shimmer or extra image download. */
@Composable internal fun ArtworkPlaceholder(title:String?,modifier:Modifier=Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(PanelHi,Panel)))
        .then(if(title.isNullOrBlank())Modifier else Modifier.semantics {contentDescription=title}),contentAlignment=Alignment.Center) {
        Icon(Icons.Outlined.Image,null,tint=Mute.copy(alpha=.7f),modifier=Modifier.padding(TvTokens.Space.S.dp).size(24.dp))
    }
}

@Composable internal fun ArtworkImage(model:Any?,description:String?,modifier:Modifier=Modifier,
    contentScale:ContentScale=ContentScale.Crop,fade:Boolean=true,iconCache:Boolean=false,onError:()->Unit={},onSuccess:(Drawable)->Unit={}) {
    var loaded by remember(model){mutableStateOf(false)}
    var failed by remember(model){mutableStateOf(false)}
    val phase=artworkPhase(model!=null,loaded,failed)
    val request=tileImageRequest(model,fade,iconCache)
    val context=androidx.compose.ui.platform.LocalContext.current
    val cached=request is coil.request.ImageRequest && IconArtworkCache.cached(request)
    val cacheReady=!cached || IconArtworkCache.epoch!="loading"
    val loader=if(cached && cacheReady)IconArtworkCache.loader(context)
        else coil.Coil.imageLoader(context)
    Box(modifier.then(if(description.isNullOrBlank())Modifier else Modifier.semantics {contentDescription=description})) {
        if(phase!=ArtworkPhase.READY)ArtworkPlaceholder(null,Modifier.matchParentSize())
        if(model!=null && cacheReady)key(request) {
            AsyncImage(request,null,imageLoader=loader,modifier=Modifier.matchParentSize(),contentScale=contentScale,
                onSuccess={loaded=true;failed=false;onSuccess(it.result.drawable)},
                onError={failed=true;onError()})
        }
    }
}
