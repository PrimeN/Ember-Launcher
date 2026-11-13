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

/**
 * A Hero banner widget (#161): one title at a time with its own artwork, text and buttons. Left and
 * right at the ends of the buttons page through the titles, as on the main hero; it turns by itself
 * while nothing in it is focused. Unlike the main hero it draws its own picture, so several can
 * sit on Home, and it takes no part in startup focus.
 */
@Composable
internal fun WidgetHero(id: String, items: List<Meta>, height: Dp, isSaved: (Meta) -> Boolean, onToggleSaved: (Meta) -> Unit, onOpen: (Meta) -> Unit,
    rotating: Boolean, onFocus: () -> Unit) {
    if (items.isEmpty()) return
    val reduceMotion = LocalReduceMotion.current
    var index by remember(items.map { it.mediaKey() }) { androidx.compose.runtime.mutableIntStateOf(WidgetPlace.index(id, items)) }
    var focused by remember { mutableStateOf(false) }
    val watchFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val safe = HeroBanner.index(index, items.size)
    val meta = items[safe]
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    LaunchedEffect(items, focused, safe, rotating, reduceMotion) {
        if (items.size > 1 && !focused && rotating && !reduceMotion) { delay(9000); index = HeroBanner.next(safe, items.size) }
    }
    CompositionLocalProvider(LocalNight provides true) {
    MaterialTheme(colorScheme = emberColorScheme(LocalAccentIndex.current, false, Ui.isOled)) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).height(height).clip(RoundedCornerShape(TvTokens.Shape.Panel.dp)).background(PanelHi).testTag("widget_hero")) {
        // #187: the artwork slides the way you go, as the carousel's does.
        val millis = if (reduceMotion) 0 else 420
        AnimatedContent(targetState = safe, modifier = Modifier.fillMaxSize(), label = "widgetHeroArt", transitionSpec = {
            val fromEnd = WidgetLook.carouselForward(initialState, targetState, items.size) != rtl
            (slideInHorizontally(tween(millis)) { full -> if (reduceMotion) 0 else if (fromEnd) full else -full } + fadeIn(tween(millis)))
                .togetherWith(slideOutHorizontally(tween(millis)) { full -> if (reduceMotion) 0 else if (fromEnd) -full / 3 else full / 3 } + fadeOut(tween(millis)))
        }) { at -> WidgetArt(items.getOrNull(at) ?: meta, Modifier.fillMaxSize()) }
        // Text sits on the left and bottom, so the shade is strongest there.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xE6000000), .55f to Color(0x66000000), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.5f to Color.Transparent, 1f to Color(0xB3000000))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(.56f).padding(start = 22.dp, bottom = 26.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroTitle(meta, expanded = false)
            Text(listOfNotNull(meta.type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() }, meta.releaseInfo?.takeIf { it.isNotBlank() },
                meta.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() }).joinToString("  ·  "), fontSize = 13.sp, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            meta.description?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFFDADCE0), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp).onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocus() }) {
                DetailsActionButton("Watch now", Icons.Outlined.PlayArrow, text = "Watch now", width = 148.dp,
                    modifier = Modifier.focusRequester(watchFocus).focusProperties { if (rtl) left = saveFocus else right = saveFocus }.restoreFocus(id).onPreviewKeyEvent { event ->
                        if (event.key != heroEdgeKey(previous = true, rtl = rtl) || items.size <= 1) false else {
                            if (HeroBanner.pageOnPress(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount, items.size)) index = HeroBanner.previous(safe, items.size)
                            true
                        }
                    }) { WidgetPlace.keep(id, meta); onOpen(meta) }
                val saved = isSaved(meta)
                DetailsActionButton(if (saved) "Remove from watchlist" else "Add to watchlist", if (saved) Icons.Outlined.Check else Icons.Outlined.Add, selected = saved, width = 44.dp,
                    modifier = Modifier.focusRequester(saveFocus).focusProperties { if (rtl) right = watchFocus else left = watchFocus }.onPreviewKeyEvent { event ->
                        if (event.key != heroEdgeKey(previous = false, rtl = rtl) || items.size <= 1) false else {
                            if (HeroBanner.pageOnPress(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount, items.size)) index = HeroBanner.next(safe, items.size)
                            true
                        }
                    }) { onToggleSaved(meta) }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp).clearAndSetSemantics { contentDescription = "Title ${safe + 1} of ${items.size}" },
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WidgetPosition(safe, items.size, Fg)
        }
    }
    } }
}
/** How one of your Home widgets draws (#161); a built-in row follows Home's card setting. */
internal data class WidgetLook(val wide: Boolean, val numbered: Boolean) {
    companion object {
        /** For Row and Numbered row widgets; the carousel and hero banner draw their own panels. */
        fun of(widget: app.ember.tv.data.HomeWidget?, homeWide: Boolean, shape: String? = null): WidgetLook = when {
            // #195: a built-in row may have a shape of its own.
            widget == null -> WidgetLook(when (shape) { "poster" -> false; "wide" -> true; else -> homeWide }, numbered = false)
            else -> WidgetLook(when (widget.shape) { "poster" -> false; "wide" -> true; else -> homeWide },
                numbered = widget.style == app.ember.tv.data.HomeWidgets.NUMBERED)
        }
        /** The cinematic carousel's share of Home's height (#167, #201): all of it. */
        const val CAROUSEL_HEIGHT = 1f
        /** The line under the carousel's logo: kind, year and up to two genres. */
        fun carouselLine(meta: Meta): String = listOfNotNull(meta.type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() },
            meta.releaseInfo?.takeIf { it.isNotBlank() }, meta.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
        /** Whether going from one title to another moves forward; the turn from the last back to the first does. */
        fun carouselForward(from: Int, to: Int, count: Int): Boolean = if (from == count - 1 && to == 0 && count > 2) true else if (from == 0 && to == count - 1 && count > 2) false else to > from
        /** Stepping from [index] by a left or right press; null at either end, where the press leaves the carousel. */
        fun carouselStep(index: Int, count: Int, forward: Boolean): Int? = (if (forward) index + 1 else index - 1).takeIf { it in 0 until count }
    }
}
/**
 * A widget row's own actions on Home (#183). Holding OK on one of its titles opens a menu with that
 * title's options first, then Move, Edit and Hide for the widget, so arranging Home needs no trip to
 * Settings. Rows that are not widgets, and Kids profiles, keep the plain title options.
 */
@Composable
internal fun WidgetActions(vm: HomeViewModel, widget: app.ember.tv.data.HomeWidget?, content: @Composable () -> Unit) {
    if (widget == null || vm.profiles.profile.kids) { content(); return }
    val titleActions = LocalTitleActions.current
    var asked by remember(widget.id) { mutableStateOf<Meta?>(null) }
    val ask: ((Meta) -> Unit)? = { meta -> asked = meta }
    // #196: a banner or carousel widget chooses its own artwork, Standard or Fanart.
    CompositionLocalProvider(LocalTitleActions provides ask, LocalHeroArtworkMode provides app.ember.tv.data.HeroArtworkMode.normalize(widget.art)) { content() }
    asked?.let { meta ->
        val section = app.ember.tv.data.HomeWidgets.section(widget.id)
        val order = vm.sections.layout.map { it.first }
        OptionMenu(widget.title, buildList {
            if (titleActions != null) add(MenuOption(WidgetDrafts.titleOption(meta.name)) { titleActions(meta) })
            WidgetDrafts.moves(order.indexOf(section), order.lastIndex).forEach { (label, step) -> add(MenuOption(label.replaceFirst("Move ", "Move widget ")) { vm.sections.moveSection(section, step) }) }
            add(MenuOption("Edit widget") { vm.editWidget(widget.id) })
            add(MenuOption("Hide widget from Home") { vm.sections.toggleSection(section) })
        }) { asked = null }
    }
}
/**
 * A banner or carousel widget's picture (#196): the title's background in the widget's artwork mode
 * (Standard, or Fanart when chosen and available), falling back to the card's own picture.
 */
@Composable
private fun WidgetArt(meta: Meta, modifier: Modifier) {
    LandscapeImage(rememberHeroDecoration(meta).applyTo(meta), modifier)
}
/**
 * Where a hero banner or carousel widget was when a title was opened from it (#169), so coming back
 * shows that title again instead of the first. Kept for the life of the app, by Home section.
 */
internal object WidgetPlace {
    private val opened = HashMap<String, String>()
    fun keep(id: String, meta: Meta) { opened[id] = meta.mediaKey() }
    fun index(id: String, items: List<Meta>): Int = opened[id]?.let { key -> items.indexOfFirst { it.mediaKey() == key } }?.takeIf { it >= 0 } ?: 0
    /** Dots up to this many titles; a longer widget shows "13 / 30", where dots ran out (#169). */
    const val DOTS = 12
    fun counter(index: Int, count: Int): String? = if (count > DOTS) "${index + 1} / $count" else null
}
@Composable
private fun WidgetPosition(index: Int, count: Int, color: Color) {
    val counter = WidgetPlace.counter(index, count)
    if (counter != null) Text(counter, fontSize = 12.sp, color = color.copy(alpha = .85f), maxLines = 1)
    else repeat(count) { i ->
        Box(Modifier.size(width = if (i == index) 16.dp else 4.dp, height = 3.dp).clip(CircleShape).background(if (i == index) color else color.copy(alpha = .4f)))
    }
}
/**
 * The Cinematic carousel widget (#167, #174): one giant panel showing a single title: its artwork
 * filling the panel, its logo (or name) and a details line centred at the bottom, and a sliver of
 * the titles before and after at the edges. It is one focus stop: left and right step through the
 * titles, sliding the way you go; OK opens the one showing, and at either end the press moves on
 * like any row. It turns by itself while it is not focused.
 */
@Composable
internal fun WidgetCarousel(id: String, items: List<Meta>, height: Dp, isSaved: (Meta) -> Boolean, onOpen: (Meta) -> Unit, rotating: Boolean, onFocus: () -> Unit,
    title: String = "", bleed: Dp = 0.dp) {
    if (items.isEmpty()) return
    val reduceMotion = LocalReduceMotion.current
    var index by remember(items.map { it.mediaKey() }) { androidx.compose.runtime.mutableIntStateOf(WidgetPlace.index(id, items)) }
    var focused by remember { mutableStateOf(false) }
    val safe = HeroBanner.index(index, items.size)
    val meta = items[safe]
    val actions = LocalTitleActions.current
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    LaunchedEffect(items, focused, safe, rotating, reduceMotion) {
        if (items.size > 1 && !focused && rotating && !reduceMotion) { delay(8000); index = HeroBanner.next(safe, items.size) }
    }
    CompositionLocalProvider(LocalNight provides true) {
        Focusable(
            onClick = { WidgetPlace.keep(id, meta); onOpen(meta) },
            onLongClick = actions?.let { action -> { action(meta) } },
            // #201: out past the page margins on both sides, and the height of Home.
            modifier = Modifier.bleed(bleed).fillMaxWidth().height(height).testTag("widget_carousel").restoreFocus(id)
                .mediaSemantics(meta, isSaved(meta))
                .onFocusChanged { focused = it.isFocused || it.hasFocus; if (focused) onFocus() }
                .onPreviewKeyEvent { event ->
                    val forward = when (event.key) { heroEdgeKey(previous = false, rtl = rtl) -> true; heroEdgeKey(previous = true, rtl = rtl) -> false; else -> return@onPreviewKeyEvent false }
                    val target = WidgetLook.carouselStep(safe, items.size, forward) ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) index = target
                    true
                },
            radius = TvTokens.Shape.None, container = PanelHi, focusedScale = TvTokens.Focus.None,
        ) {
            Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                val millis = if (reduceMotion) 0 else 420
                AnimatedContent(targetState = safe, modifier = Modifier.fillMaxSize(), label = "widgetCarousel", transitionSpec = {
                    // The new title comes in from the side you are heading to; with Reduce motion it simply appears.
                    val fromEnd = WidgetLook.carouselForward(initialState, targetState, items.size) != rtl
                    (slideInHorizontally(tween(millis)) { full -> if (reduceMotion) 0 else if (fromEnd) full else -full } + fadeIn(tween(millis)))
                        .togetherWith(slideOutHorizontally(tween(millis)) { full -> if (reduceMotion) 0 else if (fromEnd) -full / 3 else full / 3 } + fadeOut(tween(millis)))
                }) { at ->
                    val shown = items.getOrNull(at) ?: meta
                    Box(Modifier.fillMaxSize()) {
                        WidgetArt(shown, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), .18f to Color.Transparent, .5f to Color.Transparent, 1f to Color(0xF0000000))))
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(.6f).padding(bottom = 44.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HeroTitle(shown, centered = true)
                            WidgetLook.carouselLine(shown).takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 15.sp, color = Color(0xFFDADCE0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // The widget's name, where a row would have its heading.
                Text(title, Modifier.align(Alignment.TopStart).padding(start = bleed + 4.dp, top = 18.dp, end = bleed), fontSize = TvTokens.Type.Section.sp,
                    fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Which way there is more.
                if (WidgetLook.carouselStep(safe, items.size, forward = false) != null) Text("‹", Modifier.align(Alignment.CenterStart).padding(start = 14.dp), fontSize = 34.sp, color = Color.White.copy(alpha = .8f))
                if (WidgetLook.carouselStep(safe, items.size, forward = true) != null) Text("›", Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), fontSize = 34.sp, color = Color.White.copy(alpha = .8f))
                if (items.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WidgetPosition(safe, items.size, Color.White)
                }
            }
        }
    }
}
/** Draws [side] past its parent on both sides: the carousel reaches the screen's edges through the page margins (#201). */
internal fun Modifier.bleed(side: Dp) = layout { measurable, constraints ->
    val px = side.roundToPx()
    val wide = constraints.maxWidth + px * 2
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-px, 0) }
}
/** A Numbered row's rank (#202): how large the figure on the picture's corner is, in sp-sized dp. */
internal object RankNumerals {
    fun size(cardWidth: Float, wide: Boolean): Float = (if (wide) cardWidth * .2f else cardWidth * .32f).coerceIn(26f, 52f)
}
/** The rank on the picture's lower corner (#202), over a soft shade so it reads on any artwork. */
@Composable
internal fun RankCorner(rank: Int, width: Dp, height: Dp) {
    val font = with(androidx.compose.ui.platform.LocalDensity.current) { RankNumerals.size(width.value, width > height).dp.toSp() }
    Box(Modifier.width(width).height(height).clearAndSetSemantics { }, contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth(.7f).fillMaxHeight(.5f).clip(RoundedCornerShape(bottomStart = TvTokens.Shape.Card.dp))
            .drawWithContent { drawRect(Brush.radialGradient(listOf(Color(0xD9000000), Color.Transparent), center = Offset(0f, size.height), radius = size.maxDimension)) })
        Text(rank.toString(), Modifier.padding(start = 8.dp, bottom = 2.dp), fontSize = font, lineHeight = font, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, softWrap = false,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 2f), 8f)))
    }
}
