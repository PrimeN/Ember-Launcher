package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Text
import app.ember.tv.data.Profile

internal class ProfileMenuConfig(
    val profiles: List<Profile>,
    val actions: List<ProfileMenuAction>,
    val onOpenChanged: (Boolean) -> Unit,
    val onSwitch: (String) -> Unit,
    val onAction: (ProfileMenuAction) -> Unit,
)

internal val LocalProfileMenu = compositionLocalOf<ProfileMenuConfig?> { null }

/** Popup belongs to its avatar, so details, top navigation and side navigation share one menu. */
@Composable
internal fun ProfileMenuButton(profile: Profile, modifier: Modifier = Modifier, size: Dp = 42.dp,
    onFallback: () -> Unit = {}) {
    val menu = LocalProfileMenu.current
    val currentMenu by rememberUpdatedState(menu)
    var open by remember(profile.id) { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val anchorFocus = remember { FocusRequester() }
    val profileFocus = remember(profile.id) { FocusRequester() }
    val density = LocalDensity.current
    val position = remember(density) { with(density) { ProfileMenuPosition(12.dp.roundToPx(), 16.dp.roundToPx()) } }
    val windowSize = LocalWindowInfo.current.containerSize
    val availableWidth = with(density) { (windowSize.width - 32.dp.roundToPx()).coerceAtLeast(1).toDp() }
    // As wide as the widest part: the avatar row (each profile and Add), the longest action or the
    // header lines, plus the panel's padding. Measured, so it fits any language or text size.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val currentLine = profile.name + if (profile.kids) " · Kids" else " · Current profile"
    val menuWidth = remember(menu?.profiles?.size, menu?.actions, currentLine, density, availableWidth, measurer) {
        with(density) {
            fun width(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) =
                measurer.measure(text, androidx.compose.ui.text.TextStyle(fontSize = size, fontWeight = weight), softWrap = false, maxLines = 1).size.width.toDp()
            val avatars = MenuSizing.profileAvatarStripWidth(menu?.profiles?.size ?: 1)
            val actions = menu?.actions?.maxOfOrNull { width(it.label, 13.sp, FontWeight.Medium) + 46.dp } ?: 0.dp
            val header = maxOf(width("Who's watching?", 13.sp, FontWeight.SemiBold), width(currentLine, 11.sp, FontWeight.Normal).coerceAtMost(160.dp))
            MenuSizing.profileMenuWidth(maxOf(avatars, actions, header) + 20.dp + 8.dp, availableWidth, fontScale)
        }
    }
    var anchorTop by remember { mutableIntStateOf(0) }
    var anchorBottom by remember { mutableIntStateOf(0) }
    val availableHeight = with(density) {
        val space = maxOf(windowSize.height - anchorBottom - 28.dp.roundToPx(), anchorTop - 28.dp.roundToPx())
        space.coerceAtLeast(1).toDp()
    }
    fun close(returnFocus: Boolean) {
        restore = returnFocus
        open = false
        currentMenu?.onOpenChanged?.invoke(false)
    }
    DisposableEffect(Unit) { onDispose { currentMenu?.onOpenChanged?.invoke(false) } }
    LaunchedEffect(open) {
        if (!open && restore) {
            restore = false
            withFrameNanos { }
            runCatching { anchorFocus.requestFocus() }
        }
    }
    Box(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        anchorTop = bounds.top.toInt()
        anchorBottom = bounds.bottom.toInt()
    }) {
        ProfileAvatar(profile, size, Modifier.focusRequester(anchorFocus).semantics {
            contentDescription = "Profile: ${profile.name}. Open profile menu"
            stateDescription = if (open) "Expanded" else "Collapsed"
        }) {
            if (menu == null) onFallback() else {
                open = true
                menu.onOpenChanged(true)
            }
        }
        if (open && menu != null) {
            Popup(popupPositionProvider = position, onDismissRequest = { close(true) },
                properties = PopupProperties(focusable = true)) {
                // Popup controls must not replace the underlying page's remembered focus target.
                CompositionLocalProvider(LocalFocusScene provides null, LocalFloating provides true) {
                    Column(Modifier.guardOpeningPress().width(menuWidth).sceneEnter(profile.id,panel=true)
                        .heightIn(max = availableHeight)
                        .floatingPanelSurface()
                        .verticalScroll(rememberMenuScrollState()).padding(10.dp).focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Who's watching?", color = Fg, fontSize = TvTokens.Type.Compact.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberMenuScrollState()).padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                            menu.profiles.forEach { p -> key(p.id) {
                                val active = p.id == profile.id
                                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(Modifier.border(if (active) 2.dp else 0.dp,
                                        if (active) Fg else Color.Transparent, CircleShape).padding(4.dp)) {
                                        ProfileAvatar(p, 36.dp,
                                            (if (active) Modifier.focusRequester(profileFocus) else Modifier).semantics {
                                                contentDescription = p.name + if (p.kids) ", Kids profile" else ", Profile"
                                                selected = active
                                            }) {
                                            close(p.id == profile.id)
                                            if (!active) menu.onSwitch(p.id)
                                        }
                                    }
                                    Text(p.name, color = Fg, fontSize = 11.sp, maxLines = 1, softWrap = false,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            } }
                            Column(Modifier.width(48.dp).padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Focusable(onClick = { close(false); menu.onAction(ProfileMenuAction.PROFILES) },
                                    modifier = Modifier.size(36.dp).semantics { contentDescription = "Add or manage profiles" },
                                    radius = 50, container = WashHi, focusedContainer = PillColor, focusedContent=OnPillColor, focusedScale = 1.05f) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        MaterialGlyph(Icons.Outlined.PersonAdd, androidx.tv.material3.LocalContentColor.current)
                                    }
                                }
                                Text("Add", color = Fg, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(currentLine, color = Mute, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        menu.actions.forEach { action -> key(action) {
                            ProfileMenuRow(action) { close(false); menu.onAction(action) }
                        } }
                    }
                }
                LaunchedEffect(profile.id) {
                    withFrameNanos { }
                    runCatching { profileFocus.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuRow(action: ProfileMenuAction, onClick: () -> Unit) {
    val icon = when (action) {
        ProfileMenuAction.WATCHLIST -> Icons.Outlined.BookmarkBorder
        ProfileMenuAction.CONTINUE -> Icons.Outlined.PlayCircleOutline
        ProfileMenuAction.RECENT -> Icons.Outlined.History
        ProfileMenuAction.LIBRARY -> Icons.Outlined.VideoLibrary
        ProfileMenuAction.PROFILES -> Icons.Outlined.ManageAccounts
    }
    MenuActionRow(action.label, icon = icon, onClick = onClick)
}
