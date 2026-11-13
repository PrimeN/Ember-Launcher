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

/*
 * The modern look (Settings > Appearance > Interface):
 * full-bleed artwork behind the top of Home, pill tabs, wide 16:9 cards,
 * a row of app banners, and live cards with a LIVE badge. Light Home provides the apps-first layout.
 */

internal val CardW = 268.dp
private val CardH = 151.dp

// ---------------------------------------------------------------- Top bar

/**
 * Full-bleed artwork behind the top of the screen, faded into the background at the bottom and
 * left so text stays readable. [dim] goes from 0 (full picture) to 1 (almost hidden).
 */
@Composable
fun ModernBackdrop(meta: Meta?, dim: Float, backgroundColor: Color = Ink, heightFraction: Float = .78f, shade: Float = 1f, onPresented: (Meta?) -> Unit = {}) {
    val art = rememberReadyArtwork(meta)
    val presented by androidx.compose.runtime.rememberUpdatedState(onPresented)
    val sink = LocalArtworkPresented.current
    var viewport by remember {mutableStateOf<HeroViewport?>(null)}
    LaunchedEffect(art,viewport) { presented(art.meta); sink(art.copy(viewport=viewport)) }
    // Low replaces the full-screen picture directly rather than blending two large layers.
    val duration = PolishMotion.duration(TvTokens.Motion.Hero,LocalRenderOptions.current,LocalReduceMotion.current)
    val a by animateFloatAsState(1f - 0.75f * dim, tween(duration), label = "backdrop")
    // #142: the picture and its shading fade out to transparent at the bottom, so they blend into
    // whatever lies below (the page colour, theme glow or tile colours) instead of ending in a line.
    val fadeFrom = HeroFade.start(Ui.isDay && !LocalNight.current)
    Box(Modifier.fillMaxWidth().fillMaxHeight(heightFraction.coerceIn(.5f,1f)).alpha(a)
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Black, fadeFrom to Color.Black, 1f to Color.Transparent),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        }
        .onGloballyPositioned {coordinates->
        val position=coordinates.positionInRoot()
        viewport=HeroViewport(position.x,position.y,coordinates.size.width.toFloat(),coordinates.size.height.toFloat())
    }) {
        if(art.drawable==null)Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Panel,PanelHi))))
        if(duration==0)FramedHeroImage(art,Modifier.fillMaxSize())
        else {
            // Metadata/logo refreshes do not restart a full-screen image crossfade.
            Crossfade(targetState = art.drawable to art.focus, animationSpec = tween(duration), label = "backdrop") { layer ->
                FramedHeroImage(ReadyArtwork(null,layer.first,layer.second),Modifier.fillMaxSize())
            }
        }
        HeroArtworkShade(backgroundColor,shade,joinPage=false)
    }
}

/** Where the Home hero starts fading out (#142), as a fraction of its height. */
internal object HeroFade {
    fun start(day: Boolean) = if (day) .78f else .58f
}

// ---------------------------------------------------------------- Home

/**
 * Modern Home: the featured title over the backdrop, then Continue watching, Your apps and the
 * rows. [heroIndex] is shared with the backdrop drawn behind; [onAmbient] reports the focused
 * card so the backdrop can follow it further down the page.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ModernHome(
    vm: HomeViewModel,
    state: LazyListState,
    heroIndex: Int,
    onHeroIndex: (Int) -> Unit,
    onAmbient: (Meta?) -> Unit,
    onOpenTitle: (Meta) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onAppOptions: (AppEntry) -> Unit,
    onList: (String) -> Unit = {},
    onSeeAll: (CatalogRow) -> Unit = {},
    interactive: Boolean = true,
    presentedHero: Meta? = null,
) {
    val hero = vm.heroItems
    val wide = vm.look.modernCards != "poster"
    val row: @Composable (List<Meta>, Boolean) -> Unit = { items, saved ->
        MediaRow(items, wide, isSaved = { saved || vm.isSaved(it) }, onFocus = onAmbient, onOpen = onOpenTitle)
    }
    val blocks = vm.homeBlocks
    val heroPosition = HeroLayout.index(blocks.map { it.key })
    val heroVisible by remember(state) { derivedStateOf {
        state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hero" }?.let {
            HeroLayout.visibleFraction(it.offset, it.size, state.layoutInfo.viewportStartOffset, state.layoutInfo.viewportEndOffset) >= 0.75f
        } == true
    } }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var heroHasFocus by remember {mutableStateOf(false)}
    ReleaseHiddenHeroFocus(state,heroHasFocus)
    val heroFits=remember {mutableStateOf(true)}
    val defaultBring=androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val bring=remember(state,defaultBring,heroPosition) {HomeBringIntoViewSpec(state,defaultBring,{heroHasFocus},{heroFits.value},heroPosition)}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroHeight = HeroLayout.height(maxHeight.value,androidx.compose.ui.platform.LocalDensity.current.fontScale).dp
        androidx.compose.runtime.SideEffect {heroFits.value=heroHeight<=maxHeight}
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides bring) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().clipToBounds(),
            contentPadding = PaddingValues(start = (if(isEmberX)EmberXLayout.Gutter else TvTokens.Space.Page).dp, end = (if(isEmberX)EmberXLayout.Gutter else TvTokens.Space.Page).dp, top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Settings > Home layout decides which sections show and in what order.
            if (blocks.firstOrNull() !is HomeViewModel.HomeBlock.Hero) item(key = "top") { Spacer(Modifier.height(8.dp)) }
            items(blocks, key = { it.key }) { b ->
                // Only the vertical list clears the hero; horizontal rows keep their own pivot.
                CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides defaultBring) {
                when (b) {
                    is HomeViewModel.HomeBlock.Hero -> ModernHero(hero, heroIndex, onHeroIndex, heroHeight, vm::isSaved, vm::toggleWatchlist, onOpenTitle, presented = presentedHero, rotating = interactive && LocalSceneInteractive.current && heroVisible && !state.isScrollInProgress, onFocus = {
                        onAmbient(null)
                        // The banner's buttons: show the whole banner, title included.
                        if (heroHeight<=maxHeight && heroPosition >= 0 && (state.firstVisibleItemIndex != heroPosition || state.firstVisibleItemScrollOffset > 0)) scope.launch { state.animateScrollToItem(heroPosition) }
                    },onFocusState={heroHasFocus=it})
                    is HomeViewModel.HomeBlock.Apps -> ModernSection(stringResource(R.string.ui_your_apps)) {
                        HomeFolderLinks(vm)
                        AppsGrid(vm, shape = vm.look.appIcons, onLaunch = onLaunch, onAppOptions = onAppOptions, onFocus = { onAmbient(null) })
                    }
                    // #183: holding OK on a title in a widget row also offers the widget's own actions.
                    is HomeViewModel.HomeBlock.Row -> WidgetActions(vm, b.widget) { if (b.widget?.style == app.ember.tv.data.HomeWidgets.HERO) ModernSection(b.row.displayTitle) {
                        // #161: a banner of its own, with its own artwork; the main hero keeps the page backdrop.
                        WidgetHero(b.key, b.row.items, minOf(heroHeight * .82f, maxHeight * .7f), vm::isSaved, vm::toggleWatchlist, onOpenTitle,
                            rotating = interactive && LocalSceneInteractive.current, onFocus = { onAmbient(null) })
                    } else if (b.widget?.style == app.ember.tv.data.HomeWidgets.CAROUSEL) {
                        // #167, #201: the whole page, edge to edge, a title at a time; its name sits on the picture.
                        val at = blocks.indexOf(b) + if (blocks.firstOrNull() !is HomeViewModel.HomeBlock.Hero) 1 else 0
                        WidgetCarousel(b.key, b.row.items, maxHeight * WidgetLook.CAROUSEL_HEIGHT - 4.dp, vm::isSaved, onOpenTitle,
                            rotating = interactive && LocalSceneInteractive.current,
                            // Focusing it brings all of it on screen, as the banner does.
                            onFocus = { onAmbient(null); scope.launch { state.animateScrollToItem(at) } },
                            title = b.row.displayTitle, bleed = (if(isEmberX)EmberXLayout.Gutter else TvTokens.Space.Page).dp)
                    } else ModernSection(b.row.displayTitle, b.row.addonName.takeIf { it.isNotBlank() && vm.look.rowAddonLabels }) {
                        // #161: one of your widgets draws in its own style and card shape.
                        val look = WidgetLook.of(b.widget, wide, b.shape)
                        MediaRow(
                            b.row.items, look.wide, isSaved = { b.saved || vm.isSaved(it) }, onFocus = onAmbient, onOpen = onOpenTitle,
                            onSeeAll = if (vm.canSeeAll(b.row)) ({ onSeeAll(b.row) }) else null,
                            numbered = look.numbered,
                        )
                    } }
                    is HomeViewModel.HomeBlock.Collection -> CollectionRow(b.collection, animations = vm.collections.heroPreviews && !vm.profiles.profile.kids,
                        onMove = if (vm.profiles.profile.kids) null else ({ f, delta -> vm.collections.moveFolder(b.collection, f, delta) }),
                        onDelete = if (vm.profiles.profile.kids) null else ({ f -> if (f == null) vm.collections.remove(b.collection.id) else vm.collections.removeFolder(b.collection.id, f.id) })) { f -> vm.collections.openFolder(b.collection, f) }
                    is HomeViewModel.HomeBlock.Shortcuts -> ListButtons(vm, b.ids, onList)
                    // #184: a loading widget holds the room its style will take.
                    is HomeViewModel.HomeBlock.Loading -> ModernSection(b.title) {
                        when (b.widget?.style) {
                            app.ember.tv.data.HomeWidgets.CAROUSEL -> LoadingPanel(maxHeight * WidgetLook.CAROUSEL_HEIGHT)
                            app.ember.tv.data.HomeWidgets.HERO -> LoadingPanel(minOf(heroHeight * .82f, maxHeight * .7f))
                            else -> LoadingCards(WidgetLook.of(b.widget, wide).wide)
                        }
                    }
                    // #177: a widget that could not load says so here instead of going missing.
                    is HomeViewModel.HomeBlock.Notice -> ModernSection(b.title) {
                        RecoveryPanel("This widget couldn't load", b.text, "Widget settings") { vm.openSettingsPage("WIDGETS") }
                    }
                    is HomeViewModel.HomeBlock.Pinned -> ModernSection("Your shortcuts") {
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(6.dp)) {
                            items(b.items,key={it.id}) {shortcut->
                                Focusable(onClick={vm.openHomeShortcut(shortcut)?.let(onList)},onLongClick={vm.homeShortcuts.toggle(shortcut)},modifier=Modifier.width(180.dp).height(68.dp)) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(shortcut.label,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(when(shortcut.kind){"server"->"Media library";"network"->"Network folder";else->"Collection"},fontSize=TvTokens.Type.Caption.sp,color=Mute)
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            // Catalog rows come through homeBlocks (Settings > Home layout > Your catalogs), so they are not listed again here.
            item(key = "status") { HomeRecovery(vm) }
            item(key = "end") {
                Text(
                    stringResource(R.string.ui_no_sponsored_rows_everything_here_comes),
                    color = Mute,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        }
    }
}

@Composable
fun ModernSection(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 4.dp)) {
            Text(title, fontSize = TvTokens.Type.Section.sp, fontWeight = FontWeight.Medium, color = Fg)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Mute)
        }
        content()
    }
}

/** The featured title: large name, a details line, the description, two pill buttons and page dots. */
@Composable
internal fun ModernHero(
    items: List<Meta>, index: Int, onIndex: (Int) -> Unit, height: Dp,
    isSaved: (Meta) -> Boolean, onToggleSaved: (Meta) -> Unit, onOpen: (Meta) -> Unit,
    onFocus: () -> Unit, rotating: Boolean, presented: Meta? = null,
    onFocusState:(Boolean)->Unit={},
) {
    if (items.isEmpty()) return
    androidx.compose.runtime.DisposableEffect(Unit){onDispose {onFocusState(false)}}
    val reduceMotion = LocalReduceMotion.current
    val render = LocalRenderOptions.current
    val focusBackdrop = LocalFocusBackdrop.current
    var focused by remember { mutableStateOf(false) }
    val watchFocus = remember { FocusRequester() }
    // Entering Home from the menu lands on Watch now while nothing on Home was focused yet (#66):
    // geometric search from a centred menu tab otherwise picks the first row's card below it.
    val heroNavigation = LocalNavigationFocus.current
    val heroScene = LocalFocusScene.current
    androidx.compose.runtime.DisposableEffect(heroNavigation, heroScene, watchFocus) {
        if (heroNavigation != null && heroScene != null) {
            heroNavigation.register(heroScene, HERO_ENTRY, watchFocus)
            if (heroNavigation.preferred(heroScene) == null) heroNavigation.focused(heroScene, HERO_ENTRY)
        }
        onDispose { if (heroNavigation != null && heroScene != null) heroNavigation.unregister(heroScene, HERO_ENTRY, watchFocus) }
    }
    // First load (#100): the hero arrives after the rows, so it takes focus when it appears.
    val heroInteractive = LocalSceneInteractive.current
    LaunchedEffect(watchFocus, heroInteractive) {
        if (heroInteractive && StartupHeroFocus.waiting() && claimStartupFocus(watchFocus) && heroNavigation != null && heroScene != null)
            heroNavigation.focused(heroScene, HERO_ENTRY)
    }
    val saveFocus = remember { FocusRequester() }
    val safe = HeroBanner.index(index, items.size)
    LaunchedEffect(items, focused, safe, rotating, reduceMotion, presented?.mediaKey()) {
        if (items.size > 1 && !focused && rotating && !reduceMotion && (presented == null || presented.mediaKey() == items[safe].mediaKey())) {
            delay(8000)
            onIndex(HeroBanner.next(safe, items.size))
        }
    }
    val meta = presented?.takeIf { shown -> items.any { it.mediaKey() == shown.mediaKey() } } ?: items[safe]
    val shown = HeroBanner.displayIndex(index,items,presented)
    val heroRtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    PrefetchHeroArtwork(items, safe, enabled = rotating)
    // Artwork retains a neutral readability scrim in Day mode; the surrounding page stays light.
    CompositionLocalProvider(LocalNight provides true) {
    MaterialTheme(colorScheme=emberColorScheme(LocalAccentIndex.current,false,Ui.isOled)) {
    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        Column(Modifier.align(Alignment.BottomStart)
            .fillMaxWidth(HeroLayout.textFraction(androidx.compose.ui.platform.LocalDensity.current.fontScale))
            .padding(start = 4.dp, top = 24.dp, bottom = 44.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Crossfade(targetState = meta, animationSpec = tween(PolishMotion.duration(TvTokens.Motion.Text,render,reduceMotion)), label = "heroText") { title ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroTitle(title, expanded = !render.lowPower)
                    Text(listOfNotNull(title.type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() },
                        title.releaseInfo?.takeIf { it.isNotBlank() }, title.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                        title.runtime?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                        fontSize = 14.sp, color = Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ReadableDescription(title.description.orEmpty(),minLines=3)
                }
            }
            // The actions remain mounted during artwork/text transitions, so paging retains focus.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp).onFocusChanged {
                    focused = it.hasFocus
                    onFocusState(it.hasFocus)
                    if (it.hasFocus) { focusBackdrop?.clear(); onFocus() }
                }) {
                DetailsActionButton("Watch now", Icons.Outlined.PlayArrow, text = "Watch now", width = 148.dp,
                    modifier = Modifier.focusRequester(watchFocus).focusProperties { if(heroRtl)left=saveFocus else right=saveFocus }.restoreFocus(meta.mediaKey())
                        .onPreviewKeyEvent { event ->
                            if (event.key != heroEdgeKey(previous=true,rtl=heroRtl) || items.size <= 1) false else {
                                if (HeroBanner.pageOnPress(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount, items.size))
                                    onIndex(HeroBanner.previous(safe, items.size))
                                true
                            }
                        }) { onOpen(meta) }
                val saved = isSaved(meta)
                DetailsActionButton(if (saved) "Remove from watchlist" else "Add to watchlist",
                    if (saved) Icons.Outlined.Check else Icons.Outlined.Add, selected = saved, width = 44.dp,
                    modifier = Modifier.focusRequester(saveFocus).focusProperties { if(heroRtl)right=watchFocus else left=watchFocus }.onPreviewKeyEvent { event ->
                        if (event.key != heroEdgeKey(previous=false,rtl=heroRtl) || items.size <= 1) false else {
                            if (HeroBanner.pageOnPress(event.type == KeyEventType.KeyDown, event.nativeKeyEvent.repeatCount, items.size))
                                onIndex(HeroBanner.next(safe, items.size))
                            true
                        }
                    }) { onToggleSaved(meta) }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
            .clearAndSetSemantics { contentDescription = "Featured title ${shown + 1} of ${items.size}" },
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, _ ->
                Box(Modifier.size(width = if (i == shown) 16.dp else 4.dp, height = 3.dp).clip(CircleShape)
                    .background(if (i == shown) Fg else Fg.copy(alpha = .4f)))
            }
        }
    }
    } }
}

// ---------------------------------------------------------------- Cards

/** A row of wide 16:9 cards, or of posters when Settings > Appearance says so. */
@Composable
fun MediaRow(items: List<Meta>, wide: Boolean, isSaved: (Meta) -> Boolean, onFocus: (Meta?) -> Unit = {}, onSeeAll: (() -> Unit)? = null,
    /** A Numbered row widget (#161): a rank on each card. */
    numbered: Boolean = false, onOpen: (Meta) -> Unit) {
    // Six landscape cards or eight posters across the row (CardSizes), whatever the screen.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = if (wide) 16.dp else 14.dp
        val uniqueItems = remember(items) { items.distinctBy { it.mediaKey() } }
        val baseWidth = CardSizes.width(maxWidth, if(isEmberX && !wide)EmberXLayout.posterCount(maxWidth.value,androidx.compose.ui.platform.LocalDensity.current.fontScale) else LocalRenderOptions.current.count(wide), gap, 4.dp)
        val rowState=androidx.compose.foundation.lazy.rememberLazyListState()
        val w = baseWidth
        PrefetchRowArtwork(items,rowState,wide,w)
        // Remove from Continue Watching (or marking it watched) hands focus to the next card (#130).
        val keeper = rememberFocusKeeper(remember(uniqueItems) { uniqueItems.map { it.mediaKey() } })
        LazyRow(
            state=rowState,
            modifier = Modifier.focusKeeperList(keeper),
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
        ) {
            itemsIndexed(uniqueItems, key = { _, m -> m.mediaKey() }) { index, m ->
                Box(Modifier.focusKeeperItem(keeper, m.mediaKey(), index)) {
                    if (wide) {
                        WideCard(m, isSaved(m), width = w, onFocus = { onFocus(m) }) { onOpen(m) }
                    } else {
                        Box(Modifier.onFocusChanged { if (it.hasFocus) onFocus(m) }) { TitleCard(m, isSaved(m), width = w) { onOpen(m) } }
                    }
                    // #202: the rank is drawn on the picture itself, in its corner.
                    if (numbered) RankCorner(index + 1, w, if (wide) w * 9f / 16f else w * m.cardRatio)
                }
            }
            if (onSeeAll != null) item(key = "seeall") { SeeAllCard(w, if (wide) w * 9f / 16f else w * 1.5f, onSeeAll) }
        }
    }
}

/** A Material icon (outlined), tinted like the text beside it. */
@Composable
internal fun MaterialGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: androidx.compose.ui.unit.Dp = 20.dp) {
    androidx.compose.foundation.Image(
        icon,
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
        modifier = Modifier.size(size),
    )
}


/**
 * Full-screen artwork at a size that stays sharp on a 4K screen: Cinemeta/metahub backdrops are
 * asked for in "large" (1920 px wide) rather than "medium", and TMDB images in "original".
 */
fun sharpArt(url: String): String = url
    .replace("/background/medium/", "/background/large/")
    .replace("/background/small/", "/background/large/")
    .replace(tmdbSized, "image.tmdb.org/t/p/original/")

private val tmdbSized = Regex("""image\.tmdb\.org/t/p/w\d+/""")


/** Home's default entry point from the menu: the hero's Watch now button (#66). */
internal const val HERO_ENTRY = "hero-watch-now"
