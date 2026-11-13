package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.discover.ExploreKind
import app.ember.tv.discover.ExploreQuery
import app.ember.tv.discover.ExploreSort

/**
 * #244: Discover's controls, redrawn: the title type as one segmented switch, Sort and Filters as
 * labelled pills, the active filters as tags to remove in one press, and sorting as three described
 * choices instead of a plain list.
 */
internal object DiscoverLook {
    /** What each sort does, under its name. */
    fun sortNote(sort: ExploreSort): String = when (sort) {
        ExploreSort.POPULAR -> "What people are watching now"
        ExploreSort.RATING -> "Best audience scores, with enough votes to count"
        ExploreSort.NEWEST -> "Latest releases first"
    }

    fun sortIcon(sort: ExploreSort): ImageVector = when (sort) {
        ExploreSort.POPULAR -> Icons.Outlined.Whatshot
        ExploreSort.RATING -> Icons.Outlined.StarOutline
        ExploreSort.NEWEST -> Icons.Outlined.NewReleases
    }

    /** "Filters", or "Filters · 3" with how many are on. */
    fun filtersLabel(active: Int) = if (active == 0) "Filters" else "Filters · $active"
}

/** All, Movies, Shows as one switch; the chosen one is filled. */
@Composable
internal fun KindSegments(selected: ExploreKind, modifier: Modifier = Modifier, first: FocusRequester? = null, onPick: (ExploreKind) -> Unit) {
    Row(modifier.background(Wash, RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ExploreKind.entries.forEachIndexed { i, kind ->
            val on = kind == selected
            Focusable(onClick = { if (!on) onPick(kind) },
                modifier = (if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier).semantics { this.selected = on }.testTag("kind_${kind.name}"),
                radius = TvTokens.Shape.Pill, container = if (on) MaterialTheme.colorScheme.primary else Wash,
                focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control) {
                Text(kind.label, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current)
            }
        }
    }
}

/** A toolbar button with its icon, its name and what it is set to. */
@Composable
internal fun ToolbarPill(label: String, value: String?, icon: ImageVector, modifier: Modifier = Modifier, active: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.semantics { if (active) this.selected = true }, radius = TvTokens.Shape.Pill,
        container = if (active) WashHi else Wash, focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control) {
        Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialGlyph(icon, LocalContentColor.current, 18.dp)
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            value?.let { Text(it, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
        }
    }
}

/** The filters in use, each a tag that removes itself; Clear all ends the row. */
@Composable
internal fun ActiveFilterTags(query: ExploreQuery, modifier: Modifier = Modifier, onApply: (ExploreQuery) -> Unit) {
    val tags = exploreTags(query)
    if (tags.isEmpty()) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
        items(tags, key = { it.first }) { (label, without) ->
            Focusable(onClick = { onApply(without) }, radius = TvTokens.Shape.Pill, container = WashHi, focusedContainer = PillColor, focusedContent = OnPillColor,
                outline = false, focusedScale = TvTokens.Focus.Control) {
                Row(Modifier.padding(start = 12.dp, end = 10.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    MaterialGlyph(Icons.Outlined.Close, LocalContentColor.current.copy(alpha = .7f), 14.dp)
                }
            }
        }
        if (tags.size > 1) item(key = "clear") {
            Focusable(onClick = { onApply(ExploreQuery(sort = query.sort)) }, radius = TvTokens.Shape.Pill, container = Wash,
                focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Control) {
                Text("Clear all", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = .8f))
            }
        }
    }
}

/** Sorting as three cards, each with what it does. */
@Composable
internal fun SortSheet(current: ExploreSort, onPick: (ExploreSort) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(onClose, width = 760.dp, height = 300.dp) {
        DialogHeading("Sort by")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
            ExploreSort.entries.forEach { sort ->
                val on = sort == current
                val accent = MaterialTheme.colorScheme.primary
                Focusable(onClick = { onPick(sort); onClose() },
                    modifier = Modifier.weight(1f).height(150.dp).then(if (on) Modifier.focusRequester(first) else Modifier)
                        .then(if (on) Modifier.border(2.dp, accent, RoundedCornerShape(TvTokens.Shape.Card.dp)) else Modifier)
                        .semantics { this.selected = on }.testTag("sort_${sort.name}"),
                    radius = TvTokens.Shape.Card, container = if (on) WashHi else Wash, focusedContainer = PillColor, focusedContent = OnPillColor, focusedScale = TvTokens.Focus.Card) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(36.dp).background(if (on) accent.copy(alpha = .22f) else LocalContentColor.current.copy(alpha = .1f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                                MaterialGlyph(DiscoverLook.sortIcon(sort), if (on) accent else LocalContentColor.current, 20.dp)
                            }
                            if (on) MaterialGlyph(Icons.Outlined.Check, accent, 18.dp)
                        }
                        Text(sort.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(DiscoverLook.sortNote(sort), fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = .72f), maxLines = 2)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
