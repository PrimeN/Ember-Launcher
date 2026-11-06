package app.ember.tv.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/** Only the foremost menu owns the dim/blur layer, including nested dialog and popup windows. */
internal class MenuBackdropStack {
    private val entries = linkedMapOf<Any, (Boolean) -> Unit>()
    val empty get() = entries.isEmpty()
    fun add(key: Any, apply: (Boolean) -> Unit) {
        entries.values.lastOrNull()?.invoke(false)
        entries[key] = apply
        apply(true)
    }
    fun remove(key: Any) {
        val foremost = entries.keys.lastOrNull() == key
        entries.remove(key)?.invoke(false)
        if (foremost) entries.values.lastOrNull()?.invoke(true)
    }
}

private object MenuBackdrops {
    private val stacks = WeakHashMap<Any, MenuBackdropStack>()
    fun attach(host: Any, apply: (Boolean) -> Unit): () -> Unit {
        val stack = stacks.getOrPut(host) { MenuBackdropStack() }
        val key = Any()
        stack.add(key, apply)
        return {
            stack.remove(key)
            if (stack.empty) stacks.remove(host)
        }
    }
}

/** Changes only backdrop flags; focus, touch routing, window geometry and secure flags stay intact. */
@Suppress("DEPRECATION")
internal class MenuBackdropTarget(
    val host: Any,
    val manager: WindowManager,
    val context: Context,
    private val attributes: () -> WindowManager.LayoutParams,
    private val update: (WindowManager.LayoutParams) -> Unit,
) {
    fun apply(active: Boolean, blur: Boolean, radius: Int) {
        runCatching {
            val params = attributes()
            val mask = WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            val flags = (params.flags and mask.inv()) or
                (if (active) WindowManager.LayoutParams.FLAG_DIM_BEHIND else 0) or
                (if (active && blur && Build.VERSION.SDK_INT >= 31) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0)
            val dim = if (active) FloatingPanelStyle.scrim.alpha else 0f
            val pixels = if (active && blur) radius else 0
            if (params.flags != flags || params.dimAmount != dim ||
                Build.VERSION.SDK_INT >= 31 && params.blurBehindRadius != pixels) {
                params.flags = flags
                params.dimAmount = dim
                if (Build.VERSION.SDK_INT >= 31) params.blurBehindRadius = pixels
                update(params)
            }
        }
    }
}

/** Configuration changes update an existing lease so they never reorder nested menus. */
internal class MenuBackdropLease(private val target: MenuBackdropTarget) {
    private var active = false
    private var closed = false
    private var wanted = false
    private var allowed = false
    private var radius = 0
    private var stopBlur: (() -> Unit)? = null
    private val release = MenuBackdrops.attach(target.host) { active = it; render() }
    private fun render() { if (!closed) target.apply(active, wanted && allowed, radius) }

    fun update(blur: Boolean, pixels: Int) {
        if (closed) return
        radius = pixels.coerceAtLeast(0)
        val wants = blur && Build.VERSION.SDK_INT >= 31
        if (wanted != wants) {
            wanted = wants
            stopBlur?.invoke(); stopBlur = null
            allowed = false
            if (wants) stopBlur = observeBlur()
        }
        render()
    }

    @androidx.annotation.RequiresApi(31)
    private fun observeBlur(): () -> Unit {
        allowed = runCatching { target.manager.isCrossWindowBlurEnabled }.getOrDefault(false)
        val listener = java.util.function.Consumer<Boolean> {
            if (!closed && wanted) { allowed = it; render() }
        }
        return runCatching {
            target.manager.addCrossWindowBlurEnabledListener(target.context.mainExecutor, listener)
            ({ runCatching { target.manager.removeCrossWindowBlurEnabledListener(listener) }; Unit })
        }.getOrDefault({})
    }

    fun close() {
        if (closed) return
        closed = true
        stopBlur?.invoke(); stopBlur = null
        release()
        // An outgoing window must not retain a second dim layer during its exit animation.
        target.apply(false, false, 0)
    }
}

private fun Context.menuHost(): Window? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        val next = current.baseContext
        if (next === current) break
        current = next
    }
    return null
}

@Composable
private fun MenuBackdrop(target: MenuBackdropTarget?) {
    val blur = LocalRenderOptions.current.glassTier == GLASS_LIVE && !RuntimeBudgets.pressured
    // Backdrop softness is independent of the compact menu's text/layout scale.
    val radius = (LocalView.current.resources.displayMetrics.density * 20).toInt().coerceAtLeast(1)
    var lease by remember(target) { mutableStateOf<MenuBackdropLease?>(null) }
    DisposableEffect(target) {
        val attached = target?.let(::MenuBackdropLease)
        lease = attached
        onDispose { attached?.close() }
    }
    SideEffect { lease?.update(blur, radius) }
}

@Composable
internal fun DialogBackdrop(window: Window?) {
    val target = remember(window) { window?.let {
        MenuBackdropTarget(it.context.menuHost() ?: it, it.windowManager, it.context,
            { it.attributes }, { params -> it.attributes = params })
    } }
    MenuBackdrop(target)
}

/** Popups are separate Android windows but do not expose a DialogWindowProvider. */
@Composable
internal fun PopupBackdrop() {
    val view = LocalView.current
    var root by remember(view) { mutableStateOf<View?>(null) }
    DisposableEffect(view) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root = v.rootView }
            override fun onViewDetachedFromWindow(v: View) { root = null }
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) root = view.rootView
        onDispose { view.removeOnAttachStateChangeListener(listener) }
    }
    val target = remember(root) { root?.let { popup ->
        val params = popup.layoutParams as? WindowManager.LayoutParams
        if (params == null || params.type !in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW) null
        else {
            val manager = popup.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            MenuBackdropTarget(popup.context.menuHost() ?: popup, manager, popup.context,
                { popup.layoutParams as WindowManager.LayoutParams }, { manager.updateViewLayout(popup, it) })
        }
    } }
    MenuBackdrop(target)
}
