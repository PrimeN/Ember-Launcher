package app.ember.tv.ui

import app.ember.tv.data.TileLabels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.R
import app.ember.tv.data.*
import app.ember.tv.device.DeviceInputs
import app.ember.tv.light.*
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Light Home: a measured app dock and compact status capsule in native Compose. */
@Composable
fun LightHome(vm: HomeViewModel, state: LazyListState, tabs: List<String>, onTab: (String) -> Unit,
    onOpenTitle: (Meta) -> Unit, onLaunch: (AppEntry) -> Unit, onAppOptions: (AppEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val options = vm.look.lightHome
    val render = LocalRenderOptions.current
    val reduced = LocalReduceMotion.current || !options.motion || !render.animationsEnabled
    val millis = render.animationMillis(180, reduced)
    val profile = vm.profiles.profileId
    val desktop=vm.personalHomes.current
    val apps = vm.homeApps
    val namedFolders=desktop.folders.filter {f->f.packages.any(vm.appAccess::canLaunch)}
    val titleActions=LocalTitleActions.current
    val folder = vm.appAccess.page(false).filter { a -> vm.visibleApps.none { it.pkg == a.pkg } && vm.personalHomes.folderFor(a.pkg)==null }
    // Hidden apps are on neither Home nor More Apps; More Apps can show them so they can be unhidden.
    val hiddenApps = vm.appAccess.page(true).filter { it.pkg in vm.appsPageOff }
    val hasMore = folder.isNotEmpty() || hiddenApps.isNotEmpty()
    val hasFolders=hasMore || namedFolders.isNotEmpty()
    var panel by remember(profile) { mutableStateOf<String?>(null) }
    val slides = rememberLightSlides(vm, 0)
    val inputs = rememberLightInputs(0)
    val online = rememberLightInternet()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var idle by remember(profile) { mutableStateOf(false) }
    var activity by remember { mutableIntStateOf(0) }
    var wakeKey by remember { mutableStateOf<Key?>(null) }
    var selected by remember(profile) { mutableStateOf<String?>(null) }
    var heroFocused by remember { mutableStateOf(false) }
    var launching by remember(profile) { mutableStateOf<String?>(null) }
    val current = slides.firstOrNull { it.id == selected } ?: slides.firstOrNull()
    // App rows (#62): the dock app in focus, and its own rows once it has stayed focused a moment.
    var shelfPkg by remember(profile) { mutableStateOf<String?>(null) }
    val shelfFocus = remember { FocusRequester() }
    val shelfOn = options.appRows && !vm.profiles.profile.kids
    // Plex, Jellyfin and Emby come from Ember's own server connection (Google TV hides their rows
    // from other launchers, #63); other apps from what they share with Android TV.
    val serverShelf = remember(shelfPkg, shelfOn, vm.serverRows) {
        shelfPkg?.takeIf { shelfOn && vm.profiles.personalOn }?.let { AppShelf.serverGroups(it, vm.serverRows) }.orEmpty()
    }
    val shared by produceState(emptyList<Pair<String, List<LightMedia>>>(), shelfPkg, shelfOn) {
        val pkg = shelfPkg
        if (!shelfOn || pkg == null) { value = emptyList(); return@produceState }
        delay(250)
        value = runCatching { LightMediaProvider.shelf(context, pkg) }.getOrDefault(emptyList())
    }
    val shelf = remember(serverShelf, shared) { AppShelf.merge(serverShelf, shared) }
    val heroInView by remember(state) { derivedStateOf { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset < 32 } }
    val topFocus = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    val appsFocus = remember { FocusRequester() }
    val overlay = panel != null || !LocalSceneInteractive.current || !windowFocused
    fun active() { activity++; idle = false }
    LaunchedEffect(activity, options.idleSeconds, lifecycle, overlay, slides.isNotEmpty()) {
        idle = false
        if (LightLayout.idleAllowed(options.idleSeconds, lifecycle.isAtLeast(Lifecycle.State.RESUMED), overlay, slides.size)) {
            delay(options.idleSeconds * 1000L); idle = true
        }
    }
    LaunchedEffect(slides, heroFocused, idle, lifecycle, overlay, reduced) {
        if (!reduced && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !overlay && (!heroFocused || idle)) while (slides.size > 1) {
            delay(8_000)
            val index = slides.indexOfFirst { it.id == selected }.coerceAtLeast(0)
            selected = slides[(index + 1) % slides.size].id
        }
    }
    fun cycle(delta: Int) { if (slides.isNotEmpty()) selected = slides[Math.floorMod(slides.indexOf(current) + delta, slides.size)].id }
    fun focusApps() { scope.launch { state.scrollToItem(if (apps.isEmpty() && hasFolders) 1 else 0); withFrameNanos { }; runCatching { appsFocus.requestFocus() } } }
    fun goTop() { runCatching { topFocus.requestFocus() } }
    fun goHero() { if (current != null) scope.launch { state.scrollToItem(0); withFrameNanos { }; runCatching { heroFocus.requestFocus() } } else goTop() }
    fun dockUp() { if (shelfOn && shelf.isNotEmpty() && shelfPkg != null) runCatching { shelfFocus.requestFocus() } else goHero() }
    fun headerDown() { if (shelfOn && shelf.isNotEmpty() && shelfPkg != null) dockUp() else if (current != null) goHero() else if (apps.isNotEmpty() || hasFolders) focusApps() }
    // The target Ember falls back to when the window returns with nothing focused (#103).
    val navigation = LocalNavigationFocus.current
    val entry = when { current != null -> heroFocus; apps.isNotEmpty() || hasFolders -> appsFocus; else -> topFocus }
    DisposableEffect(navigation, entry) {
        navigation?.register(NavTabs.HOME, LIGHT_ENTRY, entry)
        navigation?.focused(NavTabs.HOME, LIGHT_ENTRY)
        onDispose { navigation?.unregister(NavTabs.HOME, LIGHT_ENTRY, entry) }
    }
    val latestCurrent by rememberUpdatedState(current)
    LaunchedEffect(profile) {
        delay(100)
        val returning = FocusMemory.pending?.let { key -> slides.firstOrNull { it.meta?.mediaKey() == key } }
        if (returning != null) {
            selected = returning.id; state.scrollToItem(0); withFrameNanos { }
            if (runCatching { heroFocus.requestFocus() }.getOrDefault(false)) FocusMemory.pending = null
            return@LaunchedEffect
        }
        // First load (#100): the carousel takes focus, now or when it arrives a moment later,
        // unless a key was pressed meanwhile. Apps (or the top) hold focus until then.
        val firstLoad = StartupHeroFocus.waiting()
        if (!firstLoad || latestCurrent == null) {
            if (apps.isNotEmpty() || hasFolders) {
                state.scrollToItem(if (apps.isEmpty() && hasFolders) 1 else 0); withFrameNanos { }
                runCatching { appsFocus.requestFocus() }
            } else goTop()
        }
        if (firstLoad) {
            if (latestCurrent == null) kotlinx.coroutines.withTimeoutOrNull(StartupHeroFocus.WINDOW_MS) { snapshotFlow { latestCurrent != null }.first { it } }
            if (latestCurrent != null && StartupHeroFocus.waiting()) { state.scrollToItem(0); claimStartupFocus(heroFocus) }
        }
    }
    fun launch(app: AppEntry) {
        if (launching != null) return
        launching = app.pkg
        scope.launch {
            if (millis > 0) delay(millis.toLong())
            launching = null
            if (vm.profiles.profileId == profile && vm.appAccess.canLaunch(app.pkg)) onLaunch(app)
        }
    }
    fun openSlide() {
        val slide = current ?: return
        slide.meta?.let { onOpenTitle(it); return }
        slide.external?.let { item ->
            val app = vm.appAccess.page(false).firstOrNull { it.pkg == item.pkg }
            val intent = LightMediaProvider.episodeIntent(item)
            if (app != null && intent != null) launch(app.copy(intent = intent))
            else vm.showNotice("This app no longer provides a playable link.")
        }
    }
    val baseTint=when(vm.look.lightWallpaper){"ocean"->Color(0xFF75CCD9);"ember"->Color(0xFFE9AC8F);"aurora"->Color(0xFFB6A9EF);else->Color(0xFFBFD4FF)}
    val sample=desktop.wallpaperColours && render.colorAnalysis && !render.lowPower
    val tint=rememberLightPalette(current?.artwork,sample,baseTint)
    // Same loader as Modern: right-sized URLs, a quick card-size picture first, and the previous
    // picture kept on screen until the next one is decoded. The next titles are fetched ahead.
    val heroMeta = current?.meta
    val ready = rememberReadyArtwork(heroMeta)
    val heroMetas = remember(slides) { slides.mapNotNull { it.meta } }
    PrefetchHeroArtwork(heroMetas, heroMetas.indexOfFirst { it.mediaKey() == heroMeta?.mediaKey() }.coerceAtLeast(0))
    var heroImage by remember(profile) { mutableStateOf<ReadyArtwork?>(null) }
    LaunchedEffect(ready, current?.id) {
        val meta = current?.meta ?: return@LaunchedEffect
        if (ready.drawable != null && ready.meta?.mediaKey() == meta.mediaKey()) heroImage = ready
    }
    val externalArt = current?.takeIf { it.meta == null }?.artwork
    LaunchedEffect(externalArt, render) {
        val url = externalArt ?: return@LaunchedEffect
        val request = coil.request.ImageRequest.Builder(context).data(if (url.startsWith("http")) heroArtworkUrl(url, render) else url)
            .size(render.heroMaxWidth, render.heroMaxHeight).allowHardware(render.hardwareHero).build()
        (context.imageLoader.execute(request) as? coil.request.SuccessResult)?.drawable?.let { heroImage = ReadyArtwork(null, it) }
    }
    CompositionLocalProvider(LocalLightTint provides tint,LocalNight provides true, androidx.tv.material3.LocalContentColor provides Color.White) {
        var lightOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var dockHeight by remember { mutableStateOf(0.dp) }
        val densityNow = androidx.compose.ui.platform.LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { lightOrigin = it.positionInRoot() }.pointerInput(profile) {
            awaitPointerEventScope { while (true) { if (awaitPointerEvent().changes.any { it.pressed }) active() } }
        }.onPreviewKeyEvent { event ->
            if (wakeKey == event.key) { if (event.type == KeyEventType.KeyUp) wakeKey = null; true }
            else if (idle) { active(); if (event.type == KeyEventType.KeyDown) wakeKey = event.key; true }
            else {
                if (event.type == KeyEventType.KeyDown) active()
                false
            }
        }) {
            val grid = LightLayout.grid(maxWidth.value)
            val windowWidth = maxWidth
            val windowHeight = maxHeight
            // The dock sits so that the top of the next app row peeks out at the bottom edge.
            val dockTop = (maxHeight - (if (dockHeight > 0.dp) dockHeight else (grid.height + 74f).dp) - DOCK_GAP - ROW_PEEK).coerceAtLeast(maxHeight * .5f)
            val heroHeight by animateDpAsState(if (idle) maxHeight else dockTop, tween(millis), label = "Light carousel height")
            LightWallpaper(vm.look.lightWallpaper)
            val wallpaperColors = remember(vm.look.lightWallpaper) { lightWallpaperColors(vm.look.lightWallpaper) }
            val publish = LocalArtworkPresented.current
            val shownArt = heroImage?.takeIf {current != null}?.let {art ->
                art.copy(focus=art.focus ?: HeroFocus(.5f,.3f),viewport=HeroViewport(lightOrigin.x,lightOrigin.y,
                    with(densityNow){windowWidth.toPx()},with(densityNow){windowHeight.toPx()},crop=true))
            } ?: ReadyArtwork(null,null)
            SideEffect {publish(shownArt)}
            heroImage?.takeIf { current != null && it.drawable != null }?.let { shown ->
                // Keep one full-window crop behind both the hero and drawer, including idle transitions.
                val imageModifier = Modifier.fillMaxSize()
                if (millis == 0) LightHeroImage(shown, imageModifier)
                else androidx.compose.animation.Crossfade(shown, animationSpec = tween(millis * 3), label = "Light hero") { layer -> LightHeroImage(layer, imageModifier) }
            }
            // Fade into the selected wallpaper below the dock without clipping the image at its edge.
            val heroFraction = if (windowHeight > 0.dp) (heroHeight / windowHeight).coerceIn(.3f, 1f) else 1f
            val fadeShoulder = .92f * heroFraction
            val scrim = Brush.verticalGradient(
                0f to Color(0x48000000), .22f * heroFraction to Color.Transparent, .5f * heroFraction to Color.Transparent,
                fadeShoulder to Color.Black.copy(alpha = .58f),
                (fadeShoulder + 1f) / 2f to wallpaperColors.last().copy(alpha = .82f),
                1f to wallpaperColors.last())
            Box(Modifier.fillMaxSize().background(scrim))
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (idle) 0f else 1f },
                userScrollEnabled = !idle, contentPadding = PaddingValues(start = grid.outer.dp, end = grid.outer.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item("dock") {
                    Column {
                        Spacer(Modifier.height(dockTop))
                        Box(Modifier.onSizeChanged { dockHeight = with(densityNow) { it.height.toDp() } }) {
                        Row(Modifier.glassSurface(RoundedCornerShape(DOCK_RADIUS),dock=true).padding(horizontal = grid.inset.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(grid.gap.dp)) {
                            apps.take(6).forEachIndexed { i, app -> LightAppTile(app, grid, millis, launching == app.pkg,
                                Modifier.then(if (i == 0) Modifier.focusRequester(appsFocus) else Modifier).lightArrows(onUp = ::dockUp)
                                    .onFocusChanged { if (it.hasFocus) shelfPkg = app.pkg }, { launch(app) }, { onAppOptions(app) }) }
                            if (apps.isEmpty()) Text(stringResource(R.string.ui_no_apps_to_show), color = Color.White, modifier = Modifier.padding(16.dp))
                        }
                        }
                    }
                }
                val cells = namedFolders.map {"folder:${it.id}"} + apps.drop(6).map {"app:${it.pkg}"} + if (hasMore) listOf("more") else emptyList()
                items((cells.size + 5) / 6, key = { "grid:$it" }) { row ->
                    Row(Modifier.padding(horizontal = grid.inset.dp), horizontalArrangement = Arrangement.spacedBy(grid.gap.dp)) {
                        cells.drop(row * 6).take(6).forEachIndexed {index,cell ->
                            val first=if(apps.isEmpty() && row==0 && index==0)Modifier.focusRequester(appsFocus).lightArrows(onUp=::goHero) else Modifier
                            when {
                                cell=="more"->LightFolderTile(grid,first){panel="apps"}
                                cell.startsWith("folder:")->namedFolders.firstOrNull {it.id==cell.removePrefix("folder:")}?.let {f->
                                    LightFolderTile(grid,first,f.name){vm.homeFeatureAction="folder:${f.id}"}}
                                else->apps.firstOrNull {it.pkg==cell.removePrefix("app:")}?.let {app->LightAppTile(app,grid,millis,launching==app.pkg,
                                    first.onFocusChanged { if (it.hasFocus) shelfPkg = null },{launch(app)},{onAppOptions(app)})}
                            }
                        }
                    }
                }
            }
            val showShelf = shelfOn && shelfPkg != null && shelf.isNotEmpty() && !idle && heroInView
            if (showShelf) {
                Box(Modifier.fillMaxWidth().height(heroHeight).background(Brush.verticalGradient(0f to Color(0x99000000), 1f to Color(0xCC000000))))
                Column(Modifier.align(Alignment.TopStart).height(heroHeight).fillMaxWidth().padding(bottom = 28.dp), verticalArrangement = Arrangement.Bottom) {
                    LightAppShelf(shelf, shelfFocus) { card ->
                        card.meta?.let { onOpenTitle(it); return@LightAppShelf }
                        val item = card.media ?: return@LightAppShelf
                        val app = vm.appAccess.page(false).firstOrNull { it.pkg == item.pkg }
                        val intent = LightMediaProvider.episodeIntent(item)
                        if (app != null && intent != null) launch(app.copy(intent = intent))
                        else vm.showNotice("This app no longer provides a playable link.")
                    }
                }
            }
            if (current != null && (idle || heroInView) && !showShelf) Column(Modifier.align(Alignment.TopStart).padding(horizontal = 45.dp).height(heroHeight).fillMaxWidth(), verticalArrangement = Arrangement.Bottom) {
                Column(Modifier.widthIn(max = 580.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (current.continuing) stringResource(R.string.ui_watch_next) + current.subtitle.removePrefix("Continue watching") else current.subtitle,
                        color = Color(0xFFD8DEF3), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.title, fontSize = if (idle) 38.sp else 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.graphicsLayer { alpha = if (idle) 0f else 1f }.onFocusChanged { heroFocused = it.hasFocus }.focusGroup(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LightButton(if (current.external?.continuing == true) "Resume" else if (current.external != null) "Open" else "Watch now",
                            Icons.Outlined.PlayArrow,
                            Modifier.focusRequester(heroFocus).lightArrows(onUp = ::goTop, onDown = ::focusApps)
                                .onPreviewKeyEvent { e -> if (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) {
                                    if (e.type == KeyEventType.KeyDown) cycle(if (e.key == Key.DirectionLeft) -1 else 1); true
                                } else false }, onLongClick = if(current.external!=null)({panel="hero"}) else current.meta?.let {m->({titleActions?.invoke(m);Unit})}, onClick = ::openSlide)
                        if (slides.size > 1) {
                            LightIcon("Previous item", Icons.Outlined.ChevronLeft, Modifier.lightArrows(onUp = ::goTop, onDown = ::focusApps)) { cycle(-1) }
                            LightIcon("Next item", Icons.Outlined.ChevronRight, Modifier.lightArrows(onUp = ::goTop, onDown = ::focusApps)) { cycle(1) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        slides.forEach { slide -> Box(Modifier.size(if (slide.id == current.id) 6.dp else 4.dp).clip(CircleShape).background(Color.White.copy(alpha = if (slide.id == current.id) .95f else .35f))) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().graphicsLayer { alpha = if (idle) 0f else 1f }.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (windowWidth >= (if(vm.launcherExtras.options.nowPlaying) 1000.dp else 800.dp)) LightButton(vm.weather.weatherText ?: "Weather", Icons.Outlined.Cloud, Modifier.widthIn(max = 240.dp).focusRequester(topFocus).lightArrows(onDown = ::headerDown), glass = true) { panel = "weather" }
                else LightIcon("Weather", Icons.Outlined.Cloud, Modifier.focusRequester(topFocus).lightArrows(onDown = ::headerDown), glass = true) { panel = "weather" }
                if (inputs.isNotEmpty()) LightIcon("Inputs", Icons.AutoMirrored.Outlined.Input, Modifier.lightArrows(onDown = ::headerDown), glass = true) {
                    if (vm.profiles.profile.kids) onTab(NavTabs.QUICK) else panel = "inputs"
                }
                if(vm.launcherExtras.options.nowPlaying && !vm.profiles.profile.kids)LightIcon("Now Playing",Icons.Outlined.MusicNote,glass=true){panel="playing"}
                Spacer(Modifier.weight(1f))
                Row(Modifier.lightGlass(30).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (online) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, if (online) "Internet connected" else "No internet", Modifier.size(18.dp), tint = Color.White.copy(alpha = .8f))
                    Text(rememberLauncherTime(), fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp))
                    if (NavTabs.SEARCH in tabs) LightIcon("Search", Icons.Outlined.Search, Modifier.lightArrows(onDown = ::headerDown)) { onTab(NavTabs.SEARCH) }
                    LightIcon("Control Center", Icons.Outlined.Tune, Modifier.lightArrows(onDown = ::headerDown)) { onTab(NavTabs.QUICK) }
                    ProfileMenuButton(vm.profiles.profile, Modifier.lightArrows(onDown = ::headerDown), size = 34.dp)
                }
            }
            if (idle) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) { val event = awaitPointerEvent(); event.changes.forEach { it.consume() }; active() } }
            })
        }
        when (panel) {
            "playing"->MediaDialog("Now Playing",{panel=null}){NowPlayingPanel(vm)}
            "hero" -> MediaDialog(current?.title ?: "Carousel",{panel=null}) {
                current?.external?.let {item->
                    Chip(if(item.continuing)"Remove from Continue Watching" else "Dismiss recommendation") {vm.personalHomes.dismiss(item.id,LightMediaProvider.engagementToken(item));panel=null}
                    if(!item.continuing)Chip("Hide this app’s recommendations") {vm.personalHomes.update {it.copy(mutedApps=it.mutedApps+item.pkg)};panel=null}
                }
            }
            "weather" -> SlidePanel(vm.weather.weatherPlaceName ?: "Weather", { panel = null },
                subtitle = vm.weather.details?.let { forecast -> listOfNotNull(forecast.source,
                    vm.weather.updatedAt.takeIf { it > 0 }?.let { "Updated " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }).joinToString(" · ") }) { first ->
                vm.weather.weatherText?.let { Text(it, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
                vm.weather.details?.days?.forEachIndexed { index, day -> WeatherDayRow(day, if (index == 0) Modifier.focusRequester(first) else Modifier) }
                if (vm.weather.details == null) Text(if(vm.weather.weatherPlaceName==null)"Choose a city in Weather settings to see its forecast." else "The forecast is temporarily unavailable.", color = Mute, fontSize = 13.sp)
                Chip("Weather settings", modifier = if (vm.weather.details == null) Modifier.focusRequester(first) else Modifier, maxLines = 1) { panel = null; vm.openSettingsPage(SettingsTab.WEATHER.name) }
            }
            "inputs" -> MediaDialog("Inputs", { panel = null }) {
                inputs.forEach { input -> LightButton(input.label + if (input.connected == false) " · Disconnected" else "",
                    when (input.type) {
                        android.media.tv.TvInputInfo.TYPE_HDMI -> Icons.Outlined.SettingsInputHdmi
                        android.media.tv.TvInputInfo.TYPE_TUNER -> Icons.Outlined.Tv
                        else -> Icons.Outlined.SettingsInputComposite
                    }) {
                    panel = null; vm.openSettingsGuarded { DeviceInputs.open(context, input.id, includeTuners = true) }
                } }
            }
            "apps" -> MediaDialog("More Apps", { panel = null }) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val g = LightLayout.grid(maxWidth.value).let { it.copy(width = ((maxWidth.value - 5 * it.gap) / 6).coerceAtLeast(1f)) }
                    var showHidden by remember { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        folder.chunked(6).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(g.gap.dp)) { row.forEach { app ->
                            LightAppTile(app, g, millis, launching == app.pkg, Modifier, { panel = null; launch(app) }, { panel = null; onAppOptions(app) })
                        } } }
                        if (hiddenApps.isNotEmpty()) {
                            Chip(if (showHidden) "Done showing hidden apps" else "Show ${hiddenApps.size} hidden app(s)", maxLines = 1) { showHidden = !showHidden }
                            if (showHidden) hiddenApps.chunked(6).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(g.gap.dp)) { row.forEach { app ->
                                LightAppTile(app, g, millis, launching == app.pkg, Modifier, { panel = null; launch(app) }, { panel = null; onAppOptions(app) })
                            } } }
                        }
                    }
                }
            }
        }
    }
}

private val DOCK_RADIUS = 30.dp
/** LazyColumn spacing between the dock and the first grid row. */
private val DOCK_GAP = 24.dp
/** How much of the next app row shows under the dock. */
private val ROW_PEEK = 26.dp

@Composable
internal fun Modifier.lightGlass(radius: Int = 24): Modifier = glassSurface(RoundedCornerShape(radius.dp),dock=true)

/**
 * Always fills the hero area edge to edge. Modern's framing letterboxes a picture that would lose
 * much of itself to cropping, which in Light's wide carousel left the left side of the screen empty.
 */
@Composable
private fun LightHeroImage(art: ReadyArtwork, modifier: Modifier = Modifier) {
    val drawable = art.drawable ?: return
    val focus = art.focus
    coil.compose.AsyncImage(drawable, null, modifier, contentScale = ContentScale.Crop, alignment = lightHeroAlignment(focus))
}

private fun lightHeroAlignment(focus: HeroFocus?) =
    androidx.compose.ui.BiasAlignment(((focus?.x ?: .5f) * 2f - 1f).coerceIn(-1f, 1f), ((focus?.y ?: .3f) * 2f - 1f).coerceIn(-1f, 1f))

@Composable
private fun WeatherDayRow(day: app.ember.tv.weather.WeatherDay, modifier: Modifier = Modifier) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val label = remember(day.date, locale) { runCatching { java.time.LocalDate.parse(day.date).let { date ->
        if (date == java.time.LocalDate.now()) "Today" else date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale) + " " + date.dayOfMonth
    } }.getOrDefault(day.date) }
    // Focusable so the remote can move through the days; nothing happens on OK.
    Focusable(onClick = {}, modifier = modifier.fillMaxWidth(), radius = TvTokens.Shape.Card, container = Panel, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, Modifier.width(64.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(app.ember.tv.weather.Weather.describe(day.code), Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${day.high?.let { "$it°" } ?: "—"} / ${day.low?.let { "$it°" } ?: "—"}", fontSize = 14.sp, maxLines = 1)
        }
    }
}

private fun Modifier.lightArrows(onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null): Modifier = onPreviewKeyEvent { event ->
    val action = when (event.key) { Key.DirectionUp -> onUp; Key.DirectionDown -> onDown; else -> null }
    if (action != null) { if (event.type == KeyEventType.KeyDown) action(); true } else false
}

@Composable
private fun LightIcon(label: String, icon: ImageVector, modifier: Modifier = Modifier, glass: Boolean = false, onClick: () -> Unit) {
    // glass: the same material as the rest of the top bar and the system (#65).
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick, modifier.size(36.dp).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label }, radius = TvTokens.Shape.Large,
        container = if (glass) Color.Transparent else Color.White.copy(alpha = .06f), focusedContainer = androidx.compose.ui.graphics.lerp(LocalLightTint.current,Color.White,.65f), focusedContent = Color(0xFF101722)) {
        Box(Modifier.fillMaxSize().then(if (glass && !focused) Modifier.glassSurface(RoundedCornerShape(TvTokens.Shape.Large.dp), dock = true) else Modifier),
            contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
    }
}

@Composable
private fun LightButton(label: String, icon: ImageVector, modifier: Modifier = Modifier, glass: Boolean = false, onLongClick:(()->Unit)?=null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick, modifier.onFocusChanged { focused = it.isFocused }, onLongClick=onLongClick,radius = TvTokens.Shape.Panel,
        container = if (glass) Color.Transparent else LocalLightTint.current.copy(alpha=.22f), focusedContainer = androidx.compose.ui.graphics.lerp(LocalLightTint.current,Color.White,.7f), focusedContent = Color(0xFF101722)) {
        Row(Modifier.then(if (glass && !focused) Modifier.glassSurface(RoundedCornerShape(TvTokens.Shape.Panel.dp), dock = true) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(19.dp)); Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LightAppTile(app: AppEntry, grid: LightLayout.Grid, millis: Int, launching: Boolean, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val bitmap = rememberAppArtwork(app, circle = false)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (millis == 0) 1f else if (launching) 1.4f else if (focused) (if (LocalRenderOptions.current.lowPower) 1.02f else 1.2f) else 1f, tween(millis), label = "Light app lift")
    Column(modifier.width(grid.width.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Focusable(onClick, Modifier.fillMaxWidth().height(grid.height.dp).onFocusChanged { focused = it.isFocused }.graphicsLayer {
            scaleX = scale; scaleY = scale; translationY = if (focused && millis > 0) -3.dp.toPx() else 0f
        }, onLongClick = onLongClick, radius = grid.radius.toInt().coerceAtLeast(2), container = Color(0xFF262C3C), focusedScale = TvTokens.Focus.None) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Apps, app.label, Modifier.size(30.dp)) }
        }
        if (tileLabelShown(TileLabels.APPS)) Text(app.label, color = Color.White.copy(alpha = if (focused) 1f else .75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LightFolderTile(grid: LightLayout.Grid, modifier: Modifier = Modifier, label:String="More Apps", open: () -> Unit) {
    Column(modifier.width(grid.width.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Focusable(open, Modifier.fillMaxWidth().height(grid.height.dp), radius = TvTokens.Shape.Card, container = Color(0x325B668A)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Folder, label, Modifier.size(32.dp)) }
        }
        if (tileLabelShown(TileLabels.APPS)) Text(label, fontSize = 13.sp, color = Color.White)
    }
}

@Composable
private fun LightWallpaper(style: String) {
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(lightWallpaperColors(style))))
}

private fun lightWallpaperColors(style: String): List<Color> = when (style) {
        "aurora" -> listOf(Color(0xFF111838), Color(0xFF384F70), Color(0xFF372A56))
        "ocean" -> listOf(Color(0xFF071C32), Color(0xFF235F70), Color(0xFF0A2D43))
        "ember" -> listOf(Color(0xFF17121B), Color(0xFF674039), Color(0xFF251820))
        "black" -> listOf(Color.Black, Color.Black)
        else -> listOf(Color(0xFF0E1423), Color(0xFF242A43), Color(0xFF10141F))
}

@Composable
internal fun LightHomeSettings(vm: HomeViewModel) {
    val context = LocalContext.current
    var permission by remember { mutableStateOf(LightMediaProvider.granted(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    Section("Light Home") {
        Text(stringResource(R.string.ui_wallpaper), fontSize = 15.sp)
        ButtonRow { LightLayout.backgrounds.forEach { (id, label) ->
            val title = when (id) { "art" -> stringResource(R.string.ui_title_art); "black" -> stringResource(R.string.ui_black); "ember" -> stringResource(R.string.ui_ember_glow); else -> label }
            Chip(title, selected = vm.look.lightWallpaper == id) { vm.look.updateLightWallpaper(id) }
        } }
        Text(stringResource(R.string.ui_title_art_slowly_changes_between_your), color = Mute, fontSize = 13.sp)
        val options = vm.look.lightHome
        SettingToggle("Apps’ Continue Watching", null, options.watchNext) { vm.look.updateLightHome { it.copy(watchNext = !it.watchNext) } }
        if (!vm.profiles.profile.kids && !permission) Chip("Allow apps’ Continue Watching") { request.launch(LightMediaProvider.PERMISSION) }
        Text("App recommendations and Continue Watching depend on your TV and participating apps.", color = Mute, fontSize = 13.sp)
        val home=vm.personalHomes.current
        SettingToggle("Wallpaper colours", "Cached colour sampling; device performance settings take priority.",home.wallpaperColours) {vm.personalHomes.update {it.copy(wallpaperColours=!it.wallpaperColours)}}
        SettingToggle("Hide short clips", "Exclude external resume items shorter than 10 minutes.",home.hideShortClips) {vm.personalHomes.update {it.copy(hideShortClips=!it.hideShortClips)}}
        SettingToggle("Hide credits-only items",null,home.hideCredits) {vm.personalHomes.update {it.copy(hideCredits=!it.hideCredits)}}
        if(home.dismissed.isNotEmpty())Chip("Restore dismissed carousel items"){vm.personalHomes.update {it.copy(dismissed=emptyMap())}}
        vm.appAccess.page(false).forEach {app->SettingToggle("${app.label} recommendations",null,app.pkg !in home.mutedApps){vm.personalHomes.update {it.copy(mutedApps=if(app.pkg in it.mutedApps)it.mutedApps-app.pkg else it.mutedApps+app.pkg)}}}
        Chip("App folders") {vm.openSettingsPage("HOME_TOOLS")}
        SettingToggle("Recommendations", null, options.recommendations) { vm.look.updateLightHome { it.copy(recommendations = !it.recommendations) } }
        SettingToggle("App rows", "When an app in the dock is selected, show its own rows above it (Continue watching, Recently added…).",
            options.appRows) {
            val on = !options.appRows
            vm.look.updateLightHome { it.copy(appRows = on) }
            if (on && !permission && !vm.profiles.profile.kids) request.launch(LightMediaProvider.PERMISSION)
        }
        if (options.appRows) {
            if (!permission && !vm.profiles.profile.kids) Chip("Allow apps’ rows") { request.launch(LightMediaProvider.PERMISSION) }
            Text("Plex, Jellyfin and Emby show your server's rows when the server is connected in Ember. Other apps show only the rows they share with Android TV; on Google TV many apps (Plex among them) share none with other launchers.",
                color = Mute, fontSize = 13.sp)
        }
        LightCarouselCatalog(vm)
        SettingToggle("Motion", "Also respects device performance and reduced-motion settings.", options.motion) { vm.look.updateLightHome { it.copy(motion = !it.motion) } }
        Text("Full-screen carousel when idle", fontSize = 15.sp)
        ButtonRow { listOf(0, 15, 30, 60, 120).forEach { seconds -> Chip(if (seconds == 0) "Off" else "${seconds}s", selected = options.idleSeconds == seconds) { vm.look.updateLightHome { it.copy(idleSeconds = seconds) } } } }
    }
}

/** Which Home catalog leads Light's carousel (#61): the same choices as Modern's hero banner. */
@Composable
private fun LightCarouselCatalog(vm: HomeViewModel) {
    var open by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val chosen = vm.look.lightHome.heroCatalog
    Chip("Carousel catalog: " + catalogSourceLabel(vm, chosen), modifier = Modifier.focusRequester(focus)) { open = true }
    Text("Its titles play after Continue watching. Choose any addon catalog, or mix the Home catalogs automatically.", color = Mute, fontSize = 13.sp)
    if (open) CatalogSourcePicker(vm, "Carousel catalog", chosen, { key -> vm.look.updateLightHome { it.copy(heroCatalog = key) } }) { open = false }
    RestoreToolbarFocus(open, focus)
}

/** Light Home's default focus target, for a window that returns with nothing focused (#103). */
internal const val LIGHT_ENTRY = "light-entry"
