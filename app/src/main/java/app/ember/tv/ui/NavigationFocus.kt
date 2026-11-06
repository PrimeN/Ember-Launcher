package app.ember.tv.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import app.ember.tv.data.NavTabs

/** Stable requesters per scene keep hover navigation from stealing content focus. */
class NavigationFocus {
    internal val startup=InitialNavigationFocusState()
    private val tabs = mutableMapOf<String, FocusRequester>()
    private val placedTabs = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    internal var focusedTab by androidx.compose.runtime.mutableStateOf<String?>(null); private set
    internal fun tabPlaced(id: String) { placedTabs[id] = true }
    internal fun tabReady(id: String) = placedTabs[id] == true
    internal fun tabFocus(id: String, focused: Boolean) {
        if (focused) focusedTab = id else if (focusedTab == id) focusedTab = null
    }
    internal fun tabDetached(id: String) { placedTabs.remove(id); tabFocus(id, false) }
    private val pages = mutableMapOf<String, FocusRequester>()
    private val targets = androidx.compose.runtime.mutableStateMapOf<String, FocusRequester>()
    private val last = androidx.compose.runtime.mutableStateMapOf<String, String>()
    fun tab(id: String) = tabs.getOrPut(id) { FocusRequester() }
    fun page(id: String) = pages.getOrPut(id) { FocusRequester() }
    fun register(scene: String, item: String, requester: FocusRequester) { targets["$scene/$item"] = requester }
    fun unregister(scene: String, item: String, requester: FocusRequester) {
        if (targets["$scene/$item"] === requester) targets.remove("$scene/$item")
    }
    fun focused(scene: String, item: String) { last[scene] = item }
    fun preferred(scene: String): FocusRequester? = last[scene]?.let { targets["$scene/$it"] }
    fun content(scene: String): FocusRequester = preferred(scene) ?: page(scene)
}

val LocalNavigationFocus = staticCompositionLocalOf<NavigationFocus?> { null }
val LocalFocusScene = staticCompositionLocalOf<String?> { null }
val LocalSceneInteractive = compositionLocalOf { true }

/** Home shortcuts can open personal pages without requiring a permanent navigation tab. */
internal fun resolveNavigationPage(page:String,visible:List<String>,personal:Boolean):String {
    val personalPage=page in setOf(NavTabs.WATCHLIST,NavTabs.PROGRESS,NavTabs.RECENT,NavTabs.LIBRARY)
    return when {
        personalPage && !personal -> NavTabs.HOME
        page in visible || personalPage || page==NavTabs.DISCOVER -> page
        else -> NavTabs.HOME
    }
}

/** Personal pages can be opened without having a visible tab of their own. */
internal fun navigationTabForPage(page: String, visible: List<String>): String = when {
    page in visible -> page
    page in listOf(NavTabs.WATCHLIST, NavTabs.PROGRESS, NavTabs.RECENT) && NavTabs.LIBRARY in visible -> NavTabs.LIBRARY
    NavTabs.HOME in visible -> NavTabs.HOME
    else -> visible.firstOrNull { it != NavTabs.SEARCH } ?: NavTabs.SEARCH
}

@Composable
fun Modifier.sceneContent(id: String, tab: String, side: Boolean = false, enabled: Boolean = true): Modifier {
    val focus = LocalNavigationFocus.current ?: return this
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    return sceneFocusGroup(focus.page(id),
        restore = { runCatching { focus.preferred(id)?.requestFocus() == true }.getOrDefault(false) },
        leave = { direction ->
            if (enabled && returnsToNavigation(direction,side,rtl))
                runCatching { focus.tab(tab).requestFocus() }.getOrDefault(false)
            else false
        })
}

/** One owner for enter/exit callbacks: focusRestorer would overwrite this exit route. */
internal fun Modifier.sceneFocusGroup(page: FocusRequester, restore: () -> Boolean,
    leave: (FocusDirection) -> Boolean): Modifier = focusRequester(page).focusProperties {
        onEnter = {
            // A direct request (e.g. a Settings search destination) must keep its own target.
            if (requestedFocusDirection != FocusDirection.Enter) restore()
        }
        onExit = {
            // Compose detects the new active target and reports Redirected. Canceling here
            // would instead report the original D-pad move as Cancelled after redirecting it.
            leave(requestedFocusDirection)
        }
    }.focusGroup()

/** Details has its own Back control; leaving it must never target a hidden navigation tab. */
internal fun Modifier.detailsFocusGroup(page:FocusRequester,restore:()->Boolean,back:()->Boolean):Modifier =
    sceneFocusGroup(page,restore) {direction->if(direction==FocusDirection.Up)back() else false}

/** Guide rows enter the ruler first; its vertical exits own carousel/channel transfers. */
internal fun Modifier.liveRulerFocusGroup(page:FocusRequester,up:()->Boolean,down:()->Boolean):Modifier =
    sceneFocusGroup(page,restore={false}) {direction->when(direction) {
        FocusDirection.Up->up()
        FocusDirection.Down->down()
        else->false
    }}

/** Cover the whole bar, including Profile and actions, before geometric entry chooses a child. */
internal fun Modifier.navigationFocusGroup(returnToActive: () -> Boolean): Modifier = focusProperties {
    onEnter = { if (requestedFocusDirection == FocusDirection.Up) returnToActive() }
}.focusGroup()

/** Page tabs follow remote focus and pointer hover. Dialog/action buttons still use OK. */
fun Modifier.openNavigationTab(id: String, selected: Boolean, enabled: Boolean = true, open: () -> Unit): Modifier {
    if (!enabled || id == NavTabs.QUICK) return this
    return composed {
        val currentSelected by rememberUpdatedState(selected)
        val currentOpen by rememberUpdatedState(open)
        onFocusChanged { if (it.isFocused && !currentSelected) currentOpen() }
            .pointerInput(id) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Enter && !currentSelected) currentOpen()
                    }
                }
            }
    }
}

/** Startup observes the actual attached tab instead of any focus inside the page group. */
internal fun Modifier.navigationTabTarget(owner: NavigationFocus, id: String): Modifier = composed {
    androidx.compose.runtime.DisposableEffect(owner, id) {
        onDispose { owner.tabDetached(id) }
    }
    focusRequester(owner.tab(id))
        .onFocusChanged { owner.tabFocus(id, it.isFocused) }
        .then(Modifier.onGloballyPositioned { owner.tabPlaced(id) })
}
