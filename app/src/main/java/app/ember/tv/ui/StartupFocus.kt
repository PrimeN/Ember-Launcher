package app.ember.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged

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
    var pending by remember(owner){mutableStateOf(emptyList<FocusDirection>())}
    val manager = androidx.compose.ui.platform.LocalFocusManager.current
    val placed = owner.tabReady(tab)
    val focusedTab = owner.focusedTab
    LaunchedEffect(owner,tab,enabled,placed,focused,focusedTab,pending) {
        if (!enabled || !placed || owner.startup.established && pending.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        owner.startup.ensure(true, focusedTab == tab || focusedTab == null && focused && owner.preferred(tab) != null) {
            owner.tab(tab).requestFocus() && owner.focusedTab == tab
        }
        if (owner.startup.established && pending.isNotEmpty()) {
            val direction = pending.first()
            pending = pending.drop(1)
            manager.moveFocus(direction)
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
            if (!owner.startup.established && event.type == KeyEventType.KeyDown && direction != null) {
                pending = pending + direction
                true
            } else false
        }
        .initialNavigationFocusGroup(owner.startup,{enabled},
            {owner.focusedTab == tab || owner.focusedTab == null && focused && owner.preferred(tab) != null},
            {owner.tab(tab).requestFocus() && owner.focusedTab == tab},
            onEarlyDirection = {pending = pending + it})
}

/** Logical previous/next follows layout direction, matching the arrows shown on screen. */
internal fun choiceStep(right:Boolean,rtl:Boolean)=if(right!=rtl)1 else -1

internal fun returnsToNavigation(direction:FocusDirection,side:Boolean,rtl:Boolean):Boolean =
    direction==FocusDirection.Up || side && direction==(if(rtl)FocusDirection.Right else FocusDirection.Left)
