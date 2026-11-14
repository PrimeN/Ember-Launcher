package app.ember.tv.player

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.ui.*

internal data class PlayerDialogAction(val label: String, val icon: ImageVector? = null,
    val selected: Boolean = false, val destructive: Boolean = false, val action: () -> Unit)

/** The shared TV dialog owns its window; removing this tiny host also disposes its composition. */
internal class PlayerActionDialog(private val host: ComposeView, private val restore: View?, private val cancel: () -> Unit) {
    var target: View? = null
    var isShowing = true; private set
    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        target = null
        host.disposeComposition()
        (host.parent as? ViewGroup)?.removeView(host)
        restore?.takeIf { it.isAttachedToWindow }?.requestFocus()
    }
    fun dispatch(event: KeyEvent): Boolean {
        if (!isShowing) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { dismiss(); cancel() }
            return true
        }
        return target?.dispatchKeyEvent(event) ?: false
    }

}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PlayerActivity.showPlayerActions(title: String, actions: List<PlayerDialogAction>, message: String? = null,
    cancel: () -> Unit = {}): PlayerActionDialog {
    actionDialog?.dismiss()
    val host = ComposeView(this)
    val dialog = PlayerActionDialog(host, currentFocus, cancel)
    actionDialog = dialog
    addContentView(host, FrameLayout.LayoutParams(1, 1))
    host.setContent {
        val render = rememberDeviceRenderOptions(prefs.contentDensity, prefs.navigationGlass, prefs.artworkQuality,
            prefs.devicePerformance, prefs.customPerformance).copy(glassMethod = prefs.navigationGlassMethod)
        CompositionLocalProvider(LocalNight provides true, LocalRenderOptions provides render,
            LocalReduceMotion provides rememberReduceMotion()) {
            EmberTheme(prefs.accent, playerLayoutStyle) {
                PlayerActionMenu(title, actions, message, { dialog.dismiss(); cancel() },
                    selected = { action -> dialog.dismiss(); action.action() }, target = { dialog.target = it })
            }
        }
    }
    return dialog
}

@Composable
internal fun PlayerActionMenu(title: String, actions: List<PlayerDialogAction>, message: String?, close: () -> Unit,
    selected: (PlayerDialogAction) -> Unit = { it.action() }, target: (View?) -> Unit = {}) {
    CompactActionMenu(title, actions.map { it.label }, close) { first ->
        val view=LocalView.current
        DisposableEffect(view) { target(view); onDispose {target(null)} }
        message?.let { Text(it, color = Mute, fontSize = 13.sp, maxLines = 2) }
        actions.forEachIndexed { index, action ->
            MenuActionRow(action.label, if (index == 0) Modifier.focusRequester(first) else Modifier,
                action.icon ?: Icons.Outlined.MoreHoriz, selected = action.selected, destructive = action.destructive) { selected(action) }
        }
    }
}
