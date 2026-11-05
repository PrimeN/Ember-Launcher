package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Compact actions share focus colours, accessible names and a delayed visual hint. */
@Composable
internal fun ToolbarIcon(label: String, icon: ImageVector, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        RoundIcon(selected = selected, onClick = onClick, modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label; if (selected) this.selected = true }) { color -> MaterialGlyph(icon, color) }
        ToolbarHint(label, focused)
    }
}

/** Hints stay within the window and flip above controls near its bottom edge. */
internal class ToolbarHintPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable
internal fun ToolbarHint(label: String, focused: Boolean) {
    var visible by remember { mutableStateOf(false) }
    val active = focused && LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(label, active) { visible = false; if (active) { delay(450); visible = true } }
    if (visible && active) {
        val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
        Popup(popupPositionProvider = remember(gap) { ToolbarHintPosition(gap) },
            properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Text(label, color = Fg, fontSize = TvTokens.Type.Caption.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp).clearAndSetSemantics { }
                    .background(PanelHi, RoundedCornerShape(10.dp)).border(1.dp, Fg.copy(alpha = .14f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** Menu choices retain words: icons identify actions, while labels explain their destinations. */
internal class MenuOption(val label: String, val selected: Boolean = false, val icon: ImageVector? = null, val onPick: () -> Unit)

/** Back/Close dismiss; focus always starts on the first choice (the selected one stays ticked), with a close fallback for empty menus. */
@Composable
internal fun OptionMenu(title: String, options: List<MenuOption>, close: () -> Unit) {
    CompactActionMenu(title, options.map {it.label}, close) { first ->
        options.forEachIndexed { i, option ->
            MenuActionRow(option.label, if(i==0)Modifier.focusRequester(first) else Modifier,
                option.icon ?: if(option.selected)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                selected=option.selected) {close();option.onPick()}
        }
    }
}

@Composable
internal fun RestoreToolbarFocus(open: Boolean, focus: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (!open && wasOpen) { withFrameNanos { }; runCatching { focus.requestFocus() } }
        wasOpen = open
    }
}
