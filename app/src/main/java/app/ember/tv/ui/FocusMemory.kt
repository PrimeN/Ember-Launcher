package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/** A title can occur in several rows: identity alone is not a return address. */
internal data class CardOrigin(val profile: String, val scene: String?, val slot: Int, val item: String)
internal class FocusReturnState {
    var focused: CardOrigin? = null
    var target: CardOrigin? = null; private set
    var item: String? = null; private set
    fun open(id: String) { item = id; target = focused?.takeIf { it.item == id } }
    fun matches(origin: CardOrigin) = item == origin.item && (target == null || target == origin)
    fun clear() { item = null; target = null }
}

val LocalFocusProfile = staticCompositionLocalOf { "default" }
object FocusMemory {
    internal val state = FocusReturnState()
    var pending: String?
        get() = state.item
        set(value) { if (value == null) state.clear() else state.open(value) }
}

/** The slot includes the keyed row/card, and stays stable across details visits. */
@Composable
fun Modifier.restoreFocus(id: String): Modifier {
    val requester = remember { FocusRequester() }
    @Suppress("DEPRECATION")
    val origin = CardOrigin(LocalFocusProfile.current, LocalFocusScene.current, currentCompositeKeyHash, id)
    LaunchedEffect(origin) {
        if (FocusMemory.state.matches(origin)) {
            delay(120)
            if (FocusMemory.state.matches(origin) && runCatching { requester.requestFocus() }.getOrDefault(false))
                FocusMemory.pending = null
        }
    }
    return focusRequester(requester).onFocusChanged { if (it.isFocused) FocusMemory.state.focused = origin }
}
