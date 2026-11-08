package app.ember.tv.player

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.R
import app.ember.tv.ui.*
import java.util.Date

private val Ivory=Color(0xFFF8F3ED)
private val Coal=Color(0xFF101012)
private val ControlIds=listOf("back","rewind","play","forward","next","audio","subtitles","episodes","queue","settings","info")

/** Saved Basic/Modern choices share transport behavior; only their presentation differs. */
@Composable
internal fun PlaybackOverlay(s:ControlsState,a:ControlsActions) {
    val glass=s.design!=PlayerInterfaces.BASIC
    val render=LocalRenderOptions.current
    val motion=render.animationMillis(TvTokens.Motion.Panel,LocalReduceMotion.current)
    AnimatedVisibility(s.visible,enter=fadeIn(tween(motion)),exit=fadeOut(tween(motion))) {
        val requesters=remember {ControlIds.associateWith {FocusRequester()}}
        val modal=s.sheet!=null || s.menu!=null
        LaunchedEffect(s.shownAt,s.sheet,s.menu) {
            if(!modal) {
                val target=s.returnControl ?: "play"
                withFrameNanos {}
                val restored=runCatching {requesters[target]?.requestFocus()}.getOrNull()==true
                val fallback=if(restored)false else runCatching {requesters.getValue("play").requestFocus()}.getOrDefault(false)
                if(restored || fallback)s.returnControl=null
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().testTag(if(glass)"player_glass" else "player_cinema")
            .onPreviewKeyEvent {a.touched();false}) {
            val density=LocalDensity.current
            val narrow=maxWidth<600.dp || density.fontScale>1.3f
            val inset=if(maxWidth<680.dp)20.dp else 32.dp
            val button=if(maxHeight<420.dp)36.dp else 40.dp
            var dockHeight by remember {mutableStateOf(140.dp)}
            fun rememberOrigin(id:String) {s.returnControl=id}
            @Composable fun control(id:String,icon:ImageVector,label:String,enabled:Boolean=true,large:Boolean=false,action:()->Unit) {
                PlaybackControl(icon,label,glass,modifier=Modifier.focusRequester(requesters.getValue(id))
                    .focusProperties {canFocus=!modal}.testTag("player_$id"),enabled=enabled,
                    size=if(large)button+4.dp else button,onClick=action)
            }
            if(!glass)Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.34f)
                .background(Brush.verticalGradient(listOf(Color.Transparent,Coal.copy(alpha=.84f)))))
            Row(Modifier.fillMaxWidth().padding(horizontal=inset,vertical=20.dp),verticalAlignment=Alignment.CenterVertically) {
                control("back",Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.player_back),action=a.close)
                Spacer(Modifier.weight(1f))
                EmberLogo(22.dp,Ivory)
            }
            val dock=Modifier.align(Alignment.BottomCenter).padding(start=inset,end=inset,bottom=20.dp)
                .fillMaxWidth().onSizeChanged {dockHeight=with(density){it.height.toDp()};s.overlayHeightDp=dockHeight.value}
            val material=if(glass)Modifier.clip(RoundedCornerShape(TvTokens.Shape.NativePill.dp))
                .background(Brush.verticalGradient(listOf(Color(0xA3292B2E),Color(0xBF15181C))))
                .border(.7.dp,Color.White.copy(alpha=.14f),RoundedCornerShape(TvTokens.Shape.NativePill.dp)) else Modifier
            Column(dock.then(material).padding(horizontal=if(glass)18.dp else 0.dp,vertical=if(glass)12.dp else 0.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(s.details?.name ?: s.title,color=Ivory,fontSize=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        val episode=listOfNotNull(s.seasonEpisode,s.details?.episodeTitle).filter(String::isNotBlank).joinToString(" · ")
                        if(episode.isNotEmpty())Text(episode,color=Soft,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    if(s.buffering)Text(stringResource(R.string.pl_buffering),color=Ivory,fontSize=12.sp)
                    else if(!narrow)PlaybackEndTime(s)
                }
                val positionLabel=stringResource(R.string.player_position)
                SeekBar(s,a,cinematic=true,modifier=Modifier.testTag("player_timeline").focusProperties {canFocus=!modal && s.duration>0}
                    .semantics {
                        contentDescription=positionLabel
                        if(s.duration>0) {
                            progressBarRangeInfo=ProgressBarRangeInfo((s.position.toFloat()/s.duration).coerceIn(0f,1f),0f..1f)
                            setProgress {fraction->
                                if(!fraction.isFinite())false else {a.seekBy((fraction.coerceIn(0f,1f)*s.duration).toLong()-s.position);true}
                            }
                        }
                    })
                Row(Modifier.fillMaxWidth()) {
                    Text(time(if(s.seekPreview>=0f)(s.seekPreview*s.duration).toLong() else s.position),color=Soft,fontSize=12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if(s.duration>0)time(s.duration) else "—:—",color=Soft,fontSize=12.sp)
                }
                @Composable fun transport() {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        control("rewind",Icons.Outlined.Replay10,stringResource(R.string.player_back_ten),enabled=s.duration>0){a.seekBy(-10_000)}
                        control("play",if(s.playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if(s.playing)R.string.player_pause else R.string.player_play),large=true,action=a.playPause)
                        control("forward",Icons.Outlined.Forward30,stringResource(R.string.player_forward_thirty),enabled=s.duration>0){a.seekBy(30_000)}
                        if(s.nextEpisodes.isNotEmpty() || s.fileQueueNext)control("next",Icons.Outlined.SkipNext,
                            stringResource(if(s.fileQueueLabel!=null)R.string.player_next_file else R.string.ui_next_episode),action=a.next)
                    }
                }
                @Composable fun utilities() {
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(s.audioTracks.isNotEmpty())control("audio",Icons.AutoMirrored.Outlined.VolumeUp,stringResource(R.string.player_audio)) {
                            rememberOrigin("audio");s.sheet=SOUND_SHEET
                        }
                        if(s.subtitleOptions.isNotEmpty())control("subtitles",Icons.Outlined.Subtitles,stringResource(R.string.settings_subtitles)) {
                            rememberOrigin("subtitles");s.sheet=SUBTITLE_SHEET
                        }
                        if(s.fileQueueLabel!=null)control("queue",Icons.AutoMirrored.Outlined.FormatListBulleted,stringResource(R.string.player_queue),action=a.queue)
                        else if(s.nextEpisodes.isNotEmpty())control("episodes",Icons.AutoMirrored.Outlined.FormatListBulleted,stringResource(R.string.player_episodes)) {
                            rememberOrigin("episodes");s.sheet="episodes"
                        }
                        control("settings",Icons.Outlined.Settings,stringResource(R.string.player_settings)) {
                            rememberOrigin("settings");a.openMenu("quick",null)
                        }
                    }
                }
                if(narrow) {
                    Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.Center) {transport()}
                    Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        if(s.details!=null)control("info",Icons.Outlined.Info,stringResource(R.string.player_details)){rememberOrigin("info");s.sheet="info"}
                        else Spacer(Modifier.width(button))
                        utilities()
                    }
                } else Box(Modifier.fillMaxWidth().padding(top=6.dp)) {
                    if(s.details!=null)Box(Modifier.align(Alignment.CenterStart)) {
                        control("info",Icons.Outlined.Info,stringResource(R.string.player_details)){rememberOrigin("info");s.sheet="info"}
                    }
                    Box(Modifier.align(Alignment.Center)) {transport()}
                    Box(Modifier.align(Alignment.CenterEnd)) {utilities()}
                }
            }
            if(s.seekPreview>=0f && s.duration>0) {
                BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=inset,end=inset,bottom=dockHeight+30.dp)) {
                    val x=(maxWidth*s.seekPreview-120.dp).coerceIn(0.dp,(maxWidth-240.dp).coerceAtLeast(0.dp))
                    Box(Modifier.offset(x=x)) {
                        if(s.trickplay?.valid==true)Thumbnail(s.trickplay!!,(s.seekPreview*s.duration).toLong())
                        else s.localUri?.let {LocalSeekPreview(it,(s.seekPreview*s.duration).toLong())}
                    }
                }
            }
            when(s.sheet) {
                SOUND_SHEET,SUBTITLE_SHEET,"episodes"->PlaybackChoices(s,a)
                "info"->s.details?.let {details->
                    Box(Modifier.fillMaxSize().focusProperties {onExit={cancelFocusChange()}}.focusGroup().background(Coal.copy(alpha=.92f))) {
                        InfoPanel(details)
                        val close=remember {FocusRequester()}
                        LaunchedEffect(Unit){close.requestFocus()}
                        PlaybackControl(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.player_back),glass,
                            Modifier.padding(inset).focusRequester(close)) {s.sheet=null}
                    }
                }
            }
        }
    }
}

/** One surface owns the focus geometry. Cinema leaves unfocused icons unboxed. */
@Composable
private fun PlaybackControl(icon:ImageVector,label:String,glass:Boolean,modifier:Modifier=Modifier,enabled:Boolean=true,size:Dp=40.dp,onClick:()->Unit) {
    var focused by remember {mutableStateOf(false)}
    Focusable(onClick,modifier.size(size).onFocusChanged {focused=it.isFocused},radius=TvTokens.Shape.Pill,
        container=if(glass)Color.White.copy(alpha=.07f) else Color.Transparent,
        focusedContainer=Ivory,focusedContent=Coal,outline=false,focusedScale=TvTokens.Focus.None,enabled=enabled) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Image(icon,label,Modifier.size(22.dp),colorFilter=ColorFilter.tint(when { !enabled->Ivory.copy(alpha=.32f);focused->Coal;else->Ivory }))
        }
    }
}

@Composable
internal fun CinematicTimeline(played:Float,buffered:Float,focused:Boolean,accent:Color) {
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val line=if(focused)4.dp.toPx() else 2.5.dp.toPx()
        val radius=if(focused)6.dp.toPx() else 4.dp.toPx()
        val start=Offset(radius,center.y);val width=(size.width-2*radius).coerceAtLeast(0f)
        drawLine(Color.White.copy(alpha=.21f),start,Offset(start.x+width,center.y),line,StrokeCap.Round)
        drawLine(Color.White.copy(alpha=.23f),start,Offset(start.x+width*buffered.coerceIn(0f,1f),center.y),line,StrokeCap.Round)
        val end=Offset(start.x+width*played.coerceIn(0f,1f),center.y)
        drawLine(Brush.horizontalGradient(listOf(accent,lerp(accent,Ivory,.3f))),start,end,line,StrokeCap.Round)
        drawCircle(Ivory,radius,end)
    }
}

internal fun playbackEndTime(now:Long,position:Long,duration:Long,speed:Float):Long? {
    if(duration<=0 || !speed.isFinite() || speed<=0)return null
    val remaining=((duration-position.coerceAtLeast(0)).coerceAtLeast(0)/speed.toDouble()).toLong()
    return now+minOf(remaining,Long.MAX_VALUE-now)
}

@Composable
private fun PlaybackEndTime(s:ControlsState) {
    if(!s.playing || s.duration<=0)return
    val context=LocalContext.current
    val format=remember(context){DateFormat.getTimeFormat(context)}
    // Position already updates with playback; sample the clock at the same time so the end
    // estimate does not drift between timer ticks, and only format when its minute changes.
    playbackEndTime(System.currentTimeMillis(),s.position,s.duration,s.playbackSpeed)?.let {end->
        val label=remember(end/60_000,format){format.format(Date(end))}
        Text(stringResource(R.string.player_ends_at,label),color=Soft,fontSize=12.sp,maxLines=1)
    }
}

/** Short, bounded lists. Focus and selection have separate visual cues. */
@Composable
private fun PlaybackChoices(s:ControlsState,a:ControlsActions) {
    val kind=s.sheet
    val audio=kind==SOUND_SHEET
    val episodes=kind=="episodes"
    val title=stringResource(if(episodes)R.string.player_episodes else if(audio)R.string.player_audio else R.string.settings_subtitles)
    val choices=if(episodes)s.nextEpisodes.map {"S${it.season} · E${it.episode}  ${it.title}" to false}
        else if(audio)s.audioTracks else s.subtitleOptions
    val more=if(episodes)null else stringResource(if(audio)R.string.player_more_audio else R.string.player_more_subtitles)
    val chosen=choices.indexOfFirst {it.second}.coerceAtLeast(0)
    val measure=rememberTextMeasurer()
    val density=LocalDensity.current
    val width=remember(choices,title,more,density) {
        with(density){(choices.map {it.first}+listOfNotNull(title,more)).maxOf {measure.measure(AnnotatedString(it),TextStyle(fontSize=14.sp),maxLines=1).size.width}.toDp()}+64.dp
    }
    BoxWithConstraints(Modifier.fillMaxSize().guardOpeningPress().focusProperties {onExit={cancelFocusChange()}}.focusGroup(),contentAlignment=Alignment.CenterEnd) {
        val panelWidth=width.coerceIn(minOf(220.dp,maxWidth*.8f),minOf(420.dp,maxWidth*.8f))
        Column(Modifier.padding(18.dp).width(panelWidth).heightIn(max=maxHeight*.68f).clip(RoundedCornerShape(TvTokens.Shape.Panel.dp))
            .background(Color(0xF01B1E23)).border(.7.dp,Color.White.copy(alpha=.14f),RoundedCornerShape(TvTokens.Shape.Panel.dp)).padding(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(title,color=Ivory,fontSize=16.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f).padding(8.dp))
                val close=remember {FocusRequester()}
                if(choices.isEmpty() && more==null)LaunchedEffect(Unit){close.requestFocus()}
                PlaybackControl(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.player_back),false,Modifier.focusRequester(close)){s.sheet=null}
            }
            LazyColumn(state=rememberLazyListState(initialFirstVisibleItemIndex=(chosen-2).coerceAtLeast(0))) {
                itemsIndexed(choices,key={i,_->i}) {index,(label,selected)->
                    PlaybackChoice(label,selected,index==chosen) {
                        s.sheet=null
                        if(episodes)a.playEpisode(index) else if(!selected){if(audio)a.pickAudio(index) else a.pickSubtitle(index)}
                    }
                }
                if(more!=null)item("more") {PlaybackChoice(more,false,choices.isEmpty()) {s.sheet=null;a.openMenu("options",if(audio)"audio" else "subtitles")}}
            }
        }
    }
}

@Composable
private fun PlaybackChoice(label:String,selected:Boolean,first:Boolean,onClick:()->Unit) {
    val focus=remember {FocusRequester()}
    var focused by remember {mutableStateOf(false)}
    if(first)LaunchedEffect(Unit) {focus.requestFocus()}
    Focusable(onClick,Modifier.focusRequester(focus).fillMaxWidth().onFocusChanged {focused=it.isFocused}.semantics {this.selected=selected},radius=TvTokens.Shape.Card,
        container=Color.Transparent,focusedContainer=Ivory,focusedContent=Coal,outline=false,focusedScale=TvTokens.Focus.None) {
        Row(Modifier.padding(horizontal=10.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(label,color=if(focused)Coal else Ivory,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            if(selected)Box(Modifier.size(6.dp).clip(CircleShape).background(if(focused)Coal else LocalPlayerAccent.current))
        }
    }
}
