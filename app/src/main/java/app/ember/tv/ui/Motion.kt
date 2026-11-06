package app.ember.tv.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberWindowFocused(): Boolean {
    val view = androidx.compose.ui.platform.LocalView.current
    var focused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused = it }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        // Close the gap between composition's snapshot and listener registration.
        focused = view.hasWindowFocus()
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
    return focused
}

/** Observe system settings while running; TalkBack users should not chase rotating titles. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    fun reduced() = runCatching { Settings.Global.getFloat(context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) || manager.isTouchExplorationEnabled
    var reduce by remember { mutableStateOf(reduced()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduce = reduced() }
        }
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { reduce = reduced() }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            manager.removeTouchExplorationStateChangeListener(listener)
        }
    }
    return reduce
}
