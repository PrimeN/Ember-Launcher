package app.ember.tv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal class SettingsTarget(val id: String?) {
    private var claimed = false
    var ready by mutableStateOf(false); private set
    var fulfilled by mutableStateOf(false); private set
    val completion = kotlinx.coroutines.CompletableDeferred<Boolean>()
    fun claim(): Boolean = if (claimed) false else { claimed = true; true }
    fun prepare() { ready = true }
    fun complete() { fulfilled = true; completion.complete(true) }
    fun failed() { completion.complete(false) }
}
internal val LocalSettingsTarget = staticCompositionLocalOf<SettingsTarget?> { null }

/** Reveal and focus a composed search destination after its lazy item reaches layout. */
internal fun Modifier.settingsAnchor(id: String?): Modifier = composed {
    val target = LocalSettingsTarget.current
    if (id == null || id != target?.id) return@composed this
    val bring = remember { BringIntoViewRequester() }
    val focus = remember { FocusRequester() }
    var highlighted by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target, id, target?.ready) {
        if (target?.ready == true && id != null && id == target.id && target.claim()) {
            withFrameNanos { }
            bring.bringIntoView()
            repeat(3) {
                if (runCatching { focus.requestFocus() }.getOrDefault(false)) {
                    target.complete()
                    highlighted = true
                    delay(2500)
                    highlighted = false
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
            target.failed()
        }
    }
    this.bringIntoViewRequester(bring).focusRequester(focus).focusGroup()
        .border(2.dp, if (highlighted) Ring else Color.Transparent, RoundedCornerShape(12.dp))
}
