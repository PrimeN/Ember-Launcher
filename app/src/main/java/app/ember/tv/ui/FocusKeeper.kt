package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch

/**
 * Keeping remote focus when the focused thing disappears (#130). A press that removes its own
 * item (Remove from Continue Watching, Remove on an addon, Clear…) otherwise leaves nothing
 * focused, and the next press is spent finding focus again.
 *
 * Three layers, from the most local:
 * 1. [rememberFocusKeeper]: rows and grids hand focus to the item that takes the removed one's place.
 * 2. [rescueFocusInside]: a settings section hands focus to its own first control.
 * 3. MainActivity: a page with nothing focused returns focus to its usual target, as after a TV wake.
 */

/** Whether anything in the window's main content has focus; provided by MainActivity. */
internal class FocusPresence { var any by mutableStateOf(false) }

internal val LocalFocusPresence = staticCompositionLocalOf<FocusPresence?> { null }

internal class FocusKeeper {
    internal val requesters = HashMap<Any, FocusRequester>()
    internal var key: Any? = null
    internal var index = 0
    internal var latest: List<Any> = emptyList()
}

/** Pair with [focusKeeperList] on the row or grid and [focusKeeperItem] on each item. [onEmpty] runs when the last item left. */
@Composable
internal fun rememberFocusKeeper(keys: List<Any>, onEmpty: (() -> Unit)? = null): FocusKeeper {
    val keeper = remember { FocusKeeper() }
    keeper.latest = keys
    val empty by rememberUpdatedState(onEmpty)
    LaunchedEffect(keys) {
        val gone = keeper.key ?: return@LaunchedEffect
        if (gone in keys) return@LaunchedEffect
        keeper.key = null
        withFrameNanos { }
        if (keys.isEmpty()) { empty?.let { runCatching(it) }; return@LaunchedEffect }
        val next = keys[keeper.index.coerceIn(0, keys.lastIndex)]
        runCatching { keeper.requesters[next]?.requestFocus() }
    }
    return keeper
}

/** Leaving the list on purpose (another row, the menu) forgets the item, so nothing is pulled back. */
internal fun Modifier.focusKeeperList(keeper: FocusKeeper): Modifier = onFocusChanged {
    if (!it.hasFocus && keeper.key != null && keeper.key in keeper.latest) keeper.key = null
}

@Composable
internal fun Modifier.focusKeeperItem(keeper: FocusKeeper, key: Any, index: Int): Modifier {
    val requester = remember(key) { FocusRequester() }
    DisposableEffect(keeper, key) {
        keeper.requesters[key] = requester
        onDispose { if (keeper.requesters[key] === requester) keeper.requesters.remove(key) }
    }
    val currentIndex by rememberUpdatedState(index)
    return focusRequester(requester).onFocusChanged {
        if (it.hasFocus) { keeper.key = key; keeper.index = currentIndex }
    }
}

/**
 * A container (a settings section) that had focus and lost it with nothing else focused takes it
 * back on its first control. Focus that moved somewhere else is never pulled back.
 */
@Composable
internal fun Modifier.rescueFocusInside(): Modifier {
    val presence = LocalFocusPresence.current ?: return this
    val requester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var had by remember { mutableStateOf(false) }
    return focusRequester(requester).onFocusChanged { state ->
        if (state.hasFocus) had = true
        else if (had) {
            had = false
            scope.launch {
                repeat(2) { withFrameNanos { } }
                if (!presence.any) runCatching { requester.requestFocus() }
            }
        }
    }
}
