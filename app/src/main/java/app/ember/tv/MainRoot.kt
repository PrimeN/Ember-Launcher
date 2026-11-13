package app.ember.tv

import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight

import app.ember.tv.data.launchInstalledApp
import app.ember.tv.ui.guardOpeningPress
import app.ember.tv.ui.sceneEnter
import app.ember.tv.ui.MediaDialog
import app.ember.tv.ui.TvTokens

import app.ember.tv.ui.detailsFocusGroup
import app.ember.tv.ui.MenuActionRow
import app.ember.tv.ui.CompactActionMenu
import app.ember.tv.ui.AppManagement
import app.ember.tv.data.mediaKey
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.ember.tv.R

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Text
import app.ember.tv.data.LightNavigation
import app.ember.tv.data.AppEntry
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Prefs
import app.ember.tv.player.PlayRequest
import app.ember.tv.player.PlayerApp
import app.ember.tv.streaming.Offer
import app.ember.tv.ui.AppsScreen
import app.ember.tv.ui.Chip
import app.ember.tv.ui.DetailsScreen
import app.ember.tv.ui.initialNavigationFocus
import app.ember.tv.ui.startupInput
import app.ember.tv.ui.EmberTheme
import app.ember.tv.ui.Ink
import app.ember.tv.ui.Mute
import app.ember.tv.ui.Panel
import app.ember.tv.ui.PanelHi
import app.ember.tv.ui.SettingsScreen
import app.ember.tv.ui.DiscoverScreen
import app.ember.tv.ui.LibraryScreen
import app.ember.tv.ui.SearchScreen
import app.ember.tv.ui.WatchlistScreen
import androidx.lifecycle.compose.currentStateAsState
import app.ember.tv.ui.sceneContent
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun Root(
    vm: HomeViewModel, tab: String, onTab: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit, onPlay: (PlayRequest) -> Unit,
    onPlayWith: (PlayRequest, String) -> Unit, onOpenOffer: (Offer) -> Unit,
    onPlayTrailer: (String) -> Unit,
    onPlayChannel: (List<app.ember.tv.iptv.Channel>, Int) -> Unit,
) {
    val navigation = remember(vm.profiles.profileId) { app.ember.tv.ui.NavigationFocus() }
    val reduced = app.ember.tv.ui.rememberReduceMotion()
    var titleActions by remember(vm.profiles.profileId) { mutableStateOf<Meta?>(null) }
    // Artwork belongs to the page (tab) that presented it, so the navigation glass and backdrop of
    // Live, Apps or Settings never keep the Home banner's picture.
    val noArtwork = remember { app.ember.tv.ui.ReadyArtwork(null, null) }
    var presentedArt by remember(vm.profiles.profileId) { mutableStateOf(tab to noArtwork) }
    val currentTab by rememberUpdatedState(tab)
    val backdropArt = presentedArt.second.takeIf { presentedArt.first == tab } ?: noArtwork
    val renderOptions = app.ember.tv.ui.rememberDeviceRenderOptions(vm.look.contentDensity, vm.look.navigationGlass, vm.look.artworkQuality,vm.look.devicePerformance,vm.look.customPerformance,app.ember.tv.ui.RuntimeBudgets.slowAuto).let {it.copy(glassMethod=vm.look.navigationGlassMethod)}
    // Card artwork reads this directly; set before the rows below are drawn.
    app.ember.tv.ui.Art.ultra = renderOptions.ultra
    val performanceContext=androidx.compose.ui.platform.LocalContext.current
    androidx.lifecycle.compose.LifecycleStartEffect(performanceContext,vm.look.devicePerformance) {
        val stop=if(vm.look.devicePerformance=="auto" && performanceContext is android.app.Activity)app.ember.tv.ui.RuntimeBudgets.observe(performanceContext) else ({})
        onStopOrDispose {stop()}
    }
    // Per page and profile: within a page the last glass sample stays until the next is ready (#40).
    val glassBitmap = app.ember.tv.ui.rememberGlassBitmap(backdropArt, renderOptions, Triple(navigation, tab, vm.profiles.profileId))
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val internet=app.ember.tv.ui.rememberLightInternet()
    DisposableEffect(performanceContext) {val stop=app.ember.tv.home.StartupEvents.watch(performanceContext);onDispose {stop()}}
    val startupPulse by app.ember.tv.home.StartupEvents.events.collectAsState()
    LaunchedEffect(internet,lifecycle) {if(internet && lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))vm.weather.refreshWeather()}
    LaunchedEffect(startupPulse,lifecycle,vm.apps.size,vm.profiles.profileId,vm.profiles.showProfilePicker,vm.pin.pinPrompt) {
        if(!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || vm.profiles.showProfilePicker || vm.pin.pinPrompt!=null)return@LaunchedEffect
        val event=app.ember.tv.home.StartupEvents.pending(performanceContext) ?: return@LaunchedEffect
        val config=vm.launcherExtras.options
        if(config.destination=="app" && vm.apps.isEmpty())return@LaunchedEffect
        app.ember.tv.home.StartupEvents.consume(performanceContext,event)
        if(!config.startup(event.first,vm.profiles.profile.kids))return@LaunchedEffect
        val owner=vm.profiles.profileId
        when(config.destination) {
            "app"->vm.apps.firstOrNull {it.pkg==config.target && vm.appAccess.canLaunch(it.pkg)}?.let(onLaunch)
                ?: vm.showNotice("The startup app is unavailable. Home remains available.")
            "input"->{val inputs=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){app.ember.tv.device.DeviceInputs.list(performanceContext,true)}
                if(owner==vm.profiles.profileId && inputs.any {it.id==config.target})app.ember.tv.device.DeviceInputs.open(performanceContext,config.target,true)}
            "presentation"->if(config.slides.isNotEmpty())app.ember.tv.home.PresentationActivity.start(performanceContext)
        }
    }

    val windowFocused = app.ember.tv.ui.rememberWindowFocused()
    LaunchedEffect(vm) {
        // Give composition/layout a frame to present cached content before automatic refresh.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        vm.onUiPresented()
    }
    LaunchedEffect(vm, lifecycle, windowFocused) {
        vm.onUiActive(windowFocused && lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    LaunchedEffect(lifecycle, tab) {
        if (!lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) vm.explore.background()
        else if (tab == NavTabs.HOME) {
            vm.awaitUiPresentation()
            vm.explore.refreshHome { vm.sections.sectionOn(it) }
            vm.refreshWidgets()
        }
    }
    val interactive = windowFocused && lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
        !vm.quickPanel && !vm.profiles.showProfilePicker && !vm.profiles.showProfileMenu && vm.profiles.setupRequested==null &&
        vm.link == null && vm.pin.pinPrompt == null && vm.resumeAsk == null && vm.playing.chooser == null
    val startup=vm.startupPresentation
    val startupDialog=vm.profiles.showProfilePicker || vm.profiles.showProfileMenu || vm.profiles.setupRequested!=null ||
        vm.link!=null || vm.pin.pinPrompt!=null || vm.resumeAsk!=null || vm.playing.chooser!=null || vm.quickPanel
    LaunchedEffect(startup.stage,navigation.startup.established,windowFocused,lifecycle,startupDialog,tab,vm.details!=null) {
        if(windowFocused && lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            (navigation.startup.established || app.ember.tv.ui.Ui.light || tab==NavTabs.SETTINGS || vm.details!=null || startupDialog))
            startup.controlsReady()
    }
    // Home never waits behind the startup curtain for more than a moment after its first frame (#33),
    // whatever the focus handoff is doing.
    LaunchedEffect(startup.frameShown) {
        if(startup.frameShown && !startup.ready) {kotlinx.coroutines.delay(app.ember.tv.state.StartupPresentation.MAX_CURTAIN_MS);startup.revealForInput()}
    }
    LaunchedEffect(vm.profiles.profileId,app.ember.tv.data.WatchedStore.version,lifecycle) {
        if(lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))vm.reloadWatched()
    }
    androidx.compose.runtime.CompositionLocalProvider(
        app.ember.tv.ui.LocalInternetStatus provides internet,
        app.ember.tv.ui.LocalAppArtwork provides vm.personalHomes.current.artwork,
        app.ember.tv.ui.LocalNavigationFocus provides navigation,
        app.ember.tv.ui.LocalFocusProfile provides vm.profiles.profileId,
        app.ember.tv.ui.LocalTitleActions provides { titleActions = it },
        app.ember.tv.ui.LocalReduceMotion provides reduced,
        app.ember.tv.ui.LocalRenderOptions provides renderOptions,
        app.ember.tv.ui.LocalTileLabelsOff provides vm.look.tileLabelsOff,
        app.ember.tv.ui.LocalHeroArtworkMode provides vm.look.heroArtworkMode,
        app.ember.tv.ui.LocalGlassBitmap provides glassBitmap.bitmap,
        app.ember.tv.ui.LocalGlassPreblurred provides glassBitmap.preblurred,
        app.ember.tv.ui.LocalGlassOrigin provides glassBitmap.origin,
        app.ember.tv.ui.LocalBackdropArtwork provides backdropArt,
        app.ember.tv.ui.LocalArtworkPresented provides { presentedArt = currentTab to it },
        app.ember.tv.ui.LocalSceneInteractive provides interactive,
        app.ember.tv.ui.LocalWatchedUi provides app.ember.tv.ui.WatchedUi(vm.look.showWatchedBadges,vm.look.blurUnwatchedEpisodes,vm::isWatched,vm::isEpisodeWatched,ready=vm.watchedReady),
        app.ember.tv.ui.LocalCardFocusStyle provides vm.look.cardFocusStyle,
        app.ember.tv.ui.LocalProfileMenu provides app.ember.tv.ui.ProfileMenuConfig(
            profiles = vm.profiles.all,
            actions = app.ember.tv.ui.profileMenuActions(vm.profiles.profile, NavTabs.LIBRARY in vm.visibleTabs),
            onOpenChanged = { vm.profiles.showProfileMenu = it },
            onSwitch = vm.profiles::switchProfile,
            onAction = { action ->
                action.settings?.let(vm::openSettingsPage)
                action.page?.let { page ->
                    // Recheck at activation after a possible profile edit.
                    if (vm.profiles.personalOn && (page != NavTabs.LIBRARY || page in vm.visibleTabs)) {
                        vm.closeDetails()
                        vm.collections.closeFolder()
                        onTab(page)
                    }
                }
            },
        ),
    ) {
        val focusPresence = remember { app.ember.tv.ui.FocusPresence() }
        Box(Modifier.fillMaxSize().onFocusChanged { focusPresence.any = it.hasFocus }
            .then(if(!startup.ready)Modifier.clearAndSetSemantics {} else Modifier)) {
        androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalFocusPresence provides focusPresence) {
        app.ember.tv.ui.FocusBackdropHost(vm,
            scope = listOf(vm.profiles.profile, tab, vm.collections.open?.first?.id, vm.collections.open?.second?.id, vm.serverBrowser.library?.ref),
            // Search and Live TV paint their own theme-coloured background (#120, #137), not the focused tile's.
            enabled = vm.details == null && tab != NavTabs.SEARCH && tab != NavTabs.LIVE,
            glow = app.ember.tv.data.FocusBackground.glowPage(vm.look.mediaFocusBackground, tab, vm.look.uiStyle == app.ember.tv.data.InterfaceStyles.LIGHT)) {
            RootContent(vm, tab, onTab, onLaunch, onPlay, onPlayWith, onOpenOffer, onPlayTrailer, onPlayChannel)
        }
        }
        }
        app.ember.tv.ui.StartupCurtain(startup)
        titleActions?.let { selected -> app.ember.tv.ui.TitleActions(selected, vm) { titleActions = null } }
    }
}
@Composable
private fun RootContent(
    vm: HomeViewModel,
    tab: String,
    onTab: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onPlayWith: (PlayRequest, String) -> Unit,
    onOpenOffer: (Offer) -> Unit,
    onPlayTrailer: (String) -> Unit,
    onPlayChannel: (List<app.ember.tv.iptv.Channel>, Int) -> Unit,
) {
    val settingsLifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val settingsVisible=tab==NavTabs.SETTINGS || vm.quickPanel || vm.look.settingsDestination!=null || vm.homeFeatureAction?.startsWith("edit:")==true
    LaunchedEffect(vm.profiles.profile,settingsVisible,settingsLifecycle,vm.settingsAllowed) {
        if(!settingsVisible || !settingsLifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))vm.revokeSettingsAccess()
        if(settingsVisible && !vm.settingsAllowed) {
            vm.quickPanel=false;vm.look.settingsDestination=null
            if(vm.homeFeatureAction?.startsWith("edit:")==true)vm.homeFeatureAction=null
            if(tab==NavTabs.SETTINGS)onTab(NavTabs.HOME)
        }
    }
    LaunchedEffect(vm.look.settingsDestination) {
        vm.look.settingsDestination?.let { page ->
            vm.look.settingsDestination = null
            vm.look.settingsJump = page
            vm.quickPanel = false
            vm.closeDetails()
            vm.collections.closeFolder()
            onTab(NavTabs.SETTINGS)
        }
    }
    val discoveryInteractive = app.ember.tv.ui.LocalSceneInteractive.current
    LaunchedEffect(tab, vm.profiles.profileId, vm.explore.saved, vm.sections.layout, vm.watch.watchRegion, discoveryInteractive, vm.widgets.revision) {
        if (tab != NavTabs.DISCOVER || !discoveryInteractive) vm.explore.pause()
        if (tab == NavTabs.HOME && discoveryInteractive) { vm.explore.refreshHome { vm.sections.sectionOn(it) }; vm.refreshWidgets() }
    }
    val navigationContext = androidx.compose.ui.platform.LocalContext.current
    var options by remember { mutableStateOf<AppEntry?>(null) }
    var moving by remember { mutableStateOf<AppEntry?>(null) }
    LaunchedEffect(vm.apps, vm.profiles.profile, vm.look.uiStyle, vm.visibleTabs, options?.pkg, moving?.pkg) {
        if (options?.let { !vm.canManageHomeItem(it.pkg) } == true) options = null
        if (moving?.let { !vm.canManageHomeItem(it.pkg) } == true) moving = null
    }
    val launchApp:(AppEntry)->Unit={app->if(options==null && moving==null)onLaunch(app)}
    app.ember.tv.ui.HomeFeatureDialogs(vm,onLaunch)
    LaunchedEffect(vm.pendingAppLaunch) {vm.pendingAppLaunch?.let {vm.pendingAppLaunch=null;vm.quickPanel=false;onLaunch(it)}}
    var pageProfile by remember { mutableStateOf(vm.profiles.profileId) }
    LaunchedEffect(vm.profiles.profileId) {
        if (pageProfile != vm.profiles.profileId) {
            pageProfile = vm.profiles.profileId
            options = null
            moving = null
            vm.quickPanel = false
            onTab(NavTabs.HOME)
        }
    }

    // Always handle Back: on Android 11 and older, letting it through would close the launcher itself.
    BackHandler { if (tab != NavTabs.HOME) onTab(NavTabs.HOME) }
    // Kept here so Home returns to the same place after a title screen closes.
    val homeState = rememberLazyListState()
    val pageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

    val d = vm.details
    if (d != null) {
        BackHandler { vm.closeDetails() }
        val navigation = app.ember.tv.ui.LocalNavigationFocus.current!!
        val detailsFocus = navigation.page("details")
        val backFocus = remember {androidx.compose.ui.focus.FocusRequester()}
        val mediaLayout = vm.look.mediaPageLayout
        val detailsListState = remember(d.meta.mediaKey(), mediaLayout) { androidx.compose.foundation.lazy.LazyListState() }
        val detailsDim by remember(detailsListState) { derivedStateOf {
            if (detailsListState.firstVisibleItemIndex > 0) 1f
            else (detailsListState.firstVisibleItemScrollOffset / 240f).coerceIn(0f, 1f)
        } }
        // #150: the theme's own night page colour, so the media page matches Home.
        val detailsBackground=if(mediaLayout == app.ember.tv.data.MediaPageLayouts.EMBER_X)app.ember.tv.ui.EmberXColors.Background
            else app.ember.tv.ui.emberColorScheme(vm.look.accent,false,app.ember.tv.ui.Ui.isOled).background
        app.ember.tv.ui.MediaPageLayoutScope(mediaLayout) {
        MaterialTheme(colorScheme=app.ember.tv.ui.emberColorScheme(vm.look.accent,false,app.ember.tv.ui.Ui.isOled).copy(background=detailsBackground,
            surface=detailsBackground,onSurface=androidx.compose.ui.graphics.Color.White,onBackground=androidx.compose.ui.graphics.Color.White)) {
        Box(Modifier.fillMaxSize().background(detailsBackground)) {
            app.ember.tv.ui.ModernBackdrop(d.meta,dim=detailsDim,backgroundColor=detailsBackground,heightFraction=if(mediaLayout == app.ember.tv.data.MediaPageLayouts.MODERN).78f else 1f)
            app.ember.tv.ui.MediaPageRightFade(detailsBackground)
            Column(Modifier.fillMaxSize().sceneEnter("details:${d.meta.mediaKey()}")) {
                Box(Modifier.fillMaxWidth().padding(start=TvTokens.Space.Page.dp,end=TvTokens.Space.Page.dp,top=12.dp,bottom=4.dp)) {
                    app.ember.tv.ui.DetailsBackButton(Modifier.focusRequester(backFocus)
                        .focusProperties {down=navigation.content("details")},vm::closeDetails)
                    if(mediaLayout != app.ember.tv.data.MediaPageLayouts.MODERN)app.ember.tv.ui.EmberLogo(28.dp,
                        androidx.compose.ui.graphics.Color.White, Modifier.align(androidx.compose.ui.Alignment.CenterEnd))
                }
                Box(Modifier.fillMaxWidth().weight(1f).detailsFocusGroup(detailsFocus,
                    restore={runCatching {navigation.preferred("details")?.requestFocus()==true}.getOrDefault(false)},
                    back={runCatching {backFocus.requestFocus()}.getOrDefault(false)})) {
                    androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalFocusScene provides "details") {
                    LaunchedEffect(d.meta.id) { vm.loadMyRating(d.meta) }
                    DetailsScreen(
                        modifier = Modifier,
                        d = d,
                        onOpenPersonTitle = vm::openDetails,
                        filterPersonTitles = vm::filterPersonTitles,
                        personProfileKey = vm.profiles.profileId,
                        listState = detailsListState,
                        saved = vm.isSaved(d.meta),
                        isContinue = vm.isContinueItem(d.meta),
                        onToggleSaved = { vm.toggleWatchlist(d.meta) },
                        onRemoveContinue = {
                            vm.removeFromContinue(d.meta)
                            vm.closeDetails()
                        },
                        onSeason = vm::selectSeason,
                        onEpisode = vm::selectEpisode,
                        onMoreEpisodes = vm::moreServerEpisodes,
                        formatSource = vm.sourceFormat::formatSource,
                        badgesFor = vm.sourceFormat::badgesFor,
                        hiddenBadgeCategories = vm.sourceFormat.activeBadgeCategoriesOff,
                        onPlayTrailer = onPlayTrailer,
                        ratingSources = if (vm.metadata.ratingsOn) vm.metadata.ratingSources.filter { it.id !in vm.metadata.ratingsOff } else emptyList(),
                        onPlay = { s -> vm.playRequest(s)?.let(onPlay) },
                        playButton = vm.playerSettings.playRules.enabled,
                        onPlayBest = {
                            val pick = app.ember.tv.data.PlayPicker.pick(d.streams, d.serverStreams, vm.playerSettings.playRules)
                            if (pick != null) vm.playRequest(pick)?.let(onPlay)
                            else vm.showNotice("No source meets your Play button rules yet. Pick one from the list, or change the rules in Settings › Play button.")
                        },
                        playbackLabel = vm.primaryPlaybackLabel,
                        watched = vm.detailsWatched,
                        onMarkWatched = vm::markWatched,
                        hasAddons = vm.addons.isNotEmpty(),
                        onRetrySources = vm.title::retrySources,
                        best = remember(d.streams,d.serverStreams,vm.playerSettings.playRules) {vm.playerSettings.bestSource(d)},
                        bestReason = remember(d.streams,d.serverStreams,d.loadingStreams,vm.playerSettings.playRules) {
                            if (d.loadingStreams) null else app.ember.tv.data.PlayPicker.reason(d.streams, d.serverStreams, vm.playerSettings.playRules)
                        },
                        onPlayWith = { s ->
                            val req = vm.playRequest(s) ?: return@DetailsScreen
                            // Torrent sources can only go to a torrent app, so there is nothing to choose.
                            if (s.isDirect) vm.playing.askPlayer(req) else onPlay(req)
                        },
                        offers = d.offers.filter { vm.watch.offerEnabled(it) } + d.addonLinks,
                        offersCredit = vm.watch.offersCredit,
                        onOpenOffer = onOpenOffer,
                        offerInstalled = { vm.watch.offerPackages(it).first != null },
                        autoLoad = vm.collections.autoPage,
                        canRate = vm.canRate(d.meta),
                        myRating = vm.myRating?.takeIf { it.first == d.meta.id }?.second,
                        onRate = { vm.rateTitle(d.meta, it) },
                    )
                    }
                }
            }
        }
        } }
    } else if (vm.collections.open != null) {
        // A Collection folder; its titles open the title screen above, and Back returns here.
        androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalMediaFocusKind provides app.ember.tv.ui.FocusTileKind.COLLECTION) {
          app.ember.tv.ui.ArtworkPageContent {
            app.ember.tv.ui.FolderScreen(vm, onOpenTitle = vm::openDetails)
          }
        }
    } else {
      // Keeps the rows' sideways scroll (and other saved state) while a title screen is open, so the
      // card it was opened from is still there to take focus back.
      pageState.SaveableStateProvider("main") {
        val tabs = vm.visibleTabs
        val current = app.ember.tv.ui.resolveNavigationPage(tab,tabs,vm.profiles.personalOn)
        LaunchedEffect(current) {
            when (current) {
                NavTabs.DISCOVER -> Unit // Discover owns section visibility and starts only its visible section.
                NavTabs.LIBRARY -> {
                    vm.openLibrary()
                    vm.openMedia()
                }
            }
        }
        // Modern look: the featured title's artwork fills the top of Home, behind the menu. Further
        // down the page it follows the focused card, dimmed.
        // The banner remembers which title it shows, not a position: when the rows refresh and the
        // banner's list changes order, the same title (and its loaded picture) stays on screen.
        var heroKey by remember { mutableStateOf<String?>(null) }
        val heroIndex = vm.heroItems.indexOfFirst { it.mediaKey() == heroKey }.coerceAtLeast(0)
        var ambient by remember(vm.profiles.profile) { mutableStateOf<Meta?>(null) }
        var presentedHero by remember(vm.profiles.profile) { mutableStateOf<Meta?>(null) }
        var pendingAmbient by remember(vm.profiles.profile) { mutableStateOf<Meta?>(null) }
        LaunchedEffect(pendingAmbient) {
            if (pendingAmbient != null) delay(180)
            ambient = pendingAmbient
        }
        val light = app.ember.tv.ui.Ui.light
        // Floating Settings opens as a panel over the current page instead of switching to it.
        val tabClick: (String) -> Unit = { id ->
            when {
                id == NavTabs.SCREENSAVER -> app.ember.tv.ui.QuickActions.screensaver(navigationContext)
                id == NavTabs.QUICK -> vm.openSettingsGuarded {vm.quickPanel = true}
                id == NavTabs.SETTINGS -> vm.openSettingsGuarded { onTab(id) }
                else -> onTab(id)
            }
        }
        val navigation = app.ember.tv.ui.LocalNavigationFocus.current!!
        val searchTabFocus = navigation.tab(NavTabs.SEARCH)
        // Start-up and TV wake (#103): the window can come back with nothing focused. Android then
        // spends the first D-pad press giving the view focus, and the next one searches from nowhere
        // and lands on the top-left tab (Search). Put focus on the page's default target instead.
        var contentHasFocus by remember { mutableStateOf(false) }
        val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        val rootView = androidx.compose.ui.platform.LocalView.current
        val focusPage by androidx.compose.runtime.rememberUpdatedState(current)
        // #130: the same recovery when the focused item disappeared (removed, cleared) and nothing
        // nearer took focus back: rows, grids and settings sections try first, within two frames.
        val presence = app.ember.tv.ui.LocalFocusPresence.current
        val anythingFocused = presence?.any ?: contentHasFocus
        LaunchedEffect(windowFocused, anythingFocused) {
            if (!windowFocused || anythingFocused) return@LaunchedEffect
            repeat(3) { androidx.compose.runtime.withFrameNanos { } }
            if (contentHasFocus || presence?.any == true) return@LaunchedEffect
            if (!rootView.hasFocus()) rootView.requestFocus()
            val page = focusPage
            // In order: Home's last card, the page's tab, the page's last target, the page itself
            // (Settings has no tab). A target that was removed fails and the next one is tried.
            val targets = listOfNotNull(
                if (page == NavTabs.HOME) navigation.preferred(NavTabs.HOME) else null,
                navigation.tab(app.ember.tv.ui.navigationTabForPage(page, tabs)).takeIf { !light },
                navigation.preferred(page).takeIf { !light && page != NavTabs.HOME },
                navigation.page(page).takeIf { !light })
            targets.firstOrNull { runCatching { it.requestFocus() }.getOrDefault(false) }
        }
        val pageBackdrop = remember(current, vm.profiles.profileId) { app.ember.tv.ui.PageBackdropState() }
        Box(Modifier.fillMaxSize().onFocusChanged { contentHasFocus = it.hasFocus }.initialNavigationFocus(navigation,
            app.ember.tv.ui.navigationTabForPage(current,tabs),
            enabled=discoveryInteractive,available=!light && current!=NavTabs.SETTINGS)) {
          app.ember.tv.ui.PageBackdropHost(pageBackdrop) {
            if (light && current == NavTabs.HOME) {
                app.ember.tv.ui.LightHome(vm, homeState, tabs, tabClick, onOpenTitle = vm::openDetails, onLaunch = launchApp, onAppOptions = { options = it })
            } else {
            if (current == NavTabs.HOME) {
                val hero = vm.heroItems
                val atTop by remember { derivedStateOf {
                    homeState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hero" }?.let {
                        app.ember.tv.ui.HeroLayout.visibleFraction(it.offset, it.size, homeState.layoutInfo.viewportStartOffset, homeState.layoutInfo.viewportEndOffset) >= 0.5f
                    } == true
                } }
                val heroMeta = if (vm.heroOn) hero.getOrNull(heroIndex) else null
                val shown = if (atTop) heroMeta ?: ambient else ambient
                if (app.ember.tv.ui.LocalFocusBackdrop.current?.tile == null &&
                    app.ember.tv.data.FocusBackground.idleArtwork(atTop && vm.heroOn, vm.look.mediaFocusBackground))
                    app.ember.tv.ui.ModernBackdrop(shown, dim = if (atTop && vm.heroOn) 0f else 0.55f, heightFraction = if(app.ember.tv.ui.Ui.emberX)1f else app.ember.tv.data.HeroBanner.BACKDROP_FRACTION, shade = if (atTop && vm.heroOn) .82f else 1f, onPresented = { presentedHero = it })
            }
            if (current == NavTabs.SEARCH || current == NavTabs.LIVE) app.ember.tv.ui.SearchBackdrop()
        app.ember.tv.ui.LauncherNavbarFrame(vm.look.navbarStyle,vm.look.uiStyle,vm.look.menuPosition,tabs,current,
            vm.weather.weatherText,vm.profiles.profile,tabClick,{vm.profiles.showProfilePicker=true}) { side ->
            Box(Modifier.fillMaxSize().sceneEnter(current).sceneContent(current, app.ember.tv.ui.navigationTabForPage(current, tabs), side, enabled = !light)) {
                app.ember.tv.ui.ArtworkPageContent {
                key(current) { pageState.SaveableStateProvider("page:$current") {
                androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalFocusScene provides current) {
                when (current) {
                    NavTabs.HOME -> {
                        app.ember.tv.ui.ModernHome(
                            vm, homeState, heroIndex, onHeroIndex = { heroKey = vm.heroItems.getOrNull(it)?.mediaKey() }, onAmbient = { pendingAmbient = it },
                            onOpenTitle = vm::openDetails, onLaunch = launchApp, onAppOptions = { options = it },
                            onList = { onTab(it) },
                            onSeeAll = { r -> if (vm.seeAll(r)) onTab(NavTabs.DISCOVER) },
                            interactive = options == null && moving == null,
                            presentedHero = presentedHero,
                        )
                    }
                    NavTabs.WATCHLIST, NavTabs.PROGRESS, NavTabs.RECENT -> app.ember.tv.ui.ListScreen(vm, current, onOpen = vm::openDetails)
                    NavTabs.LIVE -> app.ember.tv.ui.IptvLiveScreen(vm, onOpen = vm::openDetails, onPlayChannel = onPlayChannel)
                    NavTabs.DISCOVER -> DiscoverScreen(vm, onOpen = vm::openDetails)
                    NavTabs.LIBRARY -> LibraryScreen(vm, onOpen = vm::openDetails)
                    NavTabs.APPS -> AppsScreen(vm, onLaunch = launchApp, onAppOptions = { options = it })
                    NavTabs.SEARCH -> if (light) app.ember.tv.ui.LightSearchScreen(vm, launchApp, onAppOptions = { options = it })
                        else SearchScreen(vm, onOpen = vm::openDetails, onLaunch = launchApp, onAppOptions = { options = it },
                            autoFocus = false, navigationUp = searchTabFocus.takeIf { !side })
                    else -> SettingsScreen(vm)
                }
                } } }
                }
            }
        }
            }
          }
        }
      }
        // The note of which card to return to expires if that card is no longer on screen.
        LaunchedEffect(Unit) {
            delay(1500)
            app.ember.tv.ui.FocusMemory.pending = null
        }
    }

    val returnTo = vm.lastPlay?.takeIf { vm.returnReady && vm.playerSettings.playbackConfig.quickReturn }
    if (vm.quickPanel) app.ember.tv.ui.QuickPanel(vm, onClose = { vm.quickPanel = false },
        onReturn = returnTo?.let { last -> last.request.title to { vm.returnHint = false; onPlayWith(app.ember.tv.player.QuickReturn.request(last), last.target) } },
        onSettings = { vm.openSettingsGuarded { vm.closeDetails(); vm.collections.closeFolder(); onTab(NavTabs.SETTINGS) } })
    if (vm.profiles.showProfilePicker) app.ember.tv.ui.ProfilePicker(vm) {
        // "Profile settings" opens the full Settings straight at Profiles.
        vm.profiles.showProfilePicker = false
        vm.openSettingsGuarded {
            vm.look.settingsJump = "PROFILES"
            vm.closeDetails()
            vm.collections.closeFolder()
            onTab(NavTabs.SETTINGS)
        }
    }

    vm.profiles.setupRequested?.let {id->
        vm.profiles.all.firstOrNull {it.id==id}?.let {profile->
            app.ember.tv.ui.KidsSetupDialog(vm,profile,close={vm.profiles.setupRequested=null},onReady=vm.profiles::switchProfile)
        }
    }
    vm.link?.let { LinkDialog(it, onCancel = vm::cancelLink) }
    vm.playing.chooser?.let { req ->
        PlayerChooserDialog(
            players = vm.playing.players,
            onPick = { target ->
                vm.playing.dismissChooser()
                onPlayWith(req, target)
            },
            onCancel = vm.playing::dismissChooser,
        )
    }

    vm.pin.pinPrompt?.let { (reason, _) ->
        app.ember.tv.ui.PinPad(reason, onDone = { vm.pin.enterPin(it) }, onCancel = { vm.pin.pinPrompt = null }, vm = vm)
    }

    vm.resumeAsk?.let { (req, label) ->
        ResumeDialog(
            label = label,
            onResume = {
                vm.resumeAsk = null
                onPlayWith(req.copy(resumeDecided = true), Prefs.PLAYER_BUILTIN)
            },
            onStartOver = {
                vm.resumeAsk = null
                onPlayWith(req.copy(resumeDecided = true, startOver = true), Prefs.PLAYER_BUILTIN)
            },
            onCancel = { vm.resumeAsk = null },
        )
    }

    vm.notice?.let { msg ->
        val undo = vm.localUndo?.takeIf { it.profile == vm.profiles.profileId && it.message == msg }
        LaunchedEffect(msg, undo?.id) {
            delay(if (undo != null) 8000 else 3500)
            vm.clearNotice()
        }
        Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                msg,
                fontSize = 15.sp,
                modifier = Modifier
                    .background(PanelHi, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (undo != null) Chip("Undo") { vm.undoLocalChange() }
            }
        }
    }
    options?.takeIf { vm.canManageHomeItem(it.pkg) }?.let { app -> AppOptionsDialog(app, app.pkg in vm.hidden, vm, onMove = { options = null; moving = app }) { options = null } }
    moving?.takeIf { vm.canManageHomeItem(it.pkg) }?.let { movedApp -> app.ember.tv.ui.AppMoveDialog(movedApp, vm) { moving = null } }
}
