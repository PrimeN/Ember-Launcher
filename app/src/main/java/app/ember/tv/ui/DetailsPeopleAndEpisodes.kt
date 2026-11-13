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

/** Director, Writers and Created by as label/value lines; each name opens that person. */
@Composable
internal fun CrewLines(crew: List<Person>, onPerson: (Person) -> Unit) {
    val groups = remember(crew) {
        crew.groupBy { p ->
            val role = p.role.orEmpty().lowercase(java.util.Locale.ROOT)
            when {
                "director" in role -> "Director"
                "writ" in role || "screenplay" in role || "story" in role || "novel" in role -> "Writers"
                "creat" in role -> "Created by"
                else -> "Crew"
            }
        }.mapValues { (_, people) -> people.distinctBy { it.name }.take(4) }
            .toList().sortedBy { (label, _) -> listOf("Director", "Created by", "Writers", "Crew").indexOf(label) }
    }
    if (groups.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { (label, people) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (label == "Director" && people.size > 1) "Directors" else label, fontSize = 16.sp, color = Mute, modifier = Modifier.width(150.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    people.forEachIndexed { index, p ->
                        Focusable(onClick = { onPerson(p) }, radius = TvTokens.Shape.Small, container = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
                            Text(p.name + if (index < people.lastIndex) "," else "", fontSize = 16.sp, maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}
/** A cast member: a large round portrait (or initials), the name, and the character in a softer colour. */
@Composable
internal fun PersonCard(p: Person, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, label = "cast portrait")
    // Fixed width and two text lines keep long names from resizing the row.
    Focusable(onClick = onClick, modifier = Modifier.width(150.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Card, container = androidx.compose.ui.graphics.Color.Transparent, focusedContainer = androidx.compose.ui.graphics.Color.Transparent,
        focusedContent = Fg, outline = false, focusedScale = TvTokens.Focus.None) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.size(126.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(PanelHi)
                    .then(if (focused) Modifier.border(3.dp, androidx.compose.ui.graphics.Color.White, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                    fontSize = TvTokens.Type.Section.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Mute,
                )
                if (p.photo != null) ArtworkImage(model = p.photo, description = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(p.name, modifier = Modifier.fillMaxWidth(), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                color = if (focused) Fg else Fg.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(p.role.orEmpty(), modifier = Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 16.sp, color = Mute, minLines = 1,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
/** Small heading above each group of sources, with an optional status line. */
@Composable
internal fun GroupHeader(title: String, note: String?, noteIsWarning: Boolean = false,contentPadding:androidx.compose.ui.unit.Dp=48.dp) {
    Column(Modifier.padding(start = contentPadding, end = contentPadding, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Mute, letterSpacing = 1.2.sp)
        note?.let {
            Text(it, fontSize = 14.sp, color = if (noteIsWarning) MaterialTheme.colorScheme.primary else Mute)
        }
    }
}
@Composable
internal fun EpisodeCard(ep: Episode, selected: Boolean, width: androidx.compose.ui.unit.Dp=230.dp, watched:Boolean=false, onClick: () -> Unit) {
    val blurred=SpoilerPolicy.blur(LocalWatchedUi.current.blurEpisodes,watched,LocalWatchedUi.current.ready)
    val context=androidx.compose.ui.platform.LocalContext.current
    val fade=if(blurred)0 else PolishMotion.duration(TvTokens.Motion.Image,LocalRenderOptions.current,LocalReduceMotion.current)
    val image=remember(context,ep.thumbnail,blurred,fade) {coil.request.ImageRequest.Builder(context).data(ep.thumbnail).size(480,270).allowHardware(false).crossfade(fade)
        .apply {if(blurred)transformations(SpoilerBlurTransformation)}.build()}
    var imageReady by remember(image){mutableStateOf(false)}
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Focusable(onClick = onClick, modifier = Modifier.semantics {
            this.selected = selected
            contentDescription = "Season ${ep.season}, episode ${ep.episode}, ${ep.title}"
            stateDescription=if(watched)"Watched" else if(blurred)"Unwatched artwork blurred" else "Unwatched"
        }, container = if (selected) PanelHi else Panel,card=true) {
            Box(Modifier.width(width).height(width*9f/16f).clearAndSetSemantics { }) {
                if(!imageReady)ArtworkPlaceholder(null,Modifier.fillMaxSize())
                key(image) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(if(blurred && !imageReady)0f else 1f),
                    onSuccess={imageReady=true},
                    onError={imageReady=false},
                )
                }
                WatchedBadge(watched,Modifier.align(Alignment.TopEnd))
                if(blurred)Text(stringResource(R.string.tx_unwatched),fontSize=TvTokens.Type.Caption.sp,color=Color.White,modifier=Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(Color(0xBB202124),RoundedCornerShape(6.dp)).padding(horizontal=6.dp,vertical=3.dp))
                if (selected) {
                    Text(
                        stringResource(R.string.ui_selected),
                        fontSize = TvTokens.Type.Caption.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Text(stringResource(R.string.ui_episode_line, ep.episode, ep.title), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
        ep.released?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = Mute) }
    }
}
/** Compact vertical episode rows, with the same spoiler policy as the standard media page. */
@Composable
internal fun EmberXEpisodeRow(ep:Episode,selected:Boolean,watched:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val blurred=SpoilerPolicy.blur(LocalWatchedUi.current.blurEpisodes,watched,LocalWatchedUi.current.ready)
    val context=androidx.compose.ui.platform.LocalContext.current
    val request=remember(context,ep.thumbnail,blurred) {coil.request.ImageRequest.Builder(context).data(ep.thumbnail)
        .size(240,135).allowHardware(false).apply {if(blurred)transformations(SpoilerBlurTransformation)}.build()}
    var ready by remember(request){androidx.compose.runtime.mutableStateOf(false)}
    Focusable(onClick,modifier.fillMaxWidth().semantics {
        this.selected=selected;contentDescription="Season ${ep.season}, episode ${ep.episode}, ${ep.title}"
        stateDescription=if(watched)"Watched" else if(blurred)"Unwatched artwork blurred" else "Unwatched"
    },radius=TvTokens.Shape.Small,container=if(selected)EmberXColors.Selected.copy(alpha=.75f) else Color.Transparent,
        focusedContainer=EmberXColors.Selected,focusedContent=Color.White,focusedScale=TvTokens.Focus.Inline,card=true) {
        Row(Modifier.fillMaxWidth().padding(7.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(4.dp)).clearAndSetSemantics {}) {
                if(!ready)ArtworkPlaceholder(null,Modifier.fillMaxSize())
                AsyncImage(request,null,Modifier.fillMaxSize().alpha(if(blurred && !ready)0f else 1f),contentScale=ContentScale.Crop,onSuccess={ready=true},onError={ready=false})
                WatchedBadge(watched,Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("${ep.episode}. ${ep.title}",fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                ep.released?.let {Text(it,color=EmberXColors.Muted,fontSize=TvTokens.Type.Caption.sp,maxLines=1)}
                ep.progress?.takeIf {it.isFinite() && it>0f && it<1f}?.let {progress ->
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha=.15f))) {
                        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}
@Composable
internal fun EmberXMetadataPill(label:String,onClick:(()->Unit)?=null) {
    if(onClick==null)Text(label,fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,color=Fg,maxLines=1,
        modifier=Modifier.background(Color.White.copy(alpha=.10f),RoundedCornerShape(16.dp)).padding(horizontal=9.dp,vertical=5.dp))
    else Focusable(onClick,modifier=Modifier.widthIn(max=124.dp).semantics {contentDescription=label},radius=TvTokens.Shape.Large,
        container=Color.White.copy(alpha=.10f),focusedContainer=Color.White,focusedContent=Color.Black,outline=false,focusedScale=TvTokens.Focus.Inline) {
        Text(label,fontSize=TvTokens.Type.Caption.sp,lineHeight=14.sp,modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp),
            maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
