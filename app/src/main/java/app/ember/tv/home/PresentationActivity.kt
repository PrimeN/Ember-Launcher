package app.ember.tv.home

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import app.ember.tv.data.Prefs
import app.ember.tv.ui.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Local image/video loops; no server, WebView, tracking or privileged background playback. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PresentationActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs=Prefs(this);val owner=prefs.currentProfile
        if(prefs.profiles.firstOrNull {it.id==owner}?.kids!=false){finish();return}
        val config=LauncherExtrasState(prefs).options
        if(config.keepAwake)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent {EmberTheme(prefs.accent,prefs.uiStyle){CompositionLocalProvider(LocalNight provides true){Presentation(config)}}}
        val store=getSharedPreferences("ember",MODE_PRIVATE)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key=="currentProfile" && Prefs(this).currentProfile!=owner)finish()}
        store.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(object:androidx.lifecycle.DefaultLifecycleObserver{override fun onDestroy(owner:androidx.lifecycle.LifecycleOwner){store.unregisterOnSharedPreferenceChangeListener(listener)}})
    }
    @Composable private fun Presentation(config:LauncherExtras) {
        val root=remember {FocusRequester()};val first=remember {FocusRequester()}
        var cycle by remember {mutableIntStateOf(0)}
        var index by remember {mutableIntStateOf(0)}
        var paused by remember {mutableStateOf(false)}
        var controls by remember {mutableStateOf(false)}
        var failed by remember {mutableStateOf(false)}
        var failures by remember {mutableIntStateOf(0)}
        var interaction by remember {mutableIntStateOf(0)}
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val player=remember {ExoPlayer.Builder(this).setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(5000,15000,250,1000).setTargetBufferBytes(8*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build()).setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build()}
        val items=config.slides
        fun advance(delta:Int=1){if(items.isNotEmpty()){index=Math.floorMod(index+delta,items.size);failed=false;interaction++;cycle++}}
        DisposableEffect(player) {
            val listener=object:Player.Listener {
                override fun onPlaybackStateChanged(state:Int){if(state==Player.STATE_ENDED){failures=0;advance()}}
                override fun onPlayerError(error:PlaybackException){failed=true;failures++}
            }
            player.addListener(listener);onDispose{player.removeListener(listener);player.release()}
        }
        BackHandler {finish()}
        val item=items.getOrNull(index)
        val uri=remember(item?.uri){item?.let {PresentationMedia.safeUri(this,it.uri)}}
        LaunchedEffect(item?.id,uri,cycle) {
            player.stop();player.clearMediaItems();failed=false
            if(item!=null && uri==null){failed=true;failures++}
            else if(item?.video==true){player.setMediaItem(MediaItem.fromUri(requireNotNull(uri)));player.prepare()}
        }
        LaunchedEffect(paused,lifecycle,item?.id,failed){player.playWhenReady=!paused && !failed && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && item?.video==true}
        LaunchedEffect(item?.id,paused,lifecycle,failed,failures,cycle) {
            if(item!=null && !paused && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && failures<items.size && (!item.video || failed)) {
                delay(if(failed)2000 else config.slideSeconds*1000L);advance()
            }
        }
        LaunchedEffect(controls){if(controls)runCatching {first.requestFocus()} else runCatching {root.requestFocus()}}
        LaunchedEffect(controls,interaction){if(controls){delay(6000);controls=false}}
        Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent {e->
            if(e.type!=KeyEventType.KeyDown)false else when(e.key){
                Key.DirectionLeft->if(!controls){if(e.nativeKeyEvent.repeatCount==0)advance(-1);true}else false
                Key.DirectionRight->if(!controls){if(e.nativeKeyEvent.repeatCount==0)advance();true}else false
                Key.Spacebar,Key.MediaPlayPause->{paused=!paused;interaction++;true}
                Key.MediaPlay->{paused=false;true}
                Key.MediaPause->{paused=true;true}
                Key.MediaNext,Key.MediaFastForward->{if(e.nativeKeyEvent.repeatCount==0)advance();true}
                Key.MediaPrevious,Key.MediaRewind->{if(e.nativeKeyEvent.repeatCount==0)advance(-1);true}
                Key.DirectionUp,Key.DirectionDown->if(!controls){controls=true;interaction++;true}else false
                Key.Enter,Key.DirectionCenter->if(!controls){controls=true;interaction++;true}else false
                else->false
            }
        }.focusRequester(root).focusable()) {
            if(item==null)Text(stringResource(R.string.tx_choose_photos_or_videos_in_settings),Modifier.align(Alignment.Center),color=Color.White,fontSize=20.sp)
            else if(failed)Text(if(failures>=items.size)"The selected files are unavailable. Choose them again in Home tools." else "This file is unavailable.",Modifier.align(Alignment.Center).padding(30.dp),color=Color.White,fontSize=18.sp)
            else if(item.video)AndroidView(factory={PlayerView(it).apply {this.player=player;useController=false}},modifier=Modifier.fillMaxSize(),onRelease={it.player=null})
            else AsyncImage(model=uri,contentDescription=item.name,modifier=Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Fit,
                onSuccess={failures=0},onError={failed=true;failures++})
            if(controls || item==null || failures>=items.size)Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha=.65f)).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item?.let {Text(it.name,color=Color.White,fontSize=17.sp)}
                ButtonRow {
                    Chip("Previous",modifier=Modifier.focusRequester(first)){advance(-1)};Chip(if(paused)"Resume" else "Pause"){paused=!paused;interaction++};Chip("Next"){advance()};Chip("Back"){finish()}
                }
            }
        }
    }
    companion object {fun start(context:android.content.Context){context.startActivity(Intent(context,PresentationActivity::class.java))}}
}
