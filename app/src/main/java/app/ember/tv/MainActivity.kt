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

/** Everything (layout and text) at 95% of the TV's own size, before Settings > Appearance sizes. */
private const val BASE_SCALE = 0.95f

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()
    private var tab by mutableStateOf(NavTabs.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.ember.tv.ui.StartupHeroFocus.begin()
        // Remote sounds for every screen in this window (the activity's own dispatchKeyEvent is a
        // restricted androidx API); menus in their own windows are handled by TvDialog.
        val keys = window.callback
        window.callback = object : android.view.Window.Callback by keys {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                app.ember.tv.ui.RemoteSounds.onKey(this@MainActivity, event)
                // A key pressed while Home loads keeps your own focus (#100).
                if (event.action == KeyEvent.ACTION_DOWN) app.ember.tv.ui.StartupHeroFocus.keyPressed()
                if(event.action==KeyEvent.ACTION_DOWN)when(event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->vm.startupPresentation.revealForInput()
                }
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
                    modifier = Modifier.fillMaxSize().startupInput(vm.startupPresentation).onPreviewKeyEvent { event ->
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

    // #241: what the phone remote can do on this screen.
    private val remoteScreen = object : app.ember.tv.remote.RemoteBridge.Screen {
        override fun play(request: PlayRequest) { this@MainActivity.play(request) }
        override fun playWith(request: PlayRequest, target: String) { this@MainActivity.playWith(request, target) }
        override fun search(query: String) { vm.closeDetails(); tab = NavTabs.SEARCH; vm.search.setQuery(query, immediate = true) }
        override fun home() { vm.closeDetails(); tab = NavTabs.HOME }
    }

    override fun onDestroy() {
        if (app.ember.tv.remote.RemoteBridge.screen === remoteScreen) app.ember.tv.remote.RemoteBridge.screen = null
        if (isFinishing) app.ember.tv.remote.PhoneRemote.release(vm)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        app.ember.tv.remote.RemoteBridge.screen = remoteScreen
        app.ember.tv.remote.PhoneRemote.sync(this, vm, app.ember.tv.ui.phonePageAccent(vm.look.accent))
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
        val atRest = tab == NavTabs.HOME && vm.details == null && !vm.quickPanel && vm.pin.pinPrompt == null
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
            vm.openSettingsGuarded { tab = NavTabs.SETTINGS }
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
        // Addon links can be app links (netflix://...) that the app opens without a known package (#73).
        if(installed==null)o.androidUrl?.takeIf { o.type == "link" }?.let { tries += Intent(Intent.ACTION_VIEW, it.toUri()) }
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
