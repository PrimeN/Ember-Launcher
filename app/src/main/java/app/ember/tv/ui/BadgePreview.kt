package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.BadgeFilter
import app.ember.tv.data.Badges
import kotlinx.coroutines.CancellationException

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BadgePreview(mode: String, label: String, hiddenCategories: Set<String> = emptySet(),
    /** #194: badges switched off singly, and the switch; null where a pack has nothing to switch (Basic text). */
    off: Set<String> = emptySet(), onToggle: ((BadgeFilter) -> Unit)? = null,
    /** #397/#398: Detailed only: the categories' order, moving one, and switching one off. */
    order: List<String> = emptyList(), onMoveCategory: ((List<BadgeFilter>, String, Int) -> Unit)? = null,
    onToggleCategory: ((String) -> Unit)? = null, close: () -> Unit) {
    val switchable = onToggle != null && mode != Badges.LEGACY
    val grouped = mode == Badges.MODERN && onToggleCategory != null
    val context = LocalContext.current
    var badges by remember(mode) { mutableStateOf<List<BadgeFilter>?>(null) }
    var failed by remember(mode) { mutableStateOf(false) }
    LaunchedEffect(context, mode, Badges.rev, hiddenCategories, grouped) {
        // Grouped, every category is listed (an off one says so); otherwise off categories are left out.
        try { badges = Badges.preview(context, mode).let { if (grouped) it else app.ember.tv.data.DetailedBadgeCategories.filter(mode, hiddenCategories, it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; badges = emptyList() }
    }
    MediaDialog(if (grouped) "Advanced badge settings" else "$label badges", close, wide = grouped) {
        Text(when {
            grouped -> "Categories show under each source in this order. Move a category up to show it first, switch a whole category off, or press OK on a badge to turn just that one off."
            switchable -> "Your selected badge option stays unchanged. Press OK on a badge to turn it off or on for your sources."
            else -> "Preview only. Your selected badge option stays unchanged."
        }, color = Mute, fontSize = 13.sp)
        val list = badges
        when {
            list == null -> Note("Loading badges…")
            failed -> Note("This badge pack could not be read. Reload it in badge settings.")
            list.isEmpty() -> Note(if (mode == Badges.MODERN) "All Detailed badge categories are off. Turn one on in badge settings."
                else "No custom pack has been loaded. Import a badge pack to preview its styles and included badges.")
            grouped -> {
                val categories = app.ember.tv.data.DetailedBadgeCategories.order(order, list)
                categories.forEachIndexed { index, id ->
                    val name = app.ember.tv.data.DetailedBadgeCategories.name(id)
                    val members = list.filter { it.groupId == id }
                    val on = id !in hiddenCategories
                    Column(Modifier.fillMaxWidth().background(PanelHi, RoundedCornerShape(TvTokens.Shape.Card.dp)).padding(12.dp)
                        .testTag("badge_category_$id"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. $name", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text("${members.size} ${if (members.size == 1) "badge" else "badges"}${if (on) "" else " · hidden"}", color = Mute, fontSize = 12.sp)
                            }
                            if (index > 0) ToolbarIcon("Move $name earlier", Icons.Outlined.ArrowUpward) { onMoveCategory?.invoke(list, id, -1) }
                            if (index < categories.lastIndex) ToolbarIcon("Move $name later", Icons.Outlined.ArrowDownward) { onMoveCategory?.invoke(list, id, 1) }
                            SwitchChip(if (on) "Shown" else "Hidden", on, Modifier.testTag("badge_category_switch_$id")) { onToggleCategory?.invoke(id) }
                        }
                        if (on) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            members.forEach { BadgeTile(it, mode, off, switchable, onToggle) }
                        }
                    }
                }
            }
            else -> {
                Note(if (mode == Badges.LEGACY) "Examples of Basic text badges. Available labels depend on each source's formats and languages."
                    else "${list.size} included badges. Images and text use the same styling as your media sources.")
                LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxWidth().height(minOf(320, ((list.size + 2) / 3) * 72).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(list) { _, badge -> BadgeTile(badge, mode, off, switchable, onToggle) }
                }
            }
        }
    }
}

@Composable
private fun BadgeTile(badge: BadgeFilter, mode: String, off: Set<String>, switchable: Boolean, onToggle: ((BadgeFilter) -> Unit)?) {
    var focused by remember(badge) { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val isOff = switchable && app.ember.tv.data.BadgeChoices.key(mode, badge) in off
    val state = if (!badge.isEnabled) "disabled" else if (isOff) "off" else null
    Column(Modifier.widthIn(min = 120.dp).onFocusChanged { focused = it.isFocused }
        .background(if (focused) WashHi else Color.Transparent, shape)
        .then(if (focused) Modifier.border(1.dp, PillColor, shape) else Modifier)
        .semantics { contentDescription = badge.name + (state?.let { ", $it" } ?: "") }
        .then(if (switchable && badge.isEnabled) Modifier.clickable(remember(badge) { MutableInteractionSource() }, indication = null,
            onClickLabel = if (isOff) "Turn on" else "Turn off") { onToggle?.invoke(badge) } else Modifier.focusable())
        .padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.alpha(if (isOff) .3f else 1f)) { SourceBadge(badge) }
        Text(badge.name + (state?.let { " ($it)" } ?: ""), color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2)
    }
}
