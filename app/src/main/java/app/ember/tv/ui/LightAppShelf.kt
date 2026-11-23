package app.ember.tv.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.light.ShelfCard

/**
 * Light's App rows (#62): the focused app's own rows ("Continue watching", then each of its
 * channels such as "Recently added"), above the dock, like a top shelf. One horizontal line of
 * cards; each group's name sits over its first card.
 */
@Composable
internal fun LightAppShelf(groups: List<Pair<String, List<ShelfCard>>>, first: FocusRequester, modifier: Modifier = Modifier,
    onFocused: (ShelfCard?) -> Unit = {}, onOpen: (ShelfCard) -> Unit) {
    val cards = LightShelfCards.flatten(groups)
    // Coming back up from the dock lands on the card last focused here (#135).
    LazyRow(modifier.fillMaxWidth().focusRequester(first).focusRestorer().onFocusChanged { if (!it.hasFocus) onFocused(null) }, contentPadding = PaddingValues(horizontal = 45.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(cards, key = { it.second.id }) { (header, item) ->
            Column(Modifier.width(232.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(header.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.height(26.dp))
                Focusable(onClick = { onOpen(item) },
                    modifier = Modifier.fillMaxWidth().height(131.dp).semantics { contentDescription = item.title }
                        .onFocusChanged { if (it.isFocused) onFocused(item) },
                    radius = TvTokens.Shape.Card, focusedScale = TvTokens.Focus.Card) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(TvTokens.Shape.Card.dp)).background(Color(0x33FFFFFF))) {
                        if (item.artwork != null) coil.compose.AsyncImage(item.artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(item.title, color = Color.White, fontSize = 15.sp, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(12.dp))
                        item.progress?.let { p ->
                            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))) {
                                Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(4.dp).background(Color.White))
                            }
                        }
                    }
                }
                Text(item.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) Text(item.subtitle, color = Color(0xFFD8DEF3), fontSize = TvTokens.Type.Caption.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Spacer(Modifier.height(14.dp))
            }
        }
    }
}

internal object LightShelfCards {
    /**
     * One card per title across the app's groups: a server's Continue watching and Recently added
     * often share an episode, and each card's id keys the row, so it may appear only once.
     * The first group keeps it; a group left empty is dropped.
     */
    fun flatten(groups: List<Pair<String, List<ShelfCard>>>): List<Pair<String?, ShelfCard>> {
        val seen = HashSet<String>()
        return groups.flatMap { (name, items) ->
            items.filter { seen.add(it.id) }.mapIndexed { i, item -> (if (i == 0) name else null) to item }
        }
    }
}
