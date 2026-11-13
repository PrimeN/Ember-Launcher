package app.ember.tv.ui
import androidx.compose.runtime.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import app.ember.tv.data.Person
import app.ember.tv.data.appearance
import app.ember.tv.data.mediaKey
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.DetailsUi
import app.ember.tv.data.Episode
import app.ember.tv.data.SourceKind
import app.ember.tv.data.StreamOption
import app.ember.tv.streaming.Offer
import coil.compose.AsyncImage
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Dns
import androidx.tv.material3.Icon

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceRow(
    s: StreamOption,
    formatted: Pair<String, String>?,
    /** Every badge the source matches; ones switched off singly (#194) are not drawn but still cover their category. */
    allBadges: List<app.ember.tv.data.BadgeFilter>,
    runtime: String?,
    hiddenCategories: Set<String>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val images = allBadges.filter { it.isEnabled }
    val accent = MaterialTheme.colorScheme.primary
    if(isEmberX) {
        EmberXSourceRow(s,formatted,allBadges,runtime,hiddenCategories,onClick,onLongClick)
        return
    }
    if (formatted != null) {
        FormattedSourceRow(formatted, images, onClick, onLongClick)
        return
    }
    Focusable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QualityColumn(s.qualityLabel, s.kind, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    app.ember.tv.data.Badges.clean(s.title.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: s.name),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Keep only metadata not already represented by the selected badge pack.
                val badges = listOfNotNull(runtime?.let { "⏱ $it" }) +
                    app.ember.tv.data.DetailedBadgeCategories.textTags(s, hiddenCategories, languageLimit = 3, badges = allBadges) +
                    listOfNotNull(s.sizeGb?.let { "%.1f GB".format(it) })
                if (badges.isNotEmpty() || images.isNotEmpty()) {
                    // One row (wrapping when a source has many details): image badges first, then the
                    // text tags, all the same height and shape.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        images.forEach { SourceBadge(it) }
                        badges.forEach { Badge(it) }
                    }
                }
            }
            Text(s.addonName, fontSize = 12.sp, color = Mute, maxLines = 1)
        }
    }
}
/** Fixed quality column, title, and two badge lines; provider details live in More. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmberXSourceRow(s:StreamOption,formatted:Pair<String,String>?,allBadges:List<app.ember.tv.data.BadgeFilter>,
    runtime:String?,hiddenCategories:Set<String>,onClick:()->Unit,onLongClick:()->Unit) {
    val images=allBadges.filter {it.isEnabled}
    val title=app.ember.tv.data.Badges.clean(formatted?.first ?: s.title.lineSequence().firstOrNull()?.takeIf {it.isNotBlank()} ?: s.name)
    // Duration and file size lead even when image badges or a custom formatter are enabled.
    val primaryPills=emberXSourcePrimaryPills(s,runtime)
    val tags=app.ember.tv.data.DetailedBadgeCategories.textTags(s,emptySet())
    val description=app.ember.tv.data.Badges.clean(formatted?.second ?: (primaryPills+tags).joinToString(" · "))
    val pills=if(formatted!=null && images.isEmpty() && hiddenCategories.isEmpty())listOf(description.replace(Regex("\\s+")," ").trim()).filter(String::isNotBlank)
        else app.ember.tv.data.DetailedBadgeCategories.textTags(s,hiddenCategories,badges=allBadges)
    var info by remember(s) {mutableStateOf(false)}
    val density=LocalDensity.current
    val nameHeight=maxOf(28.dp,with(density){18.sp.toDp()})
    val formatsHeight=maxOf(24.dp,with(density){18.sp.toDp()})
    val badgesHeight=formatsHeight*2+6.dp
    val playFocus=remember {FocusRequester()}
    val infoFocus=remember {FocusRequester()}
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    // Playback and information are siblings: a clickable Surface must not own the info button's focus.
    Row(Modifier.fillMaxWidth().height(nameHeight+badgesHeight+26.dp)
        .background(Panel.copy(alpha=.45f),RoundedCornerShape(14.dp)),verticalAlignment=Alignment.CenterVertically) {
        Focusable(onClick,onLongClick=onLongClick,focusedScale=TvTokens.Focus.None,modifier=Modifier.weight(1f).fillMaxHeight()
            .focusRequester(playFocus).focusProperties {if(rtl)left=infoFocus else right=infoFocus}
            .semantics {contentDescription=listOf(title,description,s.addonName,s.qualityLabel,s.kind.label).filter(String::isNotBlank).joinToString(". ")}) {
            Row(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(64.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(s.qualityLabel,fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(s.kind.label,fontSize=TvTokens.Type.Caption.sp,lineHeight=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                        color=if(s.kind==SourceKind.UNCACHED)Mute else MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(title,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.fillMaxWidth().height(nameHeight))
                    FlowRow(Modifier.fillMaxWidth().height(badgesHeight),horizontalArrangement=Arrangement.spacedBy(6.dp),
                        verticalArrangement=Arrangement.spacedBy(6.dp),maxLines=2) {
                        primaryPills.forEach {tag-> EmberXSourcePill(tag,formatsHeight) }
                        images.forEach {badge->
                            Box(Modifier.height(formatsHeight),contentAlignment=Alignment.Center){SourceBadge(badge)}
                        }
                        pills.forEach {tag-> EmberXSourcePill(tag,formatsHeight) }
                    }
                }
            }
        }
        Focusable(onClick={info=true},modifier=Modifier.padding(end=8.dp).size(32.dp)
            .focusRequester(infoFocus).focusProperties {if(rtl)right=playFocus else left=playFocus}
            .semantics {contentDescription="Source details for $title"},radius=TvTokens.Shape.Pill,
            container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=TvTokens.Focus.None) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Info,null,modifier=Modifier.size(20.dp))}
        }
    }
    if(info)MediaDialog("Source details",{info=false}) {
        Text(title,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        Text(description,fontSize=14.sp,color=Mute)
        Text(listOf(s.qualityLabel,s.kind.label).filter(String::isNotBlank).joinToString(" · "),fontSize=12.sp,color=Mute)
        if(s.addonName.isNotBlank())Text(stringResource(R.string.tx_source, s.addonName),fontSize=13.sp,color=Mute)
        if(images.isNotEmpty())BadgeImages(images)
    }
}
@Composable
private fun EmberXSourcePill(tag:String,height:androidx.compose.ui.unit.Dp) {
    Box(Modifier.height(height).widthIn(max=180.dp).clip(RoundedCornerShape(6.dp)).background(PanelHi)
        .padding(horizontal=7.dp),contentAlignment=Alignment.Center) {
        Text(tag,fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
/** An addon source shown with the user's name and description templates (Settings > Stremio). */
@Composable
private fun FormattedSourceRow(formatted: Pair<String, String>, images: List<app.ember.tv.data.BadgeFilter>, onClick: () -> Unit, onLongClick: () -> Unit) {
    val name = app.ember.tv.data.Badges.clean(formatted.first)
    val description = app.ember.tv.data.Badges.clean(formatted.second)
    Focusable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Source name on the left, description beside it.
            Text(
                name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
                modifier = Modifier.width(260.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    description,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                if (images.isNotEmpty()) BadgeImages(images)
            }
        }
    }
}
/** Quality on top (4K, FHD, HD, SD) and how the source plays underneath. */
@Composable
private fun QualityColumn(quality: String, kind: SourceKind, accent: Color) {
    Column(Modifier.width(96.dp)) {
        Text(quality.ifBlank { "—" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            kind.label,
            fontSize = 12.sp,
            color = if (kind == SourceKind.UNCACHED) Mute else accent,
        )
    }
}
/** A streaming service that has the title: opens its TV app, or the store when it is not installed. */
@Composable
internal fun ServiceRow(o: Offer, installed: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QualityColumn(o.quality, SourceKind.SERVICE, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(o.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(o.label)
                    if (!installed) Badge("App not installed")
                }
            }
            Text(if (installed) "Opens app" else "Get app", fontSize = 12.sp, color = Mute, maxLines = 1)
        }
    }
}
@Composable
private fun BadgeImages(list: List<app.ember.tv.data.BadgeFilter>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { SourceBadge(it) }
    }
}
/**
 * A pack's styling applies to its image and to the label shown while loading or on failure.
 */
private const val ASSET_PREFIX = "file:///android_asset/"
/** Width ÷ height of a PNG in the app's assets, from its header; null if it is not a PNG. */
private fun pngRatio(context: android.content.Context, path: String): Float? = runCatching {
    context.assets.open(path).use { input ->
        val h = ByteArray(24)
        if (input.read(h) < 24) return null
        val w = java.nio.ByteBuffer.wrap(h, 16, 4).int
        val ht = java.nio.ByteBuffer.wrap(h, 20, 4).int
        if (w <= 0 || ht <= 0) null else BadgeArtwork.ratio(w.toFloat(),ht.toFloat())
    }
}.getOrNull()
private val bundledBadgeRatios=WeightedArtworkCache<String,Float>(4096){4}
private fun bundledBadgeRatio(context:android.content.Context,path:String):Float? {
    bundledBadgeRatios.get(path)?.let {return it}
    return pngRatio(context,path)?.also {bundledBadgeRatios.put(path,it)}
}
@Composable
internal fun SourceBadge(b: app.ember.tv.data.BadgeFilter) {
    val url = b.imageURL?.takeIf { it.isNotBlank() }
    val appearance = remember(b) { b.appearance() }
    val imageTint = remember(b.imageTint) { app.ember.tv.data.Badges.color(b.imageTint)?.let { androidx.compose.ui.graphics.ColorFilter.tint(Color(it)) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bundledRatio = remember(context, url) {
        url?.takeIf { it.startsWith(ASSET_PREFIX) }?.let { bundledBadgeRatio(context, it.removePrefix(ASSET_PREFIX)) }
    }
    val painter = if (url != null) {
        val request = remember(context, url) {
            coil.request.ImageRequest.Builder(context).data(url)
                .size(BadgeArtwork.MAX_WIDTH, BadgeArtwork.MAX_HEIGHT)
                .precision(coil.size.Precision.INEXACT).scale(coil.size.Scale.FIT)
                .allowRgb565(false).build()
        }
        coil.compose.rememberAsyncImagePainter(request)
    } else null
    val imageReady = painter?.state is coil.compose.AsyncImagePainter.State.Success
    val ratio = if (imageReady) bundledRatio ?: BadgeArtwork.ratio(painter.intrinsicSize.width, painter.intrinsicSize.height) else null
    val fill = appearance.background?.let(::Color) ?: if (appearance.filled && !imageReady) PanelHi else Color.Transparent
    BadgeBox(fill, appearance.border?.let(::Color), appearance.borderWidth.dp, appearance.radius.dp) {
        if (ratio != null && painter != null) {
            androidx.compose.foundation.Image(painter, contentDescription = b.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                colorFilter = imageTint,
                modifier = Modifier.height(BADGE_IMAGE_H).width(BADGE_IMAGE_H * ratio))
        } else {
            Text(b.name, fontSize = TvTokens.Type.Caption.sp, fontWeight = FontWeight.SemiBold,
                color = appearance.foreground?.let(::Color) ?: Fg, maxLines = 1, softWrap = false)
        }
    }
}
/** Every tag under a source has this height, image or text. */
private val BADGE_H = 24.dp
private val BADGE_IMAGE_H = 15.dp
/** The tag shape: a rounded box of the badge height with the content centred. */
@Composable
private fun BadgeBox(fill: Color = PanelHi, border: Color? = null,
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp, radius: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .height(BADGE_H)
            .clip(shape)
            .background(fill)
            .then(if (border != null && borderWidth > 0.dp) Modifier.border(borderWidth, border, shape) else Modifier)
            .padding(horizontal = 7.dp, vertical = if (border != null) borderWidth else 0.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
@Composable
private fun Badge(text: String) {
    BadgeBox { Text(text, fontSize = TvTokens.Type.Caption.sp, maxLines = 1) }
}
