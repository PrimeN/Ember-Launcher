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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Text
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

/** Everything (layout and text) at 95% of the TV's own size, before Settings > Appearance sizes. */
private const val BASE_SCALE = 0.95f

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    private var tab by mutableStateOf(NavTabs.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remote sounds for every screen in this window (the activity's own dispatchKeyEvent is a
        // restricted androidx API); menus in their own windows are handled by TvDialog.
        val keys = window.callback
        window.callback = object : android.view.Window.Callback by keys {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                app.ember.tv.ui.RemoteSounds.onKey(this@MainActivity, event)
                if (backTwice(event)) return true
                return keys.dispatchKeyEvent(event)
            }
        }
        setContent {
            // Interface and text size (Settings > Appearance): scales dp and sp for the whole app.
            val base = androidx.compose.ui.platform.LocalDensity.current
            val ui = app.ember.tv.ui.UiSizes.screen(vm.look.uiSize)
            val text = app.ember.tv.ui.UiSizes.text(vm.look.textSize)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * ui * BASE_SCALE, base.fontScale * text),
            ) {
            EmberTheme(vm.look.accent, vm.look.uiStyle) {
                // A tv-material Surface sets the text colour for everything inside it, dialogs included.
                // Without it, plain Text falls back to black on the dark background.
                Surface(
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {vm.onUiInteraction();app.ember.tv.ui.RuntimeBudgets.input()}
                        false // Observe input without consuming or redirecting the remote event.
                    },
                    colors = SurfaceDefaults.colors(containerColor = Ink, contentColor = MaterialTheme.colorScheme.onSurface),
                ) {
                    // Screen margins (Settings > Appearance) for projectors and TVs that crop the edges.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val m = app.ember.tv.data.ScreenMargins.fraction(vm.look.screenMargin)
                        Box(Modifier.fillMaxSize().padding(horizontal = maxWidth * m, vertical = maxHeight * m)) {
                            Root(vm, tab, onTab = { tab = it }, onLaunch = ::launchApp, onPlay = ::play, onPlayWith = ::playWith, onOpenOffer = ::openOffer, onPlayTrailer = ::playTrailer, onPlayChannel = ::playChannel)
                            if (vm.returnHint) vm.lastPlay?.let { app.ember.tv.ui.ReturnHint(it.request.title, app.ember.tv.player.QuickReturn.prompt(app.ember.tv.player.QuickReturn.key(vm.playerSettings.playbackConfig))) }
                        }
                    }
                }
            }
            }
        }
    }

    private var askedProfile = false

    override fun onResume() {
        super.onResume()
        app.ember.tv.ui.Art.update(this)
        app.ember.tv.ui.RemoteSounds.warm(this)
        if (!askedProfile) {
            askedProfile = true
            vm.profiles.askProfileIfWanted()
        }
        vm.onLauncherResume()
        offerReturn()
    }

    // ---- "Back to …" (Settings > Player > Leaving the player) ----
    private fun rememberPlay(req: PlayRequest, target: String) {
        vm.lastPlay = app.ember.tv.player.LastPlay(req, target, System.currentTimeMillis())
        vm.returnReady = false; vm.returnHint = false
    }
    private fun returnAvailable(): Boolean {
        val last = vm.lastPlay ?: return false
        val saved = runCatching { app.ember.tv.player.PositionStore(this, app.ember.tv.data.LocalActivity.scope(this, last.request.profileId)).get(last.request.resumeKey) }.getOrNull()
        return app.ember.tv.player.QuickReturn.available(last, vm.playerSettings.playbackConfig.quickReturn, System.currentTimeMillis(), saved) &&
            (last.request.profileId == null || last.request.profileId == vm.profiles.profileId)
    }
    private var hintedFor = 0L
    private var returnJob: kotlinx.coroutines.Job? = null
    /** Back on Home after a video: once the player has saved where it stopped, offer to go back. */
    private fun offerReturn() {
        returnJob?.cancel()
        returnJob = lifecycleScope.launch {
            // The player saves its position as it closes, which can be just after Home appears.
            kotlinx.coroutines.delay(1500)
            vm.returnReady = returnAvailable()
            val last = vm.lastPlay ?: return@launch
            if (!vm.returnReady) { if (System.currentTimeMillis() - last.at > app.ember.tv.player.QuickReturn.WINDOW_MS) vm.lastPlay = null; return@launch }
            if (hintedFor == last.at) return@launch
            hintedFor = last.at
            vm.returnHint = true
            kotlinx.coroutines.delay(8000)
            vm.returnHint = false
        }
    }
    private var lastBack = 0L
    /**
     * "Press Back twice": only on the Home page with nothing open over it, so a double press while
     * leaving a title or a menu never starts a video. The first press still does what Back does.
     */
    private fun backTwice(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP || event.isCanceled) return false
        val atRest = tab == NavTabs.HOME && vm.details == null && !vm.quickPanel && !vm.look.settingsPanel && vm.pin.pinPrompt == null
        if (!atRest || !vm.returnReady || app.ember.tv.player.QuickReturn.key(vm.playerSettings.playbackConfig) != "back") { lastBack = 0L; return false }
        val now = android.os.SystemClock.uptimeMillis()
        if (lastBack != 0L && now - lastBack <= app.ember.tv.player.QuickReturn.DOUBLE_PRESS_MS) {
            lastBack = 0L
            returnToLast()
            return true
        }
        lastBack = now
        return false
    }
    /** Plays the last title again from where it stopped, with the same player. */
    private fun returnToLast() {
        val last = vm.lastPlay?.takeIf { returnAvailable() } ?: run { vm.returnReady = false; return }
        vm.returnHint = false
        playWith(app.ember.tv.player.QuickReturn.request(last), last.target)
    }

    /** Pressing the Home button while the launcher is open returns to the Home tab. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab = NavTabs.HOME
        vm.closeDetails() // Home from a title screen goes all the way back to Home
        // ...and from a collection folder or any panel over the page.
        vm.collections.closeFolder()
        vm.look.settingsPanel = false
        vm.quickPanel = false
        vm.homeFeatureAction=null;vm.pendingAppLaunch=null
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)app.ember.tv.ui.RuntimeBudgets.input()
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_MOVE) vm.onUiInteraction()
        return super.dispatchTouchEvent(event)
    }

    /** The remote's search key opens Search and starts voice input, when Search is in the menu. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // The remote's Settings or Menu button opens Ember's settings, unless they are left to the
        // TV (Settings > Appearance > Remote's Settings button), e.g. for a projector's own menu.
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) &&
            vm.look.remoteSettingsKey == "ember" && vm.details == null
        ) {
            vm.openSettingsGuarded {
                if (vm.look.settingsStyle == "floating") vm.look.settingsPanel = true else tab = NavTabs.SETTINGS
            }
            return true
        }
        // The remote's Play key on Home goes back to the video you left (when Back to … is offered).
        if ((keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) && event?.repeatCount == 0 &&
            vm.details == null && vm.returnReady && vm.pin.pinPrompt == null &&
            app.ember.tv.player.QuickReturn.key(vm.playerSettings.playbackConfig) == "play"
        ) {
            returnToLast()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SEARCH && NavTabs.SEARCH in vm.visibleTabs) {
            vm.closeDetails()
            tab = NavTabs.SEARCH
            vm.search.requestVoice()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchApp(app: AppEntry) {
        if (!vm.appAccess.canLaunch(app.pkg)) return
        val owner=vm.profiles.profileId
        val open={if(owner==vm.profiles.profileId && vm.appAccess.canLaunch(app.pkg))launchInstalledApp({startActivity(app.intent)}) {
            Toast.makeText(this,getString(R.string.launcher_app_unavailable,app.label),Toast.LENGTH_SHORT).show();vm.refreshApps(force=true)
        }}
        withAppPin(app.pkg,app.label){open()}
    }

    private fun withAppPin(pkg:String,label:String,action:()->Unit) {
        val owner=vm.profiles.profileId
        val guarded={if(owner==vm.profiles.profileId)action()}
        if(pkg in vm.launcherExtras.options.lockedApps) {
            if(vm.pin.pinSet)vm.pin.withPin("Enter the PIN to open $label"){guarded()}
            else vm.showNotice("Set the administrator PIN to unlock this app.")
        } else guarded()
    }

    /** Plays with the default player chosen in Settings. Torrent sources always go to a torrent app. */
    private fun play(req: PlayRequest) {
        if(!playbackAllowed(req))return
        if (!req.stream.isDirect) {
            openTorrent(req)
            return
        }
        when (val pref = vm.playing.playerPref) {
            Prefs.PLAYER_BUILTIN -> playBuiltIn(req)
            Prefs.PLAYER_ASK -> vm.playing.askPlayer(req)
            else -> playExternal(req, pref)
        }
    }

    /** Plays with a player picked in the chooser: "builtin" or an app's package name. */
    private fun playWith(req: PlayRequest, target: String) {
        if(!playbackAllowed(req))return
        if (target == Prefs.PLAYER_BUILTIN) playBuiltIn(req) else playExternal(req, target)
    }

    private fun playBuiltIn(req: PlayRequest) {
        if(!playbackAllowed(req))return
        // Resume or Start over (Settings > Player > Resume).
        var r = req
        if (!r.resumeDecided) {
            val label = vm.resumeLabel(r)
            if (label != null) {
                when (vm.playerSettings.playbackConfig.resume) {
                    "start" -> r = r.copy(startOver = true)
                    "resume" -> Unit
                    else -> {
                        vm.resumeAsk = r to label
                        return
                    }
                }
            }
        }
        val intent = r.toIntent(this)
        // Picture-in-picture: the player gets its own task so pressing Home keeps it playing in a window.
        if (vm.playerSettings.playbackConfig.pip) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        rememberPlay(r, Prefs.PLAYER_BUILTIN)
        startActivity(intent)
    }

    private fun playExternal(req:PlayRequest,pkg:String) {
        if(!playbackAllowed(req))return
        withAppPin(pkg,vm.apps.firstOrNull {it.pkg==pkg}?.label ?: "player"){playExternalUnlocked(req,pkg)}
    }
    /** The request an external player is showing, for its result (position and duration). */
    private var externalPlay: PlayRequest? = null
    private val externalPlayer = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        externalPlay?.let { recordExternalResult(it, result.data) }
        externalPlay = null
    }

    /**
     * MX-compatible players ("position", "duration", "end_by") and VLC ("extra_position",
     * "extra_duration") report where playback stopped. That feeds In progress and Resume the same
     * way the built-in player does; a player that reports nothing still leaves the title in progress.
     */
    private fun recordExternalResult(req: PlayRequest, data: Intent?) {
        val scope = app.ember.tv.data.LocalActivity.scope(this, req.profileId)
        @Suppress("DEPRECATION") fun number(vararg keys: String) = keys.firstNotNullOfOrNull { k -> (data?.extras?.get(k) as? Number)?.toLong() }
        val position = number("position", "extra_position")?.takeIf { it >= 0 }
        val duration = number("duration", "extra_duration")?.takeIf { it > 0 }
        val positions = app.ember.tv.player.PositionStore(this, scope)
        when {
            data?.getStringExtra("end_by") == "playback_completion" -> {
                LocalLibrary.finished(req.resumeKey, scope); positions.clear(req.resumeKey)
            }
            position != null && duration != null -> {
                LocalLibrary.progress(req.resumeKey, position.toFloat() / duration, duration, scope)
                if (position >= duration * 0.95) positions.clear(req.resumeKey) else positions.put(req.resumeKey, position, duration)
            }
            else -> LocalLibrary.playedElsewhere(req.resumeKey, scope)
        }
    }

    private fun playExternalUnlocked(req: PlayRequest, pkg: String) {
        if(!playbackAllowed(req))return
        // Started for a result (no new task), so the player can report where it stopped.
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(req.stream.url), "video/*")
            .setPackage(pkg)
            .putExtra("return_result", true) // MX-compatible result protocol
            .putExtra("title", req.title) // read by VLC and MX Player
        // The addon's request headers: MX-compatible players read "headers" as name, value, name, value...
        if (req.stream.headers.isNotEmpty()) intent.putExtra("headers", req.stream.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        val codes = PlayRequest.deviceLanguageCodes()
        req.subtitles.firstOrNull { it.lang.lowercase() in codes }?.let { sub ->
            intent.putExtra("subtitles_location", sub.url) // VLC
            intent.putExtra("subs", arrayOf(sub.url.toUri())) // MX Player
            intent.putExtra("subs.name", arrayOf(sub.lang))
        }
        // Resume where the built-in player or an earlier external play stopped (MX-compatible "position", VLC "from_start").
        app.ember.tv.player.PositionStore(this, app.ember.tv.data.LocalActivity.scope(this, req.profileId)).get(req.resumeKey)
            ?.takeIf { (position, duration) -> !req.startOver && position > 0 && position < duration * 0.95 }
            ?.let { (position, _) -> intent.putExtra("position", position.toInt()).putExtra("from_start", false) }
        launchInstalledApp({ externalPlay = req; rememberPlay(req, pkg); externalPlayer.launch(intent) }) {
            externalPlay = null
            Toast.makeText(this, R.string.launcher_player_unavailable, Toast.LENGTH_LONG).show()
            vm.refreshApps(force=true)
        }
    }

    /**
     * Opens a Where to watch offer in its service's TV app: Watchmode's app link, then its web
     * link aimed at the app, then the app's home screen. If the app is missing, opens the store page.
     */
    /** Plays a live channel in the built-in player; channel up and down move through [list]. */
    private fun playChannel(list: List<app.ember.tv.iptv.Channel>, index: Int) {
        if(!vm.liveAllowed || index !in list.indices)return
        val profile = vm.profiles.profileId
        val session = app.ember.tv.iptv.LiveSession(list, list[index].key,
            available = { if (vm.profiles.profileId == profile && vm.liveAllowed) vm.iptv.visibleChannels else emptyList() },
            onSelected = { vm.iptv.played(it) }, onGuide = { vm.iptv.requestGuide(it) },
            programmes = { channel, at -> vm.iptv.nowNext(channel, at) },
            sourceName = { channel -> vm.iptv.sources.firstOrNull { it.id == channel.sourceId }?.name ?: "IPTV" },
            history = { vm.iptv.recent })
        val token = app.ember.tv.iptv.LiveSessions.put(session)
        startActivity(android.content.Intent(this, app.ember.tv.player.PlayerActivity::class.java)
            .putExtra(app.ember.tv.player.PlayerActivity.EXTRA_LIVE, true)
            .putExtra(app.ember.tv.player.PlayerActivity.EXTRA_LIVE_SESSION, token))
    }

    /**
     * Plays a trailer. With the experimental in-app option on, Ember finds the YouTube streams and
     * plays them in its own player; if that fails (YouTube changes often), the YouTube app opens instead.
     */
    private fun playTrailer(videoId: String) {
        val content=vm.details?.meta ?: return
        if(!vm.contentAllowed(content))return
        val owner=vm.profiles.profileId
        if (!vm.playerSettings.playbackConfig.trailersInApp) {
            openTrailerInYouTube(videoId)
            return
        }
        val title = vm.details?.meta?.name?.let { "$it · Trailer" } ?: "Trailer"
        Toast.makeText(this, "Loading trailer...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching { app.ember.tv.trailer.YouTubeTrailers.streams(videoId) }
                .onSuccess { s ->
                    if(vm.profiles.profileId!=owner || !vm.contentAllowed(content))return@onSuccess
                    startActivity(app.ember.tv.player.PlayerActivity.trailerIntent(this@MainActivity, s.videoUrl, s.audioUrl, title)
                        .putExtra(PlayRequest.EXTRA_PROFILE,owner).putExtra(PlayRequest.EXTRA_CONTENT_TYPE,content.type)
                        .putExtra(PlayRequest.EXTRA_GENRES,content.genres?.toTypedArray()).putExtra(PlayRequest.EXTRA_AGE_RATING,content.ageRating)
                        .putExtra(PlayRequest.EXTRA_ADULT,content.adult==true))
                }
                .onFailure {
                    if(vm.profiles.profileId!=owner || !vm.contentAllowed(content))return@onFailure
                    Toast.makeText(this@MainActivity, "Could not play the trailer here; opening YouTube.", Toast.LENGTH_SHORT).show()
                    openTrailerInYouTube(videoId)
                }
        }
    }

    /** Opens a YouTube trailer in the TV's YouTube app (or SmartTube, or a browser). */
    private fun openTrailerInYouTube(videoId: String) {
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, "vnd.youtube:$videoId".toUri()),
            Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$videoId".toUri()),
        )
        for (intent in tries) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next way
            }
        }
        Toast.makeText(this, "Install YouTube to watch trailers.", Toast.LENGTH_LONG).show()
    }

    private fun openOffer(o:Offer) {
        if(vm.details?.meta?.let(vm::contentAllowed)!=true)return
        val installed=vm.watch.offerPackages(o).first
        if(installed!=null) {
            if(vm.appAccess.canLaunch(installed))withAppPin(installed,o.name){openOfferUnlocked(o)}
        } else openOfferUnlocked(o)
    }
    private fun openOfferUnlocked(o: Offer) {
        if(vm.details?.meta?.let(vm::contentAllowed)!=true)return
        val (installed, store) = vm.watch.offerPackages(o)
        val tries = ArrayList<Intent>()
        if (installed != null) {
            o.androidUrl?.let { tries += Intent(Intent.ACTION_VIEW, it.toUri()).setPackage(installed) }
            o.webUrl?.let { tries += Intent(Intent.ACTION_VIEW, it.toUri()).setPackage(installed) }
            packageManager.getLeanbackLaunchIntentForPackage(installed)?.let { tries += it }
            packageManager.getLaunchIntentForPackage(installed)?.let { tries += it }
        } else if (store != null) {
            tries += Intent(Intent.ACTION_VIEW, "market://details?id=$store".toUri())
        }
        if(installed==null)o.webUrl?.let { tries += Intent(Intent.ACTION_VIEW, it.toUri()) }
        for (intent in tries) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next way
            }
        }
        Toast.makeText(this, "Could not open ${o.name}. Install its app from the Play Store.", Toast.LENGTH_LONG).show()
    }

    private fun openTorrent(req: PlayRequest) {
        if(!playbackAllowed(req))return
        val hash = req.stream.infoHash ?: return
        val intent = Intent(Intent.ACTION_VIEW, "magnet:?xt=urn:btih:$hash&dn=${Uri.encode(req.title)}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this TV opens torrent links. Pick a Direct or Cached source instead.", Toast.LENGTH_LONG).show()
        }
    }
    private fun playbackAllowed(req:PlayRequest):Boolean =
        app.ember.tv.data.KidsContentPolicy.playbackAllowed(vm.profile,req.profileId,req.contentMeta)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Root(
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
    val glassBitmap = app.ember.tv.ui.rememberGlassBitmap(backdropArt, renderOptions, navigation)
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
        }
    }
    val interactive = windowFocused && lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
        !vm.quickPanel && !vm.look.settingsPanel && !vm.profiles.showProfilePicker && !vm.profiles.showProfileMenu && vm.profiles.setupRequested==null &&
        vm.link == null && vm.pin.pinPrompt == null && vm.resumeAsk == null && vm.playing.chooser == null
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
        app.ember.tv.ui.FocusBackdropHost(vm,
            scope = listOf(vm.profiles.profile, tab, vm.collections.open?.first?.id, vm.collections.open?.second?.id, vm.serverBrowser.library?.ref),
            enabled = vm.details == null) {
            RootContent(vm, tab, onTab, onLaunch, onPlay, onPlayWith, onOpenOffer, onPlayTrailer, onPlayChannel)
        }
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
    val settingsVisible=tab==NavTabs.SETTINGS || vm.look.settingsPanel || vm.quickPanel || vm.look.settingsDestination!=null || vm.homeFeatureAction?.startsWith("edit:")==true
    LaunchedEffect(vm.profiles.profile,settingsVisible,settingsLifecycle,vm.settingsAllowed) {
        if(!settingsVisible || !settingsLifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))vm.revokeSettingsAccess()
        if(settingsVisible && !vm.settingsAllowed) {
            vm.look.settingsPanel=false;vm.quickPanel=false;vm.look.settingsDestination=null
            if(vm.homeFeatureAction?.startsWith("edit:")==true)vm.homeFeatureAction=null
            if(tab==NavTabs.SETTINGS)onTab(NavTabs.HOME)
        }
    }
    LaunchedEffect(vm.look.settingsDestination) {
        vm.look.settingsDestination?.let { page ->
            vm.look.settingsDestination = null
            vm.look.settingsJump = page
            vm.look.settingsPanel = false
            vm.quickPanel = false
            vm.closeDetails()
            vm.collections.closeFolder()
            onTab(NavTabs.SETTINGS)
        }
    }
    val discoveryInteractive = app.ember.tv.ui.LocalSceneInteractive.current
    LaunchedEffect(tab, vm.profiles.profileId, vm.explore.saved, vm.sections.layout, vm.watch.watchRegion, discoveryInteractive) {
        if (tab != NavTabs.DISCOVER || !discoveryInteractive) vm.explore.pause()
        if (tab == NavTabs.HOME && discoveryInteractive) vm.explore.refreshHome { vm.sections.sectionOn(it) }
    }
    val navigationContext = androidx.compose.ui.platform.LocalContext.current
    var options by remember { mutableStateOf<AppEntry?>(null) }
    var moving by remember { mutableStateOf<AppEntry?>(null) }
    LaunchedEffect(vm.apps, vm.profiles.profile, options?.pkg, moving?.pkg) {
        if (options?.let { !vm.appAccess.canLaunch(it.pkg) } == true) options = null
        if (moving?.let { !vm.appAccess.canLaunch(it.pkg) } == true) moving = null
    }
    val launchApp:(AppEntry)->Unit={app->if(options==null && moving==null)onLaunch(app)}
    app.ember.tv.ui.HomeFeatureDialogs(vm,onLaunch)
    LaunchedEffect(vm.pendingAppLaunch) {vm.pendingAppLaunch?.let {vm.pendingAppLaunch=null;vm.quickPanel=false;vm.look.settingsPanel=false;onLaunch(it)}}
    var pageProfile by remember { mutableStateOf(vm.profiles.profileId) }
    LaunchedEffect(vm.profiles.profileId) {
        if (pageProfile != vm.profiles.profileId) {
            pageProfile = vm.profiles.profileId
            options = null
            moving = null
            vm.quickPanel = false
            vm.look.settingsPanel = false
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
        val detailsListState = remember(d.meta.mediaKey()) { androidx.compose.foundation.lazy.LazyListState() }
        val detailsDim by remember(detailsListState) { derivedStateOf {
            if (detailsListState.firstVisibleItemIndex > 0) 1f
            else (detailsListState.firstVisibleItemScrollOffset / 240f).coerceIn(0f, 1f)
        } }
        val detailsBackground=if(app.ember.tv.ui.Ui.emberX)app.ember.tv.ui.EmberXColors.Background else if(app.ember.tv.ui.LocalAccentIndex.current==app.ember.tv.data.ColorThemes.MODERN_EMBER)
            if(app.ember.tv.ui.Ui.isOled)androidx.compose.ui.graphics.Color.Black else app.ember.tv.ui.ModernEmberColors.Background
            else androidx.compose.ui.graphics.Color(0xFF111215)
        androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalNight provides true) {
        MaterialTheme(colorScheme=app.ember.tv.ui.emberColorScheme(vm.look.accent,false,app.ember.tv.ui.Ui.isOled).copy(background=detailsBackground,
            surface=detailsBackground,onSurface=androidx.compose.ui.graphics.Color.White,onBackground=androidx.compose.ui.graphics.Color.White)) {
        Box(Modifier.fillMaxSize().background(detailsBackground)) {
            app.ember.tv.ui.ModernBackdrop(d.meta,dim=detailsDim,backgroundColor=detailsBackground,heightFraction=if(app.ember.tv.ui.Ui.emberX)1f else .78f)
            Column(Modifier.fillMaxSize().sceneEnter("details:${d.meta.mediaKey()}")) {
                Box(Modifier.fillMaxWidth().padding(start=TvTokens.Space.Page.dp,end=TvTokens.Space.Page.dp,top=12.dp,bottom=4.dp)) {
                    app.ember.tv.ui.DetailsBackButton(Modifier.focusRequester(backFocus)
                        .focusProperties {down=navigation.content("details")},vm::closeDetails)
                    if(app.ember.tv.ui.Ui.emberX)app.ember.tv.ui.ProfileMenuButton(vm.profiles.profile,
                        Modifier.align(androidx.compose.ui.Alignment.CenterEnd),size=32.dp,onFallback={vm.profiles.showProfilePicker=true})
                }
                Box(Modifier.fillMaxWidth().weight(1f).detailsFocusGroup(detailsFocus,
                    restore={runCatching {navigation.preferred("details")?.requestFocus()==true}.getOrDefault(false)},
                    back={runCatching {backFocus.requestFocus()}.getOrDefault(false)})) {
                    androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalFocusScene provides "details") {
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
                        playbackLabel = vm.primaryPlaybackLabel,
                        watched = vm.detailsWatched,
                        onMarkWatched = vm::markWatched,
                        hasAddons = vm.addons.isNotEmpty(),
                        onRetrySources = vm.title::retrySources,
                        best = remember(d.streams,d.serverStreams,vm.playerSettings.playRules) {vm.playerSettings.bestSource(d)},
                        onPlayWith = { s ->
                            val req = vm.playRequest(s) ?: return@DetailsScreen
                            // Torrent sources can only go to a torrent app, so there is nothing to choose.
                            if (s.isDirect) vm.playing.askPlayer(req) else onPlay(req)
                        },
                        offers = d.offers.filter { vm.watch.offerEnabled(it) },
                        offersCredit = vm.watch.offersCredit,
                        onOpenOffer = onOpenOffer,
                        offerInstalled = { vm.watch.offerPackages(it).first != null },
                    )
                    }
                }
            }
        }
        } }
    } else if (vm.collections.open != null) {
        // A Collection folder; its titles open the title screen above, and Back returns here.
        androidx.compose.runtime.CompositionLocalProvider(app.ember.tv.ui.LocalMediaFocusKind provides app.ember.tv.ui.FocusTileKind.COLLECTION) {
            app.ember.tv.ui.FolderScreen(vm, onOpenTitle = vm::openDetails)
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
                id == NavTabs.SETTINGS -> vm.openSettingsGuarded {
                    if (!app.ember.tv.ui.Ui.emberX && vm.look.settingsStyle == "floating") vm.look.settingsPanel = true else onTab(id)
                }
                else -> onTab(id)
            }
        }
        val side = !app.ember.tv.ui.Ui.emberX && !light && vm.look.menuPosition == "side" && current != NavTabs.SETTINGS
        val navigation = app.ember.tv.ui.LocalNavigationFocus.current!!
        val searchTabFocus = navigation.tab(NavTabs.SEARCH)
        val pageBackdrop = remember(current, vm.profiles.profileId) { app.ember.tv.ui.PageBackdropState() }
        Box(Modifier.fillMaxSize().initialNavigationFocus(navigation,
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
        Column(Modifier.fillMaxSize().padding(start = if (side) 84.dp else 0.dp)) {
            // Light has no menu: Settings and Search are buttons on its Home, and Back returns there.
            if (!light && !side && current != NavTabs.SETTINGS) {
                if(app.ember.tv.ui.Ui.emberX)app.ember.tv.ui.EmberXTopBar(tabs,current,tabClick,vm.profiles.profile){vm.profiles.showProfilePicker=true}
                else app.ember.tv.ui.ModernTopBar(tabs, current, tabClick, vm.weather.weatherText,
                    vm.profiles.profile, searchFocus = searchTabFocus) { vm.profiles.showProfilePicker = true }
            }
            Box(Modifier.fillMaxWidth().weight(1f).sceneEnter(current).sceneContent(current, app.ember.tv.ui.navigationTabForPage(current, tabs), side, enabled = !light)) {
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
            if (side) app.ember.tv.ui.ModernSideRail(tabs, current, tabClick, vm.weather.weatherText, profile = vm.profiles.profile) { vm.profiles.showProfilePicker = true }
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

    if (vm.look.settingsPanel) {
        SettingsScreen(
            vm,
            onClose = { vm.look.settingsPanel = false },
            onOpenFull = { page ->
                vm.openSettingsPage(page)
            },
        )
    }
    val returnTo = vm.lastPlay?.takeIf { vm.returnReady && vm.playerSettings.playbackConfig.quickReturn }
    if (vm.quickPanel) app.ember.tv.ui.QuickPanel(vm, onClose = { vm.quickPanel = false },
        onReturn = returnTo?.let { last -> last.request.title to { vm.returnHint = false; onPlayWith(app.ember.tv.player.QuickReturn.request(last), last.target) } },
        onSettings = { vm.openSettingsGuarded {
        if (vm.look.settingsStyle == "floating") vm.look.settingsPanel = true
        else { vm.closeDetails(); vm.collections.closeFolder(); onTab(NavTabs.SETTINGS) }
    } })
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
    options?.takeIf { vm.appAccess.canLaunch(it.pkg) }?.let { app -> AppOptionsDialog(app, app.pkg in vm.hidden, vm, onMove = { options = null; moving = app }) { options = null } }
    moving?.takeIf { vm.appAccess.canLaunch(it.pkg) }?.let { movedApp -> app.ember.tv.ui.AppMoveDialog(movedApp, vm) { moving = null } }
}

/** Resume or Start over, for a title with a resume point. */
@Composable
private fun ResumeDialog(label: String, onResume: () -> Unit, onStartOver: () -> Unit, onCancel: () -> Unit) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val heading = stringResource(R.string.ui_continue_watching)
    val startOver = stringResource(R.string.ui_start_over)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Screen width in this app's scaled dp (Interface size changes density), not the system's dp.
    val screenPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width
    val maxWidth = (with(density) { screenPx.toDp() } - 48.dp).coerceAtLeast(1.dp)
    val width = with(density) { maxOf(
        measurer.measure(heading,androidx.compose.ui.text.TextStyle(fontSize=18.sp),softWrap=false).size.width.toDp() + 40.dp,
        measurer.measure("▶ $label",androidx.compose.ui.text.TextStyle(fontSize=app.ember.tv.ui.TvTokens.Type.Label.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold),softWrap=false).size.width.toDp() + 98.dp,
        measurer.measure(startOver,androidx.compose.ui.text.TextStyle(fontSize=app.ember.tv.ui.TvTokens.Type.Label.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium),softWrap=false).size.width.toDp() + 76.dp,
    ) }.coerceIn(minOf(220.dp,maxWidth),maxWidth)
    Dialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        app.ember.tv.ui.DialogMotion()
        Column(
            modifier = Modifier
                .guardOpeningPress().width(width)
                .background(Panel, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(heading, fontSize = 18.sp, maxLines=1, softWrap=false, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Chip("▶ $label", selected = true, modifier = Modifier.fillMaxWidth().focusRequester(first), maxLines=1, onClick = onResume)
            Chip(stringResource(R.string.ui_start_over), modifier = Modifier.fillMaxWidth(), maxLines=1, onClick = onStartOver)
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}

@Composable
private fun PlayerChooserDialog(players: List<PlayerApp>, onPick: (String) -> Unit, onCancel: () -> Unit) {
    val builtIn=stringResource(R.string.ui_built_in_player)
    CompactActionMenu(stringResource(R.string.ui_play_with),listOf(builtIn)+players.map {it.label},onCancel) {first->
        MenuActionRow(builtIn,Modifier.focusRequester(first),Icons.Outlined.PlayCircleOutline) {onPick(Prefs.PLAYER_BUILTIN)}
        players.forEach {p->MenuActionRow(p.label,icon=Icons.Outlined.SmartDisplay) {onPick(p.pkg)}}
        if(players.isEmpty())Text(stringResource(R.string.ui_no_other_video_players_are_installed),color=Mute,fontSize=TvTokens.Type.Compact.sp)
    }
}

@Composable
private fun LinkDialog(link: LinkUi, onCancel: () -> Unit) {
    MediaDialog("Connect ${link.trackerName}",onCancel) {
        Text(stringResource(R.string.ui_on_your_phone_or_computer_open),color=Mute,fontSize=TvTokens.Type.Body.sp)
        Text(link.url,fontSize=18.sp)
        Text(stringResource(R.string.ui_and_enter_this_code),color=Mute,fontSize=TvTokens.Type.Body.sp)
        Text(link.code,fontSize=44.sp,letterSpacing=6.sp)
        Text(stringResource(R.string.ui_waiting_for_approval_this_closes_by),color=Mute,fontSize=TvTokens.Type.Caption.sp)
        Chip(stringResource(R.string.ui_cancel),onClick=onCancel)
    }
}

@Composable
private fun AppOptionsDialog(app: AppEntry, hidden: Boolean, vm: HomeViewModel, onMove: () -> Unit, onClose: () -> Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val labels=listOf("Move with D-pad",stringResource(R.string.ui_move_earlier),stringResource(R.string.ui_move_later),
        if(hidden)"Show on Home" else "Remove from Home",if(app.pkg in vm.appsPageOff)"Unhide" else "Hide",
        "App folder", "Choose artwork", if(app.pkg in vm.personalHomes.current.artwork)"Restore original artwork" else "Original artwork",
        if(app.pkg in vm.launcherExtras.options.lockedApps)"Remove PIN lock" else "Require PIN", "Uninstall")
    CompactActionMenu(app.label,labels,onClose) {first->
            MenuActionRow(labels[0],modifier=Modifier.focusRequester(first), icon=Icons.Outlined.OpenWith, onClick = onMove)
            MenuActionRow(labels[1],icon=Icons.AutoMirrored.Outlined.KeyboardArrowLeft) { vm.moveApp(app.pkg, -1) }
            MenuActionRow(labels[2],icon=Icons.AutoMirrored.Outlined.KeyboardArrowRight) { vm.moveApp(app.pkg, 1) }
            MenuActionRow(labels[3],icon=if(hidden)Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,divider=true) {
                vm.toggleHidden(app.pkg)
                onClose()
            }
            MenuActionRow(labels[4],icon=Icons.Outlined.Apps) {
                vm.toggleAppsPage(app.pkg)
                onClose()
            }
            MenuActionRow(labels[5],icon=Icons.Outlined.Folder) {onClose();vm.openSettingsGuarded {vm.homeFeatureAction="edit:assign:${app.pkg}"}}
            MenuActionRow(labels[6],icon=Icons.Outlined.Image) {onClose();vm.openSettingsGuarded {vm.homeFeatureAction="edit:art:${app.pkg}"}}
            if(app.pkg in vm.personalHomes.current.artwork)MenuActionRow(labels[7],icon=Icons.Outlined.Restore) {onClose();vm.openSettingsGuarded {vm.personalHomes.update {it.copy(artwork=it.artwork-app.pkg)}}}
            MenuActionRow(labels[8],icon=Icons.Outlined.Lock) {
                onClose();val owner=vm.profiles.profileId
                if(!vm.pin.pinSet){vm.showNotice("Set the administrator PIN first.");vm.openSettingsPage("PROFILES")}
                else vm.pin.withPin("Enter the administrator PIN") {if(owner==vm.profiles.profileId)vm.launcherExtras.update {
                    it.copy(lockedApps=if(app.pkg in it.lockedApps)it.lockedApps-app.pkg else it.lockedApps+app.pkg)}}
            }
            MenuActionRow(labels[9],icon=Icons.Outlined.DeleteOutline,destructive=true,divider=true) {
                onClose()
                val uninstall:()->Unit={if(vm.appAccess.canLaunch(app.pkg))AppManagement.uninstall(context,app.pkg)}
                vm.openSettingsGuarded(uninstall)
            }
    }
}
