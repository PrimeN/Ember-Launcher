package app.ember.tv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.iptv.LiveViews

@Composable
internal fun LiveGlassControl(label: String, icon: ImageVector?, modifier: Modifier = Modifier,
    selected: Boolean = false, dropdown: Boolean = false, iconOnly: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick, modifier.widthIn(max = 260.dp).onFocusChanged { focused = it.isFocused }.semantics {
        contentDescription = label; this.selected = selected
    }, radius = TvTokens.Shape.Pill,
        container = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .32f) else Color.Transparent,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.glassSurface(RoundedCornerShape(TvTokens.Shape.Pill.dp), focused = focused)
            .heightIn(min = 38.dp).padding(horizontal = if (iconOnly) 10.dp else 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = if (focused) OnPillColor else Fg
            if (icon != null) MaterialGlyph(icon, color, 18.dp)
            if (!iconOnly) Text(label, Modifier.weight(1f, fill = false), color = color, fontSize = TvTokens.Type.CompactBody.sp,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (dropdown) MaterialGlyph(Icons.Outlined.ExpandMore, color, 16.dp)
        }
    }
}

@Composable
internal fun LiveViewToolbar(source: String, scope: String, canvas: Boolean,
    searchActive: Boolean, sidebarOpen: Boolean, sourceFocus: FocusRequester, searchFocus: FocusRequester, scopeFocus: FocusRequester, moreFocus: FocusRequester,
    onSource: () -> Unit, onView: (String) -> Unit, onScope: () -> Unit, onSidebar: () -> Unit,
    onSearch: () -> Unit, onNow: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
    showViews: Boolean = true) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveGlassControl(source, null, modifier = Modifier.widthIn(max = 190.dp).focusRequester(sourceFocus).testTag("live_source"), dropdown = true, onClick = onSource)
        if (showViews) {
            LiveGlassControl("Guide", Icons.Outlined.GridOn, Modifier.testTag("live_view_guide"), selected = !canvas) { onView(LiveViews.GUIDE) }
            LiveGlassControl("On now", Icons.Outlined.Dashboard, Modifier.testTag("live_view_canvas"), selected = canvas) { onView(LiveViews.CANVAS) }
        }
        if (canvas) LiveGlassControl(scope, null, modifier = Modifier.widthIn(max = 170.dp).focusRequester(scopeFocus).testTag("live_scope"), dropdown = true, onClick = onScope)
        else LiveGlassControl(if (sidebarOpen) "Hide categories" else "Show categories", Icons.Outlined.Menu,
            Modifier.testTag("live_sidebar"), iconOnly = true, selected = sidebarOpen, onClick = onSidebar)
        LiveGlassControl("Search channels", Icons.Outlined.Search, Modifier.focusRequester(searchFocus).testTag("live_search"),
            iconOnly = true, selected = searchActive, onClick = onSearch)
        if (!canvas && showViews) LiveGlassControl("Jump to now", Icons.Outlined.Schedule,
            Modifier.testTag("live_now"), iconOnly = true, onClick = onNow)
        LiveGlassControl("Live TV options", Icons.Outlined.MoreHoriz, Modifier.focusRequester(moreFocus).testTag("live_more"), iconOnly = true, onClick = onMore)
    }
}
