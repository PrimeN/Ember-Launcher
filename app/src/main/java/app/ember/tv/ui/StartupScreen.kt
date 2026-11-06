package app.ember.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.R
import app.ember.tv.state.StartupPresentation
import app.ember.tv.state.StartupStage
import kotlin.math.cos
import kotlin.math.sin

private val Coal=Color(0xFF101012)
private val Amber=Color(0xFFFFB644)
private val EmberRed=Color(0xFFB72D21)

internal fun startupMotion(options:RenderOptions,reduced:Boolean)=!reduced && !options.lowPower && options.animationsEnabled

/** No new focus target: early directions keep reaching the existing startup navigation handoff. */
internal fun Modifier.startupInput(startup:StartupPresentation):Modifier=composed {
    val confirmations=remember(startup){mutableSetOf<Key>()}
    pointerInput(startup) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            if(!startup.ready) {
                startup.revealForInput()
                down.consume()
                do {
                    val event=awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach {it.consume()}
                } while(event.changes.any {it.pressed})
            }
        }
    }.onPreviewKeyEvent {event->
        if(event.key in confirmations) {
            if(event.type==KeyEventType.KeyUp)confirmations.remove(event.key)
            true
        } else if(!startup.ready && event.type==KeyEventType.KeyDown) {
            when(event.key) {
                Key.DirectionCenter,Key.Enter,Key.NumPadEnter,Key.Spacebar->{
                    confirmations.add(event.key)
                    startup.revealForInput()
                    true // Do not activate a button hidden underneath the curtain, including on release.
                }
                else->{startup.revealForInput();false}
            }
        } else false
    }
}

@Composable
internal fun StartupCurtain(startup:StartupPresentation) {
    val render=LocalRenderOptions.current
    val reduced=LocalReduceMotion.current
    val exit=render.animationMillis(140,reduced)
    AnimatedVisibility(visible=!startup.ready,enter=EnterTransition.None,
        exit=if(exit==0)ExitTransition.None else fadeOut(tween(exit))) {
        StartupScreen(startup.stage,render,reduced,LocalSceneInteractive.current)
    }
}

/** Locally drawn artwork: no network, bitmap decode, live blur, particles, or minimum display time. */
@Composable
internal fun StartupScreen(stage:StartupStage,render:RenderOptions,reduced:Boolean,active:Boolean=true) {
    val titles=listOf(R.string.startup_kindling,R.string.startup_charcoal,R.string.startup_spark,R.string.startup_ready)
    val notes=listOf(R.string.startup_preferences,R.string.startup_home,R.string.startup_controls,R.string.startup_settle_in)
    val title=stringResource(titles[stage.ordinal])
    val note=stringResource(notes[stage.ordinal])
    val pane=stringResource(R.string.startup_pane)
    val step=stringResource(R.string.startup_stage,stage.ordinal+1,StartupStage.entries.size)
    val motion=startupMotion(render,reduced) && active
    val pulse=if(motion)rememberInfiniteTransition(label="emberWarmth").animateFloat(.65f,1f,
        infiniteRepeatable(tween(1600,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="emberTip") else remember {mutableStateOf(1f)}
    val progress=animateFloatAsState(stage.ordinal.toFloat()/StartupStage.READY.ordinal,
        tween(if(motion)180 else 0),label="startupMilestones")
    BoxWithConstraints(Modifier.fillMaxSize().background(Coal).clearAndSetSemantics {
        paneTitle=pane
        contentDescription="$pane. $title $note"
        stateDescription=step
        liveRegion=LiveRegionMode.Polite
    }) {
        if(!render.lowPower)Canvas(Modifier.fillMaxSize()) {
            val focal=Offset(size.width*.5f,size.height*.42f)
            drawRect(Brush.radialGradient(listOf(Color(0xFF6D250D).copy(alpha=.30f),Color.Transparent),focal,size.minDimension*.65f))
            val grid=56.dp.toPx()
            var x=0f
            while(x<size.width) {drawLine(Color.White.copy(alpha=.018f),Offset(x,0f),Offset(x,size.height),.5.dp.toPx());x+=grid}
            var y=0f
            while(y<size.height) {drawLine(Color.White.copy(alpha=.018f),Offset(0f,y),Offset(size.width,y),.5.dp.toPx());y+=grid}
        }
        val diameter=minOf(maxWidth*.34f,maxHeight*.46f,252.dp).coerceAtLeast(112.dp)
        val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.25f
        Column(Modifier.align(Alignment.Center).fillMaxWidth(.88f),horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(diameter),contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset=10.dp.toPx()
                    val d=(size.minDimension-2*inset).coerceAtLeast(1f)
                    val origin=Offset(inset,inset)
                    val track=Size(d,d)
                    val stroke=2.5.dp.toPx()
                    val sweep=360f*if(stage==StartupStage.READY)1f else progress.value.coerceIn(0f,1f)
                    drawArc(Color(0xFF3F3835),-90f,360f,false,origin,track,style=Stroke(stroke))
                    rotate(-90f) {
                        val warmth=Brush.sweepGradient(0f to EmberRed,.50f to Color(0xFFF57720),1f to Amber)
                        if(!render.lowPower && sweep>0)drawArc(Amber.copy(alpha=.06f),0f,sweep,false,origin,track,style=Stroke(stroke*4,cap=StrokeCap.Round))
                        drawArc(warmth,0f,sweep,false,origin,track,style=Stroke(stroke,cap=StrokeCap.Round))
                    }
                    if(sweep>0) {
                        val angle=Math.toRadians((sweep-90f).toDouble())
                        val tip=Offset(center.x+cos(angle).toFloat()*d/2,center.y+sin(angle).toFloat()*d/2)
                        if(motion)drawCircle(Amber.copy(alpha=.10f*pulse.value),stroke*3,tip)
                        drawCircle(Amber,stroke*.70f,tip)
                    }
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    // Keep the actual Ember identity in every selected theme colourway.
                    EmberMark(diameter*.35f)
                    EmberWordmark(diameter*.09f,Color.White)
                }
            }
            Spacer(Modifier.height(if(large)12.dp else 22.dp))
            Text(title,color=Color(0xFFF8F3ED),fontSize=if(large)22.sp else 28.sp,fontWeight=FontWeight.Medium,
                textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
            Spacer(Modifier.height(8.dp))
            Text(note,color=Color(0xFFC2B5AA),fontSize=15.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
            Spacer(Modifier.height(if(large)16.dp else 28.dp))
            Canvas(Modifier.size(68.dp,10.dp)) {
                repeat(StartupStage.entries.size) {i->
                    drawCircle(if(i<=stage.ordinal)Amber else Color(0xFF493E39),2.8.dp.toPx(),Offset((10+i*16).dp.toPx(),center.y))
                }
            }
        }
    }
}
