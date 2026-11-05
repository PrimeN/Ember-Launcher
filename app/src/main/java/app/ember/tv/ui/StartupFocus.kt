package app.ember.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.*
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

/** Handles fast first D-pad input before the initial layout/window handoff has completed. */
internal fun Modifier.initialNavigationFocusGroup(state:InitialNavigationFocusState,ready:()->Boolean,
    focused:()->Boolean,request:()->Boolean):Modifier=focusProperties {
    onEnter={
        if(requestedFocusDirection in listOf(FocusDirection.Up,FocusDirection.Down,FocusDirection.Left,FocusDirection.Right))
            state.ensure(ready(),focused(),request)
    }
}.focusGroup()

@Composable
internal fun Modifier.initialNavigationFocus(owner:NavigationFocus,tab:String,enabled:Boolean):Modifier {
    var focused by remember(owner){mutableStateOf(false)}
    val ready by rememberUpdatedState(enabled)
    val request by rememberUpdatedState<()->Boolean>({owner.tab(tab).requestFocus()})
    LaunchedEffect(owner,tab,enabled) {
        if(!enabled || owner.startup.established)return@LaunchedEffect
        repeat(6) {
            withFrameNanos { }
            if(owner.startup.ensure(ready,focused,request))return@LaunchedEffect
        }
    }
    return onFocusChanged {focused=it.hasFocus}
        .initialNavigationFocusGroup(owner.startup,{ready},{focused},{request()})
}

/** Logical previous/next follows layout direction, matching the arrows shown on screen. */
internal fun choiceStep(right:Boolean,rtl:Boolean)=if(right!=rtl)1 else -1

internal fun returnsToNavigation(direction:FocusDirection,side:Boolean,rtl:Boolean):Boolean =
    direction==FocusDirection.Up || side && direction==(if(rtl)FocusDirection.Right else FocusDirection.Left)
