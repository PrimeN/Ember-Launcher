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

private val CardW = 268.dp
private val CardH = 151.dp

// ---------------------------------------------------------------- Top bar

/** Profile, a shared navigation capsule, two quick actions, then branding and live status. */
@Composable
fun ModernTopBar(
    tabs: List<String>,
    tab: String,
    onTab: (String) -> Unit,
    weather: String?,
    profile: app.ember.tv.data.Profile? = null,
    openOnFocus: Boolean = true,
    searchFocus: androidx.compose.ui.focus.FocusRequester? = null,
    onProfile: () -> Unit = {},
) {
    val sceneFocus = LocalNavigationFocus.current
    val time=rememberLauncherTime()
    // Search leads the capsule; other tabs retain the user's saved order.
    val navigation = tabs.filter { it == NavTabs.SEARCH } +
        tabs.filter { it != NavTabs.SEARCH && it != NavTabs.SETTINGS }
    val activeTab = navigationTabForPage(tab, navigation)
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 900.dp
        val gap = if (compact) 8.dp else 12.dp
        val capsule = Modifier.navigationGlass()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = if (compact) 24.dp else 48.dp,
                end = if (compact) 24.dp else 40.dp, top = 16.dp, bottom = 12.dp)
                .navigationFocusGroup { runCatching { sceneFocus?.tab(activeTab)?.requestFocus() == true }.getOrDefault(false) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (profile != null) {
                ProfileMenuButton(profile, size = 42.dp,
                    modifier = Modifier.semantics { contentDescription = "Profile: ${profile.name}" },
                    onFallback = onProfile)
            } else {
                NavigationAction("Profile", Icons.Outlined.IcProfile, onProfile)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically) {
                // Only the tabs scroll. Their pill wraps content until the status and
                // action buttons leave insufficient room, then becomes a bounded viewport.
                FrostedPill(Modifier.weight(1f, fill = false)) {
                Row(
                    modifier = Modifier.then(capsule)
                        .clip(CircleShape).horizontalScroll(rememberScrollState()).padding(6.dp).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navigation.forEachIndexed { index, id -> key(id) {
                        val selected = activeTab == id
                        Focusable(
                            onClick = { onTab(id) },
                            modifier = Modifier.height(42.dp)
                                .let { m -> if (sceneFocus != null) m.navigationTabTarget(sceneFocus, id) else searchFocus?.takeIf { id == NavTabs.SEARCH }?.let { m.focusRequester(it) } ?: m }
                                .let { m -> if (sceneFocus != null) m.focusProperties {
                                    // Explicit neighbors avoid scroll-group geometry restarting at Search.
                                    left = navigation.getOrNull(index+choiceStep(right=false,rtl=rtl))?.let(sceneFocus::tab) ?: androidx.compose.ui.focus.FocusRequester.Default
                                    right = navigation.getOrNull(index+choiceStep(right=true,rtl=rtl))?.let(sceneFocus::tab) ?: androidx.compose.ui.focus.FocusRequester.Default
                                    if (openOnFocus) down = sceneFocus.content(tab)
                                } else m }
                                .semantics { this.selected = selected; role = Role.Tab }
                                .openNavigationTab(id, selected, enabled = openOnFocus) { onTab(id) },
                            radius = TvTokens.Shape.Pill,
                            container = if (selected) WashHi else Color.Transparent,
                            focusedContainer = PillColor,
                            focusedContent = OnPillColor,
                            outline = false,
                            focusedScale = TvTokens.Focus.Inline,
                        ) {
                            if (id == NavTabs.SEARCH) {
                                Box(Modifier.size(42.dp).semantics { contentDescription = "Search" }, contentAlignment = Alignment.Center) {
                                    SearchGlyph(androidx.tv.material3.LocalContentColor.current)
                                }
                            } else {
                                // Surface places bare content at the top. Match the icon
                                // tabs' explicit height and center the label within it.
                                Box(Modifier.height(42.dp).padding(horizontal = if (compact) 14.dp else 18.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text(tabTitle(id),
                                        fontSize = TvTokens.Type.Navigation.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = Color.Unspecified, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    } }
                }
                }
                FrostedPill {
                Row(capsule.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavigationAction(stringResource(R.string.ui_screensaver_now), Icons.Outlined.IcScreensaver) { onTab(NavTabs.SCREENSAVER) }
                    NavigationAction(stringResource(R.string.ui_quick_settings), Icons.Outlined.IcSettings) { onTab(NavTabs.QUICK) }
                }
                }
            }
            Column(Modifier.widthIn(max = if (compact) 150.dp else 210.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmberLogo(height = if (compact) 22.dp else 26.dp, tint = Fg)
                    Text(time, fontSize = if (compact) 14.sp else 16.sp, color = Fg, maxLines = 1, softWrap = false)
                }
                weather?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Icon-only action with a spoken label; it never activates just because focus passes over it. */
@Composable
private fun NavigationAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick,
        modifier = Modifier.size(42.dp).semantics { contentDescription = label }
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Pill, container = Color.Transparent, focusedContainer = PillColor,
        focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MaterialGlyph(icon, if (focused) OnPillColor else Fg, 22.dp)
        }
    }
}

/** Quick settings, shown as your profile's avatar when there are profiles (the panel lists them at the top). */
@Composable
internal fun QuickButton(profile: app.ember.tv.data.Profile?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    if (profile != null) ProfileAvatar(profile, size, modifier, onClick)
    else RoundIcon(selected = selected, onClick = onClick, modifier = modifier) { c -> NavGlyph(NavTabs.QUICK, c) }
}

/** A profile's round avatar: its first letter on its colour. */
@Composable
fun ProfileAvatar(p: app.ember.tv.data.Profile, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = Color(app.ember.tv.data.Profile.COLORS.getOrElse(p.color) { app.ember.tv.data.Profile.COLORS[0] })
    Focusable(onClick = onClick, modifier = modifier.size(size), radius = TvTokens.Shape.Pill, container = color, focusedContainer = color, focusedContent = Color(0xFF1F1F1F), focusedScale = TvTokens.Focus.Round) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(p.name.take(1).uppercase(), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F1F1F))
        }
    }
}

/** A round button with a drawn icon; the icon turns dark when the button is focused. */
@Composable
internal fun RoundIcon(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        modifier = modifier.size(42.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Pill,
        container = if (selected) WashHi else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.Round,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { glyph(if (focused) OnPillColor else Fg) }
    }
}

@Composable
internal fun SearchGlyph(color: Color) = MaterialGlyph(Icons.Outlined.IcSearch, color)

@Composable
internal fun GearGlyph(color: Color) = MaterialGlyph(Icons.Outlined.IcSettings, color)


// ---------------------------------------------------------------- Backdrop

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
                        AppsGrid(vm, circles = vm.look.appIcons == "circle", onLaunch = onLaunch, onAppOptions = onAppOptions, onFocus = { onAmbient(null) })
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
private fun WidgetActions(vm: HomeViewModel, widget: app.ember.tv.data.HomeWidget?, content: @Composable () -> Unit) {
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
private fun Modifier.bleed(side: Dp) = layout { measurable, constraints ->
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
private fun RankCorner(rank: Int, width: Dp, height: Dp) {
    val font = with(androidx.compose.ui.platform.LocalDensity.current) { RankNumerals.size(width.value, width > height).dp.toSp() }
    Box(Modifier.width(width).height(height).clearAndSetSemantics { }, contentAlignment = Alignment.BottomStart) {
        Box(Modifier.fillMaxWidth(.7f).fillMaxHeight(.5f).clip(RoundedCornerShape(bottomStart = TvTokens.Shape.Card.dp))
            .drawWithContent { drawRect(Brush.radialGradient(listOf(Color(0xD9000000), Color.Transparent), center = Offset(0f, size.height), radius = size.maxDimension)) })
        Text(rank.toString(), Modifier.padding(start = 8.dp, bottom = 2.dp), fontSize = font, lineHeight = font, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1, softWrap = false,
            style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(0f, 2f), 8f)))
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
fun AppCircle(app: AppEntry, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit = {}, width: androidx.compose.ui.unit.Dp = 112.dp) {
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
            radius = TvTokens.Shape.Pill,
            container = Color(0xFFF1F3F4),
            focusedScale = TvTokens.Focus.Round,
            card=true,
        ) {
            if(bitmap!=null)Image(bitmap.asImageBitmap(), contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
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
        Text("On now",fontSize=16.sp,fontWeight=FontWeight.Medium,color=Fg,modifier=Modifier.padding(start=4.dp).semantics {heading()})
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
                    Text("LIVE",color=Color.White,fontSize=TvTokens.Type.Caption.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clip(RoundedCornerShape(3.dp)).background(LiveRed).padding(horizontal=6.dp,vertical=2.dp))
                    Spacer(Modifier.weight(1f))
                    remaining?.let {Text("$it min left",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Medium)}
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

/**
 * The modern menu down the left side (Settings > Appearance > Menu: Side): round icons, opening
 * into icon-and-name rows over a dark fade while the remote is on them. The wordmark sits at the
 * top and the clock at the bottom.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ModernSideRail(
    tabs: List<String>,
    tab: String,
    onTab: (String) -> Unit,
    weather: String?,
    modifier: Modifier = Modifier,
    profile: app.ember.tv.data.Profile? = null,
    onProfile: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val time=rememberLauncherTime()
    val width by androidx.compose.animation.core.animateDpAsState(if (expanded) 260.dp else 84.dp,
        animationSpec = tween(PolishMotion.duration(TvTokens.Motion.Rail,LocalRenderOptions.current,LocalReduceMotion.current)), label = "rail")
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(0f to Ink.copy(alpha = 0.95f), 0.8f to Ink.copy(alpha = 0.85f), 1f to Ink.copy(alpha = 0.5f))
                else Brush.horizontalGradient(0f to Ink.copy(alpha = 0.6f), 1f to Color.Transparent),
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusRestorer(),
    ) {
        Column(Modifier.fillMaxHeight().padding(start = 20.dp, top = 26.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                    if (profile != null) ProfileMenuButton(profile, onFallback = onProfile) else EmberMark(32.dp)
                }
                if (expanded) EmberWordmark(19.dp, Fg)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                // Settings is the first entry inside Quick settings.
                (tabs.filter { it != NavTabs.SETTINGS } + NavTabs.QUICK).forEach { id -> RailItem(id, tab, id == tab || (id == NavTabs.QUICK && tab == NavTabs.SETTINGS), expanded) { onTab(id) } }
            }
            Text(time, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fg, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (expanded && weather != null) Text(weather, fontSize = 13.sp, color = Mute, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(end = 20.dp))
        }
    }
}

@Composable
private fun RailItem(id: String, contentId: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val scene = LocalNavigationFocus.current
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        // Expanded, the pill follows the rail as it widens instead of jumping to its full width.
        modifier = (if (expanded) Modifier.fillMaxWidth().padding(end = 24.dp).height(44.dp) else Modifier.size(44.dp))
            .let { m -> if (scene != null) m.navigationTabTarget(scene,id).focusProperties { if(rtl)left=scene.content(contentId) else right=scene.content(contentId) } else m }
            .semantics { this.selected = selected; role = Role.Tab }
            .openNavigationTab(id, selected, open = onClick)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Pill,
        container = if (selected) WashHi else Color.Transparent,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.Control,
    ) {
        Row(Modifier.fillMaxSize().padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { NavGlyph(id, if (focused) OnPillColor else Fg) }
            if (expanded) Text(tabTitle(id), fontSize = TvTokens.Type.Navigation.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
        }
    }
}

/** Small drawn icons for the menu entries. */
@Composable
fun NavGlyph(id: String, color: Color, size: Dp = 20.dp) {
    MaterialGlyph(
        when (id) {
            NavTabs.HOME -> Icons.Outlined.IcHome
            NavTabs.WATCHLIST -> Icons.Outlined.IcBookmarkBorder
            NavTabs.LIVE -> Icons.Outlined.IcLiveTv
            NavTabs.DISCOVER -> Icons.Outlined.IcExplore
            NavTabs.LIBRARY -> Icons.Outlined.IcVideoLibrary
            NavTabs.APPS -> Icons.Outlined.IcApps
            NavTabs.SEARCH -> Icons.Outlined.IcSearch
            NavTabs.SETTINGS -> Icons.Outlined.IcSettings
            NavTabs.QUICK -> Icons.Outlined.IcTune
            else -> Icons.Outlined.IcCircle
        },
        color, size,
    )
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


/** Home apps scroll sideways, with one or two rows and a partially visible next app. */
@Composable
fun AppsGrid(
    vm: HomeViewModel,
    circles: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onAppOptions: (AppEntry) -> Unit,
    onFocus: () -> Unit = {},
) {
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
                            circles -> AppCircle(app, onClick = { onLaunch(app) }, onLongClick = { onAppOptions(app) }, onFocus = onFocus, width = w)
                            else -> AppBanner(app, onClick = { onLaunch(app) }, onLongClick = { onAppOptions(app) }, onFocus = onFocus, width = w)
                        }
                    }
                }
            }
        }
    }
}

/** Home's default entry point from the menu: the hero's Watch now button (#66). */
internal const val HERO_ENTRY = "hero-watch-now"
