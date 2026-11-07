package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable
internal fun BadgePreview(mode: String, label: String, hiddenCategories: Set<String> = emptySet(), close: () -> Unit) {
    val context = LocalContext.current
    var badges by remember(mode) { mutableStateOf<List<BadgeFilter>?>(null) }
    var failed by remember(mode) { mutableStateOf(false) }
    LaunchedEffect(context, mode, Badges.rev, hiddenCategories) {
        try { badges = app.ember.tv.data.DetailedBadgeCategories.filter(mode, hiddenCategories, Badges.preview(context, mode)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true; badges = emptyList() }
    }
    MediaDialog("$label badges", close) {
        Text("Preview only. Your selected badge option stays unchanged.", color = Mute, fontSize = 13.sp)
        when {
            badges == null -> Note("Loading badges…")
            failed -> Note("This badge pack could not be read. Reload it in badge settings.")
            badges!!.isEmpty() -> Note(if (mode == Badges.MODERN) "All Detailed badge categories are off. Turn one on in badge settings."
                else "No custom pack has been loaded. Import a badge pack to preview its styles and included badges.")
            else -> {
                Note(if (mode == Badges.LEGACY) "Examples of Basic text badges. Available labels depend on each source's formats and languages."
                    else "${badges!!.size} included badges. Images and text use the same styling as your media sources.")
                LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxWidth().height(minOf(320, ((badges!!.size + 2) / 3) * 72).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(badges!!) { _, badge ->
                        var focused by remember(badge) { mutableStateOf(false) }
                        val shape = RoundedCornerShape(8.dp)
                        Column(Modifier.onFocusChanged { focused = it.isFocused }
                            .background(if (focused) WashHi else Color.Transparent, shape)
                            .then(if (focused) Modifier.border(1.dp, PillColor, shape) else Modifier)
                            .semantics { contentDescription = badge.name + if (badge.isEnabled) "" else ", disabled" }
                            .focusable().padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            SourceBadge(badge)
                            Text(badge.name + if (badge.isEnabled) "" else " (disabled)", color = Mute, fontSize = app.ember.tv.ui.TvTokens.Type.Caption.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
