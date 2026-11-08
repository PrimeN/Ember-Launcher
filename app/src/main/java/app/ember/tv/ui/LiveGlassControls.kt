package app.ember.tv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.ember.tv.iptv.LiveViews

@Composable
internal fun LiveViewToolbar(source: String, scope: String, canvas: Boolean,
    searchActive: Boolean, sidebarOpen: Boolean, sourceFocus: FocusRequester, searchFocus: FocusRequester, scopeFocus: FocusRequester, moreFocus: FocusRequester,
    onSource: () -> Unit, onView: (String) -> Unit, onScope: () -> Unit, onSidebar: () -> Unit,
    onSearch: () -> Unit, onNow: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
    showViews: Boolean = true) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassControl(source, null, modifier = Modifier.widthIn(max = 190.dp).focusRequester(sourceFocus).testTag("live_source"), dropdown = true, onClick = onSource)
        if (showViews) {
            GlassControl("Guide", Icons.Outlined.GridOn, Modifier.testTag("live_view_guide"), selected = !canvas) { onView(LiveViews.GUIDE) }
            GlassControl("On now", Icons.Outlined.Dashboard, Modifier.testTag("live_view_canvas"), selected = canvas) { onView(LiveViews.CANVAS) }
        }
        if (canvas) GlassControl(scope, null, modifier = Modifier.widthIn(max = 170.dp).focusRequester(scopeFocus).testTag("live_scope"), dropdown = true, onClick = onScope)
        else GlassControl(if (sidebarOpen) "Hide categories" else "Show categories", Icons.Outlined.Menu,
            Modifier.testTag("live_sidebar"), iconOnly = true, selected = sidebarOpen, onClick = onSidebar)
        GlassControl("Search channels", Icons.Outlined.Search, Modifier.focusRequester(searchFocus).testTag("live_search"),
            iconOnly = true, selected = searchActive, onClick = onSearch)
        if (!canvas && showViews) GlassControl("Jump to now", Icons.Outlined.Schedule,
            Modifier.testTag("live_now"), iconOnly = true, onClick = onNow)
        GlassControl("Live TV options", Icons.Outlined.MoreHoriz, Modifier.focusRequester(moreFocus).testTag("live_more"), iconOnly = true, onClick = onMore)
    }
}
