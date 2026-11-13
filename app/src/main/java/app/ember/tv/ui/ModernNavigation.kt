package app.ember.tv.ui

import app.ember.tv.data.TileLabels

import app.ember.tv.isSaved
import app.ember.tv.resumeLabel
import app.ember.tv.toggleWatchlist
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search as IcSearch
import androidx.compose.material.icons.outlined.Settings as IcSettings
import androidx.compose.material.icons.outlined.Home as IcHome
import androidx.compose.material.icons.outlined.BookmarkBorder as IcBookmarkBorder
import androidx.compose.material.icons.outlined.LiveTv as IcLiveTv
import androidx.compose.material.icons.outlined.Explore as IcExplore
import androidx.compose.material.icons.outlined.VideoLibrary as IcVideoLibrary
import androidx.compose.material.icons.outlined.Dns as IcDns
import androidx.compose.material.icons.outlined.Apps as IcApps
import androidx.compose.material.icons.outlined.Tune as IcTune
import androidx.compose.material.icons.outlined.Circle as IcCircle
import androidx.compose.material.icons.outlined.Wallpaper as IcScreensaver
import androidx.compose.material.icons.outlined.Person as IcProfile

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.ember.tv.data.resumeLabel
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import app.ember.tv.data.HeroBanner
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.AppEntry
import app.ember.tv.data.Meta
import app.ember.tv.data.mediaKey
import app.ember.tv.data.NavTabs
import app.ember.tv.iptv.Channel
import app.ember.tv.iptv.IptvController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Profile, a shared navigation capsule, two quick actions, then branding and live status. */
@Composable
fun ModernTopBar(
    tabs: List<String>,
    tab: String,
    onTab: (String) -> Unit,
    weather: String?,
    profile: app.ember.tv.data.Profile? = null,
    openOnFocus: Boolean = true,
    searchFocus: androidx.compose.ui.focus.FocusRequester? = null,
    onProfile: () -> Unit = {},
) {
    val sceneFocus = LocalNavigationFocus.current
    val time=rememberLauncherTime()
    // Search leads the capsule; other tabs retain the user's saved order.
    val navigation = tabs.filter { it == NavTabs.SEARCH } +
        tabs.filter { it != NavTabs.SEARCH && it != NavTabs.SETTINGS }
    val activeTab = navigationTabForPage(tab, navigation)
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 900.dp
        val gap = if (compact) 8.dp else 12.dp
        val capsule = Modifier.navigationGlass()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = if (compact) 24.dp else 48.dp,
                end = if (compact) 24.dp else 40.dp, top = 16.dp, bottom = 12.dp)
                .navigationFocusGroup { runCatching { sceneFocus?.tab(activeTab)?.requestFocus() == true }.getOrDefault(false) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (profile != null) {
                ProfileMenuButton(profile, size = 42.dp,
                    modifier = Modifier.semantics { contentDescription = "Profile: ${profile.name}" },
                    onFallback = onProfile)
            } else {
                NavigationAction("Profile", Icons.Outlined.IcProfile, onProfile)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically) {
                // Only the tabs scroll. Their pill wraps content until the status and
                // action buttons leave insufficient room, then becomes a bounded viewport.
                FrostedPill(Modifier.weight(1f, fill = false)) {
                Row(
                    modifier = Modifier.then(capsule)
                        .clip(CircleShape).horizontalScroll(rememberScrollState()).padding(6.dp).focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navigation.forEachIndexed { index, id -> key(id) {
                        val selected = activeTab == id
                        Focusable(
                            onClick = { onTab(id) },
                            modifier = Modifier.height(42.dp)
                                .let { m -> if (sceneFocus != null) m.navigationTabTarget(sceneFocus, id) else searchFocus?.takeIf { id == NavTabs.SEARCH }?.let { m.focusRequester(it) } ?: m }
                                .let { m -> if (sceneFocus != null) m.focusProperties {
                                    // Explicit neighbors avoid scroll-group geometry restarting at Search.
                                    left = navigation.getOrNull(index+choiceStep(right=false,rtl=rtl))?.let(sceneFocus::tab) ?: androidx.compose.ui.focus.FocusRequester.Default
                                    right = navigation.getOrNull(index+choiceStep(right=true,rtl=rtl))?.let(sceneFocus::tab) ?: androidx.compose.ui.focus.FocusRequester.Default
                                    if (openOnFocus) down = sceneFocus.content(tab)
                                } else m }
                                .semantics { this.selected = selected; role = Role.Tab }
                                .openNavigationTab(id, selected, enabled = openOnFocus) { onTab(id) },
                            radius = TvTokens.Shape.Pill,
                            container = if (selected) WashHi else Color.Transparent,
                            focusedContainer = PillColor,
                            focusedContent = OnPillColor,
                            outline = false,
                            focusedScale = TvTokens.Focus.Inline,
                        ) {
                            if (id == NavTabs.SEARCH) {
                                Box(Modifier.size(42.dp).semantics { contentDescription = "Search" }, contentAlignment = Alignment.Center) {
                                    SearchGlyph(androidx.tv.material3.LocalContentColor.current)
                                }
                            } else {
                                // Surface places bare content at the top. Match the icon
                                // tabs' explicit height and center the label within it.
                                Box(Modifier.height(42.dp).padding(horizontal = if (compact) 14.dp else 18.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text(tabTitle(id),
                                        fontSize = TvTokens.Type.Navigation.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = Color.Unspecified, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    } }
                }
                }
                FrostedPill {
                Row(capsule.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    NavigationAction(stringResource(R.string.ui_screensaver_now), Icons.Outlined.IcScreensaver) { onTab(NavTabs.SCREENSAVER) }
                    NavigationAction(stringResource(R.string.ui_quick_settings), Icons.Outlined.IcSettings) { onTab(NavTabs.QUICK) }
                }
                }
            }
            Column(Modifier.widthIn(max = if (compact) 150.dp else 210.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmberLogo(height = if (compact) 22.dp else 26.dp, tint = Fg)
                    Text(time, fontSize = if (compact) 14.sp else 16.sp, color = Fg, maxLines = 1, softWrap = false)
                }
                weather?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
/** Icon-only action with a spoken label; it never activates just because focus passes over it. */
@Composable
private fun NavigationAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick,
        modifier = Modifier.size(42.dp).semantics { contentDescription = label }
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Pill, container = Color.Transparent, focusedContainer = PillColor,
        focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MaterialGlyph(icon, if (focused) OnPillColor else Fg, 22.dp)
        }
    }
}
/** Quick settings, shown as your profile's avatar when there are profiles (the panel lists them at the top). */
@Composable
internal fun QuickButton(profile: app.ember.tv.data.Profile?, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    if (profile != null) ProfileAvatar(profile, size, modifier, onClick)
    else RoundIcon(selected = selected, onClick = onClick, modifier = modifier) { c -> NavGlyph(NavTabs.QUICK, c) }
}
/** A round button with a drawn icon; the icon turns dark when the button is focused. */
@Composable
internal fun RoundIcon(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        modifier = modifier.size(42.dp).onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Pill,
        container = if (selected) WashHi else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.Round,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { glyph(if (focused) OnPillColor else Fg) }
    }
}
@Composable
internal fun SearchGlyph(color: Color) = MaterialGlyph(Icons.Outlined.IcSearch, color)
@Composable
internal fun GearGlyph(color: Color) = MaterialGlyph(Icons.Outlined.IcSettings, color)


// ---------------------------------------------------------------- Backdrop
/**
 * The modern menu down the left side (Settings > Appearance > Menu: Side): round icons, opening
 * into icon-and-name rows over a dark fade while the remote is on them. The wordmark sits at the
 * top and the clock at the bottom.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ModernSideRail(
    tabs: List<String>,
    tab: String,
    onTab: (String) -> Unit,
    weather: String?,
    modifier: Modifier = Modifier,
    profile: app.ember.tv.data.Profile? = null,
    onProfile: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val time=rememberLauncherTime()
    val width by androidx.compose.animation.core.animateDpAsState(if (expanded) 260.dp else 84.dp,
        animationSpec = tween(PolishMotion.duration(TvTokens.Motion.Rail,LocalRenderOptions.current,LocalReduceMotion.current)), label = "rail")
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(0f to Ink.copy(alpha = 0.95f), 0.8f to Ink.copy(alpha = 0.85f), 1f to Ink.copy(alpha = 0.5f))
                else Brush.horizontalGradient(0f to Ink.copy(alpha = 0.6f), 1f to Color.Transparent),
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusRestorer(),
    ) {
        Column(Modifier.fillMaxHeight().padding(start = 20.dp, top = 26.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
                    if (profile != null) ProfileMenuButton(profile, onFallback = onProfile) else EmberMark(32.dp)
                }
                if (expanded) EmberWordmark(19.dp, Fg)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                // Settings is the first entry inside Quick settings.
                (tabs.filter { it != NavTabs.SETTINGS } + NavTabs.QUICK).forEach { id -> RailItem(id, tab, id == tab || (id == NavTabs.QUICK && tab == NavTabs.SETTINGS), expanded) { onTab(id) } }
            }
            Text(time, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fg, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (expanded && weather != null) Text(weather, fontSize = 13.sp, color = Mute, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(end = 20.dp))
        }
    }
}
@Composable
private fun RailItem(id: String, contentId: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val scene = LocalNavigationFocus.current
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        // Expanded, the pill follows the rail as it widens instead of jumping to its full width.
        modifier = (if (expanded) Modifier.fillMaxWidth().padding(end = 24.dp).height(44.dp) else Modifier.size(44.dp))
            .let { m -> if (scene != null) m.navigationTabTarget(scene,id).focusProperties { if(rtl)left=scene.content(contentId) else right=scene.content(contentId) } else m }
            .semantics { this.selected = selected; role = Role.Tab }
            .openNavigationTab(id, selected, open = onClick)
            .onFocusChanged { focused = it.isFocused || it.hasFocus },
        radius = TvTokens.Shape.Pill,
        container = if (selected) WashHi else Color.Transparent,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.Control,
    ) {
        Row(Modifier.fillMaxSize().padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { NavGlyph(id, if (focused) OnPillColor else Fg) }
            if (expanded) Text(tabTitle(id), fontSize = TvTokens.Type.Navigation.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
        }
    }
}
/** Small drawn icons for the menu entries. */
@Composable
fun NavGlyph(id: String, color: Color, size: Dp = 20.dp) {
    MaterialGlyph(
        when (id) {
            NavTabs.HOME -> Icons.Outlined.IcHome
            NavTabs.WATCHLIST -> Icons.Outlined.IcBookmarkBorder
            NavTabs.LIVE -> Icons.Outlined.IcLiveTv
            NavTabs.DISCOVER -> Icons.Outlined.IcExplore
            NavTabs.LIBRARY -> Icons.Outlined.IcVideoLibrary
            NavTabs.APPS -> Icons.Outlined.IcApps
            NavTabs.SEARCH -> Icons.Outlined.IcSearch
            NavTabs.SETTINGS -> Icons.Outlined.IcSettings
            NavTabs.QUICK -> Icons.Outlined.IcTune
            else -> Icons.Outlined.IcCircle
        },
        color, size,
    )
}
