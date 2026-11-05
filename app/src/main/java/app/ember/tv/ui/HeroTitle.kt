package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.Meta
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Posters
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Text reserves the same space and remains the fallback and accessible title. */
@Composable
internal fun HeroTitle(meta: Meta, modifier: Modifier = Modifier, expanded: Boolean = false,
    details: Boolean = false, largeText: Boolean = false) {
    val logo = meta.logo?.takeIf { it.isNotBlank() } ?: if(app.ember.tv.mediaserver.ServerRef.of(meta)==null)Posters.logoUrl(meta, PosterConfig()) else null
    var loaded by remember(logo) { mutableStateOf(false) }
    var failed by remember(logo) { mutableStateOf(false) }
    val context = LocalContext.current
    val request = remember(context,logo) { logo?.let { ImageRequest.Builder(context).data(it).size(720,220).allowRgb565(false).build() } }
    val font = if(details) { if(largeText)TvTokens.Type.DetailsLarge else TvTokens.Type.Details } else if(expanded)TvTokens.Type.HeroExpanded else TvTokens.Type.Hero
    val line = if(details)42 else if(expanded)44 else 34
    val height = if(isEmberX && details)60.dp else if(details || expanded)78.dp else 64.dp
    Box(modifier.fillMaxWidth().heightIn(min=height).semantics { contentDescription=meta.name }, contentAlignment=Alignment.CenterStart) {
        Text(meta.name, fontSize=font.sp, lineHeight=line.sp, fontWeight=if(details)FontWeight.Bold else FontWeight.SemiBold,
            maxLines=if(details && largeText)3 else 2, overflow=TextOverflow.Ellipsis, color=Fg,
            style=TextStyle(shadow=Shadow(if(Ui.isDay)Color.White.copy(alpha=.6f) else Color.Black.copy(alpha=.8f),Offset(0f,1f),4f)),
            modifier=Modifier.alpha(if(loaded && !failed)0f else 1f).clearAndSetSemantics { })
        if(request!=null && !failed)Box(Modifier.matchParentSize(),contentAlignment=Alignment.CenterStart) {
            AsyncImage(request,null,contentScale=ContentScale.Fit,alignment=Alignment.CenterStart,
                modifier=Modifier.widthIn(max=if(expanded)400.dp else 360.dp).fillMaxSize().alpha(if(loaded)1f else 0f),
                onSuccess={loaded=true},onError={failed=true;loaded=false})
        }
    }
}

/** A portrait fallback should fit on the right instead of being magnified into a landscape. */
internal fun fitHeroPortrait(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toDouble()/height < 1.45
