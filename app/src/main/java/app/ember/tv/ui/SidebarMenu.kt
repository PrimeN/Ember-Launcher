package app.ember.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.ember.tv.data.NavTabs
import app.ember.tv.data.NavbarStyles
import app.ember.tv.data.Profile
import kotlinx.coroutines.launch

/** Space the Sidebar keeps for itself; the opened panel is drawn over the page. */
internal val SidebarRailWidth = 84.dp

/**
 * The Sidebar navbar (#369): round icons down the left side that open into a matte panel of rows
 * (the profile, the pages and Quick settings) while the remote is on them. Moving over a row only
 * moves focus; OK opens it and returns to the page. Right and Back return to the page; Left from
 * the page's edge comes back to the current page's row.
 */
@Composable
internal fun SidebarMenu(tabs: List<String>, current: String, onTab: (String) -> Unit, weather: String?, profile: Profile, onProfile: () -> Unit) {
    val owner = LocalNavigationFocus.current
    val active = navigationTabForPage(current, tabs)
    var hasFocus by remember { mutableStateOf(false) }
    var profileOpen by remember(profile.id) { mutableStateOf(false) }
    val expanded = hasFocus || profileOpen
    val scope = rememberCoroutineScope()
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val motion = PolishMotion.duration(TvTokens.Motion.Rail, LocalRenderOptions.current, LocalReduceMotion.current)
    val panel by animateDpAsState(if (expanded) 268.dp else SidebarRailWidth, tween(motion), label = "sidebar")
    val entries = (NavbarStyles.entries(tabs) + NavTabs.QUICK)
    val profileRow = remember { FocusRequester() }
    fun toPage(page: String) {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { owner?.content(page)?.requestFocus() }
        }
    }
    BackHandler(enabled = hasFocus && !profileOpen) { toPage(current) }
    Box(
        Modifier.fillMaxHeight().width(panel).testTag("sidebar")
            .background(if (expanded) Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent))
                else Brush.horizontalGradient(listOf(Ink.copy(alpha = .6f), Color.Transparent)))
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { if (!profileOpen && isBackKey(it)) { if (it.type == KeyEventType.KeyUp) toPage(current); true } else false }
            // Arriving from the page always lands on the current page's row, whichever row is nearest.
            .focusProperties { onEnter = { if (owner != null && requestedFocusDirection != androidx.compose.ui.focus.FocusDirection.Enter) runCatching { owner.tab(active).requestFocus() } } }
            .focusGroup(),
    ) {
        Column(
            Modifier.fillMaxHeight().width(if (expanded) 256.dp else SidebarRailWidth)
                .padding(if (expanded) PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp) else PaddingValues(0.dp))
                .then(if (expanded) Modifier.matteMenuSurface(RoundedCornerShape(22.dp)).testTag("sidebar_open") else Modifier)
                .padding(horizontal = if (expanded) 10.dp else 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProfileMenuButton(profile, if (expanded) Modifier.fillMaxWidth() else Modifier, size = if (expanded) 34.dp else 44.dp,
                onFallback = onProfile, row = expanded,
                anchorModifier = Modifier.testTag("sidebar_profile").focusRequester(profileRow).focusProperties {
                    if (owner != null) {
                        down = owner.tab(entries.first())
                        if (rtl) left = owner.content(current) else right = owner.content(current)
                    }
                }, onOpenChanged = { profileOpen = it })
            if (expanded) Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).height(1.dp).background(Fg.copy(alpha = .12f)))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                entries.forEachIndexed { index, id ->
                    key(id) {
                        SidebarRow(id, id == active || (id == NavTabs.QUICK && current == NavTabs.SETTINGS), expanded,
                            Modifier.then(if (owner != null) Modifier.navigationTabTarget(owner, id).focusProperties {
                                up = entries.getOrNull(index - 1)?.let(owner::tab) ?: profileRow
                                down = entries.getOrNull(index + 1)?.let(owner::tab) ?: FocusRequester.Cancel
                                if (rtl) left = owner.content(current) else right = owner.content(current)
                            } else Modifier)) {
                            onTab(id)
                            if (id != NavTabs.QUICK) toPage(id)
                        }
                    }
                }
            }
            Text(rememberLauncherTime(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fg, style = OverArt, maxLines = 1,
                modifier = if (expanded) Modifier.padding(start = 12.dp) else Modifier.width(44.dp),
                textAlign = if (expanded) null else androidx.compose.ui.text.style.TextAlign.Center)
            if (expanded && !weather.isNullOrBlank()) Text(weather, fontSize = 13.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun SidebarRow(id: String, selected: Boolean, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val title = tabTitle(id)
    Focusable(
        onClick = onClick,
        modifier = (if (expanded) modifier.fillMaxWidth().height(48.dp) else modifier.size(44.dp)).testTag("sidebar_tab_$id")
            .semantics { this.selected = selected; role = Role.Tab; contentDescription = title },
        radius = if (expanded) TvTokens.Shape.Card else TvTokens.Shape.Pill,
        container = if (selected) Fg.copy(alpha = .14f) else Color.Transparent,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false,
        focusedScale = if (expanded) TvTokens.Focus.None else TvTokens.Focus.Control,
    ) {
        Row(Modifier.fillMaxSize().padding(start = if (expanded) 14.dp else 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { NavGlyph(id, LocalContentColor.current) }
            if (expanded) Text(title, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}
