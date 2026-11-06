package app.ember.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.collect

/** Keep a few deliberate presses, never a held-key backlog accumulated while the UI loads. */
internal class StartupDirections {
    private var values by mutableStateOf(emptyList<FocusDirection>())
    val size get()=values.size
    fun offer(direction:FocusDirection,repeatCount:Int=0) {
        if(repeatCount==0 && values.size<4)values=values+direction
    }
    fun take():FocusDirection?=values.firstOrNull()?.also {values=values.drop(1)}
    fun clear() {values=emptyList()}
}

/** One startup handoff per navigation owner; window returns and ordinary moves keep their focus. */
internal class InitialNavigationFocusState {
    var established=false;private set
    private var requesting=false
    fun ensure(ready:Boolean,hasFocus:Boolean,request:()->Boolean):Boolean {
        if(established)return true
        if(!ready || requesting)return false
        if(hasFocus){established=true;return true}
        requesting=true
        return try {runCatching(request).getOrDefault(false).also {if(it)established=true}}
        finally {requesting=false}
    }
}

/** Directional entry can wait for a placed target without losing the originating key. */
internal fun Modifier.initialNavigationFocusGroup(state:InitialNavigationFocusState,ready:()->Boolean,
    focused:()->Boolean,request:()->Boolean,onEarlyDirection:((FocusDirection)->Unit)?=null):Modifier=focusProperties {
    onEnter={
        if(requestedFocusDirection in listOf(FocusDirection.Up,FocusDirection.Down,FocusDirection.Left,FocusDirection.Right)) {
            if (!state.established && onEarlyDirection != null) {
                onEarlyDirection(requestedFocusDirection)
                cancelFocusChange()
            } else state.ensure(ready(),focused(),request)
        }
    }
}.focusGroup()

/** Seed the real tab after placement and replay early directional input from that tab. */
@Composable
internal fun Modifier.initialNavigationFocus(owner:NavigationFocus,tab:String,enabled:Boolean,available:Boolean=true):Modifier {
    if (!available) return this
    var focused by remember(owner){mutableStateOf(false)}
    val pending=remember(owner){StartupDirections()}
    val manager = androidx.compose.ui.platform.LocalFocusManager.current
    val currentTab by rememberUpdatedState(tab)
    val active by rememberUpdatedState(enabled)
    LaunchedEffect(owner,manager) {
        // collect (not collectLatest): more input must not cancel the frame that establishes focus.
        snapshotFlow {listOf(active,currentTab,owner.tabReady(currentTab),focused,owner.focusedTab,pending.size)}.collect {
            if (!active) {
                if(owner.startup.established)pending.clear()
                return@collect
            }
            if (!owner.tabReady(currentTab)) {
                if(owner.startup.established)pending.clear()
                return@collect
            }
            if(owner.startup.established && pending.size==0)return@collect
            withFrameNanos { }
            if(!active || !owner.tabReady(currentTab))return@collect
            owner.startup.ensure(true, owner.focusedTab == currentTab || owner.focusedTab == null && focused && owner.preferred(currentTab) != null) {
                owner.tab(currentTab).requestFocus() && owner.focusedTab == currentTab
            }
            if(!owner.startup.established)return@collect
            while(pending.size>0) {
                if(!active) {pending.clear();break}
                if(!owner.tabReady(currentTab)) {pending.clear();break}
                val direction=pending.take() ?: break
                try {manager.moveFocus(direction)}
                catch(error:IllegalStateException) {
                    app.ember.tv.data.CrashLog.note("Startup focus target changed during navigation",error)
                    pending.clear()
                    break
                }
                // A move may switch the page or open a dialog. Re-read readiness after layout.
                if(pending.size>0)withFrameNanos { }
            }
        }
    }
    return onFocusChanged {focused=it.hasFocus}
        .onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionUp -> FocusDirection.Up
                Key.DirectionDown -> FocusDirection.Down
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> null
            }
            if ((!owner.startup.established || pending.size>0) && event.type == KeyEventType.KeyDown && direction != null) {
                pending.offer(direction,event.nativeKeyEvent.repeatCount)
                true
            } else false
        }
        .initialNavigationFocusGroup(owner.startup,{enabled},
            {owner.focusedTab == tab || owner.focusedTab == null && focused && owner.preferred(tab) != null},
            {owner.tab(tab).requestFocus() && owner.focusedTab == tab},
            onEarlyDirection = pending::offer)
}

/** Logical previous/next follows layout direction, matching the arrows shown on screen. */
internal fun choiceStep(right:Boolean,rtl:Boolean)=if(right!=rtl)1 else -1

internal fun returnsToNavigation(direction:FocusDirection,side:Boolean,rtl:Boolean):Boolean =
    direction==FocusDirection.Up || side && direction==(if(rtl)FocusDirection.Right else FocusDirection.Left)
