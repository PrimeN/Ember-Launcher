package app.ember.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection

/**
 * One Move control per row (#107). #456: pressing it picks the row up: it jiggles, the arrows move
 * it one place at a time (hold an arrow to keep going), OK puts it down and Back puts it back where
 * it was. [onMove] gets the step: negative is towards the top or front.
 */
@Composable
internal fun MoveButton(name: String, index: Int, lastIndex: Int, horizontal: Boolean = false, onMove: (Int) -> Unit) {
    if (lastIndex <= 0 || index !in 0..lastIndex) return
    val id = MoveSession.id(name, lastIndex)
    val active = MoveSession.active == id
    val focus = remember { FocusRequester() }
    val latest by rememberUpdatedState(index)
    if (active) {
        // The row may be drawn somewhere else after each move; focus follows it.
        LaunchedEffect(index) { withFrameNanos { }; runCatching { focus.requestFocus() } }
        BackHandler { onMove(MoveSession.start - latest); MoveSession.end() }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    ToolbarIcon(if (active) "Moving ${name.ifBlank { "item" }}, place ${index + 1} of ${lastIndex + 1}. Arrows move, OK places, Back cancels" else "Move",
        Icons.Outlined.OpenWith, selected = active,
        modifier = Modifier.focusRequester(focus).jiggle(active)
            .semantics { if (active) { stateDescription = "Place ${index + 1} of ${lastIndex + 1}"; liveRegion = LiveRegionMode.Polite } }
            .onPreviewKeyEvent { e ->
                if (!active) return@onPreviewKeyEvent false
                val back = if (horizontal) (if (rtl) Key.DirectionRight else Key.DirectionLeft) else Key.DirectionUp
                val forward = if (horizontal) (if (rtl) Key.DirectionLeft else Key.DirectionRight) else Key.DirectionDown
                when (e.key) {
                    back, forward -> {
                        if (e.type == KeyEventType.KeyDown) {
                            val step = if (e.key == back) -1 else 1
                            if (latest + step in 0..lastIndex) onMove(step)
                        }
                        true
                    }
                    // The other arrows would carry focus away mid-move.
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyDown) MoveSession.armed = true
                        else if (e.type == KeyEventType.KeyUp && MoveSession.armed) MoveSession.end()
                        true
                    }
                    else -> false
                }
            }) {
        // Keys never get here while moving; a screen reader's tap puts it down.
        if (!active) MoveSession.begin(id, index) else MoveSession.end()
    }
}

/** #456: the one list row being moved, if any. */
internal object MoveSession {
    var active by mutableStateOf<String?>(null); private set
    var start = 0; private set
    /** OK went down during the move; the press that started it doesn't count. */
    var armed = false
    fun id(name: String, lastIndex: Int) = "$name#$lastIndex"
    fun begin(id: String, index: Int) { active = id; start = index; armed = false }
    fun end() { active = null; armed = false }
}

/** #456: a gentle wobble for whatever is being moved; still when motion is reduced. */
internal fun Modifier.jiggle(on: Boolean): Modifier = composed {
    if (!on || LocalReduceMotion.current) return@composed this
    val wobble = rememberInfiniteTransition(label = "move wobble")
    val angle by wobble.animateFloat(-1.6f, 1.6f, infiniteRepeatable(tween(150), RepeatMode.Reverse), label = "move angle")
    graphicsLayer { rotationZ = angle }
}

/** The choices for an item at [index] of [lastIndex] + 1, with their steps. */
internal fun moveOptions(index: Int, lastIndex: Int, horizontal: Boolean = false): List<Pair<String, Int>> = buildList {
    if (index > 1) add((if (horizontal) "To the front" else "To the top") to -index)
    if (index > 0) add((if (horizontal) "Left" else "Up") to -1)
    if (index < lastIndex) add((if (horizontal) "Right" else "Down") to 1)
    if (index < lastIndex - 1) add((if (horizontal) "To the end" else "To the bottom") to lastIndex - index)
}

/** [index] moved by [step], clamped to the list. */
internal fun <T> List<T>.moved(index: Int, step: Int): List<T> {
    if (index !in indices) return this
    val to = (index + step).coerceIn(0, lastIndex)
    return if (to == index) this else toMutableList().apply { add(to, removeAt(index)) }
}

/**
 * #456: an app picked up to move where it sits (Home's app rows, the Light Home, the Apps page).
 * The surface the app is drawn on says which apps sit beside it ([LocalAppMoveScope]); each arrow
 * saves the new order so the row reflows live, OK keeps it and Back restores the order it had.
 */
internal class AppMoveSession(val app: app.ember.tv.data.AppEntry, private val save: (List<String>) -> Unit, private val done: (Boolean) -> Unit) {
    val pkg get() = app.pkg
    var draft by mutableStateOf<AppMoveDraft?>(null); private set
    private var original: List<String> = emptyList()
    var columns = 0
    var armed = false
    val attached get() = draft != null
    fun attach(scope: AppMoveScope) {
        if (draft != null || pkg !in scope.order) return
        original = scope.order.distinct()
        draft = AppMoveDraft(original, pkg)
        columns = scope.columns
    }
    /** A single row has no Up or Down: it is as wide as the whole list. */
    fun step(horizontal: Int, vertical: Int): Boolean {
        val d = draft ?: return false
        val across = if (columns > 0) columns else d.order.size.coerceAtLeast(1)
        return d.move(across, horizontal, vertical).also { if (it) save(d.order) }
    }
    fun place() = finish(true)
    fun cancel() { if (draft != null && draft?.order != original) save(original); finish(false) }
    private fun finish(placed: Boolean) { if (AppMove.session === this) AppMove.session = null; done(placed) }
}

/** The apps one surface draws, in order, and how many sit across ([columns] 0 for one row). */
internal data class AppMoveScope(val order: List<String>, val columns: Int = 0)

internal val LocalAppMoveScope = compositionLocalOf<AppMoveScope?> { null }

internal object AppMove {
    var session by mutableStateOf<AppMoveSession?>(null)
}

/** #456: on the app being moved: it jiggles, keeps focus as it travels, and takes the arrows, OK and Back. */
internal fun Modifier.appMove(pkg: String): Modifier = composed {
    val session = AppMove.session
    val scope = LocalAppMoveScope.current
    if (session == null || session.pkg != pkg || scope == null) return@composed this
    SideEffect { session.attach(scope) }
    val draft = session.draft ?: return@composed this
    val focus = remember { FocusRequester() }
    val index = draft.index
    LaunchedEffect(index) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    BackHandler { session.cancel() }
    val h = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    jiggle(true).focusRequester(focus)
        .semantics { stateDescription = "Moving, place ${index + 1} of ${draft.order.size}. Arrows move, OK places, Back cancels"; liveRegion = LiveRegionMode.Polite }
        .onPreviewKeyEvent { e ->
            when (e.key) {
                Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                    if (e.type == KeyEventType.KeyDown) when (e.key) {
                        Key.DirectionLeft -> session.step(-h, 0)
                        Key.DirectionRight -> session.step(h, 0)
                        Key.DirectionUp -> session.step(0, -1)
                        else -> session.step(0, 1)
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (e.type == KeyEventType.KeyDown) session.armed = true
                    else if (e.type == KeyEventType.KeyUp && session.armed) session.place()
                    true
                }
                else -> false
            }
        }
}
