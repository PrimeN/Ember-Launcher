package app.ember.tv.ui

import app.ember.tv.data.TileLabels

import app.ember.tv.isSaved
import app.ember.tv.resumeLabel
import app.ember.tv.toggleWatchlist
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search as IcSearch
import androidx.compose.material.icons.outlined.Settings as IcSettings
import androidx.compose.material.icons.outlined.Home as IcHome
import androidx.compose.material.icons.outlined.BookmarkBorder as IcBookmarkBorder
import androidx.compose.material.icons.outlined.LiveTv as IcLiveTv
import androidx.compose.material.icons.outlined.Explore as IcExplore
import androidx.compose.material.icons.outlined.VideoLibrary as IcVideoLibrary
import androidx.compose.material.icons.outlined.Dns as IcDns
import androidx.compose.material.icons.outlined.Apps as IcApps
import androidx.compose.material.icons.outlined.Tune as IcTune
import androidx.compose.material.icons.outlined.Circle as IcCircle
import androidx.compose.material.icons.outlined.Wallpaper as IcScreensaver
import androidx.compose.material.icons.outlined.Person as IcProfile

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.ember.tv.data.resumeLabel
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import app.ember.tv.data.HeroBanner
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.AppEntry
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import app.ember.tv.data.NavTabs
import app.ember.tv.iptv.Channel
import app.ember.tv.iptv.IptvController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** A profile's round avatar: its first letter on its colour. */
@Composable
fun ProfileAvatar(p: app.ember.tv.data.Profile, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(app.ember.tv.data.Profile.COLORS.getOrElse(p.color) { app.ember.tv.data.Profile.COLORS[0] })
    // #259: read as the profile's name, not its initial.
    Focusable(onClick = onClick, modifier = modifier.size(size).semantics { contentDescription = p.name }, radius = TvTokens.Shape.Pill, container = color, focusedContainer = color, focusedContent = Color(0xFF1F1F1F), focusedScale = TvTokens.Focus.Round) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(p.name.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F), modifier = Modifier.clearAndSetSemantics { })
            // #222: the profile's photo over its colour; the letter shows until it loads, or if it can't.
            app.ember.tv.data.Profile.usablePhoto(p.photo)?.let { photo ->
                coil.compose.AsyncImage(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
        }
    }
}
/** A 16:9 card: the title's backdrop (poster if there is none) with its name over a soft shade. */
@Composable
fun WideCard(meta: Meta, saved: Boolean, width: androidx.compose.ui.unit.Dp = CardW, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    val actions = LocalTitleActions.current
    val backdrop = rememberMediaFocus(meta)
    var focused by remember { mutableStateOf(false) }
    val height = width * 9f / 16f
    val small = width < 200.dp
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Focusable(
            onClick = onClick,
            onLongClick = actions?.let { action -> { action(meta) } },
            modifier = Modifier.width(width).height(height).restoreFocus(meta.mediaKey()).mediaSemantics(meta, saved).focusBackdrop(backdrop).onFocusChanged {
                focused = it.isFocused || it.hasFocus
                if (focused) onFocus()
            },
            container = PanelHi,card=true,
        ) {
            Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                LandscapeImage(meta, Modifier.fillMaxSize(), onImage = backdrop::loaded)
                WatchedBadge(LocalWatchedUi.current.badge(meta),Modifier.align(Alignment.TopStart))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = if (small) 8.dp else 12.dp, end = 8.dp, bottom = if (meta.safeProgress != null) 12.dp else 8.dp)) {
                    LandscapeTitle(meta, if (small) 12.sp else 15.sp, logoHeight = height * 0.34f, logoWidth = width * 0.62f)
                    (meta.safeProgress?.let { p -> listOfNotNull(
                        if (meta.season != null && meta.episode != null) "S${meta.season} E${meta.episode}" else null,
                        "${(p.coerceIn(0f, 1f) * 100).toInt()}% watched").joinToString(" · ") } ?: meta.badge)?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 12.sp, color = Color(0xFFDADCE0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (saved) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(9.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                meta.safeProgress?.let { p ->
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(3.dp).clip(CircleShape).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        // Details under the focused card only; the space is kept so rows do not jump, unless
        // Labels under tiles > Titles is off (#43).
        if (tileLabelShown(TileLabels.MEDIA)) Text(
            if (focused) meta.resumeLabel() ?: listOfNotNull(meta.releaseInfo, meta.type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }).joinToString(" · ") else "",
            fontSize = 12.sp,
            color = Mute,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics { },
        )
    }
}
/** An app's artwork fills its tile, with its name retained for accessibility. */
@Composable
fun AppBanner(app: AppEntry, dim: Boolean = false, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit = {}, width: androidx.compose.ui.unit.Dp = 196.dp) {
    val bitmap=rememberAppArtwork(app)
    val backdrop = rememberAppFocus(app, bitmap)
    Focusable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(width).height(width * 0.5625f).focusBackdrop(backdrop).onFocusChanged {
            if (it.isFocused || it.hasFocus) onFocus()
        },
        container = PanelHi,card=true,
    ) {
        if(bitmap!=null)Image(bitmap.asImageBitmap(),
            contentDescription = app.label + if (dim) " (hidden)" else "",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            alpha = if (dim) 0.4f else 1f)
        else ArtworkPlaceholder(app.label,Modifier.fillMaxSize())
    }
}
/**
 * An app as a round icon. Uses the app's own icon (its adaptive icon
 * drawn edge to edge, so the circle is filled), falling back to the banner. Name under the focused one.
 */
@Composable
fun AppCircle(app: AppEntry, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit = {}, width: androidx.compose.ui.unit.Dp = 112.dp,
    /** #211: a rounded square instead of a circle. */
    square: Boolean = false) {
    val clip = if (square) RoundedCornerShape(AppShapes.squareRadius(width.value).dp) else CircleShape
    val bitmap=rememberAppArtwork(app,circle=true)
    val backdrop = rememberAppFocus(app, bitmap, kind = "circle")
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Focusable(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.size(width).focusBackdrop(backdrop).onFocusChanged {
                focused = it.isFocused || it.hasFocus
                if (focused) onFocus()
            },
            radius = if (square) AppShapes.squareRadius(width.value) else TvTokens.Shape.Pill,
            container = Color(0xFFF1F3F4),
            focusedScale = TvTokens.Focus.Round,
            card=true,
        ) {
            if(bitmap!=null)Image(bitmap.asImageBitmap(), contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(clip))
            else ArtworkPlaceholder(app.label,Modifier.fillMaxSize())
        }
        if (tileLabelShown(TileLabels.APPS)) Text(
            if (focused) app.label else "",
            fontSize = 12.sp,
            color = Fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(16.dp),
        )
    }
}
/** App icon shapes on Home (#211). */
internal object AppShapes {
    val options = listOf("banner" to "Banners", "circle" to "Circles", "square" to "Squares")
    fun normalize(value: String?): String = value?.takeIf { v -> options.any { it.first == v } } ?: "circle"
    /** A square's corner, in dp: about a fifth of its side, like a phone's app icons. */
    fun squareRadius(width: Float): Int = (width * .22f).toInt().coerceIn(8, 28)
}

// ---------------------------------------------------------------- Live
/**
 * "On now" on Live TV: favourites and recent channels first, otherwise the first channels with
 * a programme on now. Each card has a red LIVE badge, the programme, time left and progress.
 */
@Composable
fun LiveNowRow(iptv:IptvController,modifier:Modifier=Modifier,onOptions:(Channel)->Unit={},
    onFocus:(Channel)->Unit={},firstFocus:androidx.compose.ui.focus.FocusRequester?=null,onPlay:(List<Channel>,Int)->Unit) {
    val clock=iptv.clock
    val list=remember(iptv.visibleChannels,iptv.favourites,iptv.recent,iptv.guide,clock/60_000) {
        app.ember.tv.iptv.LivePresentation.featured(iptv.visibleChannels,iptv.favourites,iptv.recent,iptv.guide,clock)
    }
    val providers=remember(iptv.sources){iptv.sources.associate {it.id to it.name}}
    if(list.isEmpty())return
    Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.tx_on_now),fontSize=16.sp,fontWeight=FontWeight.Medium,color=Fg,modifier=Modifier.padding(start=4.dp).semantics {heading()})
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width=CardSizes.width(maxWidth,3.25f,18.dp,6.dp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(18.dp),contentPadding=PaddingValues(horizontal=6.dp,vertical=10.dp)) {
                itemsIndexed(list,key={_,c->"now:"+c.key}) {index,channel ->
                    LiveFeaturedCard(channel,iptv.nowNext(channel).first,clock,providers[channel.sourceId].orEmpty(),width,
                        modifier=if(index==0 && firstFocus!=null)Modifier.focusRequester(firstFocus) else Modifier,
                        onFocus={onFocus(channel)},onOptions={onOptions(channel)}) {onPlay(list,index)}
                }
            }
        }
    }
}
@Composable
internal fun LiveFeaturedCard(channel:Channel,programme:app.ember.tv.iptv.Programme?,clock:Long,source:String,
    width:androidx.compose.ui.unit.Dp,modifier:Modifier=Modifier,onFocus:()->Unit,onOptions:()->Unit,onPlay:()->Unit) {
    val image=programme?.image
    var failed by remember(image) {mutableStateOf(false)}
    var logoFailed by remember(channel.logo) {mutableStateOf(false)}
    val progress=app.ember.tv.iptv.LivePresentation.progress(programme,clock)
    val remaining=app.ember.tv.iptv.LivePresentation.remainingMinutes(programme,clock)
    Column(Modifier.width(width),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Focusable(onClick=onPlay,onLongClick=onOptions,modifier=modifier.width(width).height(width*9f/16f)
            .onFocusChanged {if(it.isFocused)onFocus()}.semantics {
                contentDescription=listOfNotNull(programme?.title ?: channel.name,channel.name,source.takeIf(String::isNotBlank),remaining?.let {"$it minutes remaining"}).distinct().joinToString(", ")
                progress?.let {progressBarRangeInfo=androidx.compose.ui.semantics.ProgressBarRangeInfo(it,0f..1f)}
            },container=LiveColors.Current,radius=TvTokens.Shape.Control,focusedScale=TvTokens.Focus.Inline,card=true) {
            Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                if(image!=null && !failed)AsyncImage(image,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop,onError={failed=true})
                else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(LiveColors.Current,LiveColors.Background))),contentAlignment=Alignment.Center) {
                    if(channel.logo!=null && !logoFailed)AsyncImage(channel.logo,null,Modifier.fillMaxSize().padding(24.dp),contentScale=ContentScale.Fit,onError={logoFailed=true})
                    else Text(channel.name,color=LiveColors.Text,fontSize=20.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(18.dp))
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent,1f to Color(0x88000000))))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.tx_live),color=Color.White,fontSize=TvTokens.Type.Caption.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(3.dp)).background(LiveRed).padding(horizontal=6.dp,vertical=2.dp))
                    Spacer(Modifier.weight(1f))
                    remaining?.let {Text(stringResource(R.string.tx_min_left, it),color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Medium)}
                }
                progress?.let {fraction ->Box(Modifier.align(Alignment.BottomCenter).padding(horizontal=12.dp,vertical=10.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color.White.copy(alpha=.35f))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }}
            }
        }
        Text(programme?.title ?: channel.name,color=Fg,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.clearAndSetSemantics {})
        Text(listOfNotNull(source.takeIf(String::isNotBlank),channel.name,programme?.rating,programme?.season?.let {s->"S$s"+(programme.episode?.let {" E$it"} ?: "")}).distinct().joinToString(" · "),
            color=Mute,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.clearAndSetSemantics {})
    }
}

// ---------------------------------------------------------------- Side menu
/** Home apps scroll sideways, with one or two rows and a partially visible next app. */
@Composable
fun AppsGrid(
    vm: HomeViewModel,
    /** "banner", "circle" or "square" (#211). */
    shape: String,
    onLaunch: (AppEntry) -> Unit,
    onAppOptions: (AppEntry) -> Unit,
    onFocus: () -> Unit = {},
) {
    val circles = shape != "banner"
    val apps = vm.homeApps
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        val gap = if (circles) 10.dp else 16.dp
        val w = if (circles) (maxWidth - 8.dp - gap * 9) / 9.5f
            else CardSizes.width(maxWidth, CardSizes.WIDE, gap, 4.dp)
        val rows = vm.homeAppRows.coerceIn(1, 2).coerceAtMost(apps.size.coerceAtLeast(1))
        val perRow = (apps.size + rows - 1) / rows
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            apps.chunked(perRow.coerceAtLeast(1)).forEach { line ->
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    items(line, key = { it.pkg }) { app ->
                        when {
                            circles -> AppCircle(app, onClick = { onLaunch(app) }, onLongClick = { onAppOptions(app) }, onFocus = onFocus, width = w, square = shape == "square")
                            else -> AppBanner(app, onClick = { onLaunch(app) }, onLongClick = { onAppOptions(app) }, onFocus = onFocus, width = w)
                        }
                    }
                }
            }
        }
    }
}
