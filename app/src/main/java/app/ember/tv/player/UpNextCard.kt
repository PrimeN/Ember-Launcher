package app.ember.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** What the Up next card shows (#225); the player fills it in and counts down. */
class UpNextUi {
    var visible by mutableStateOf(false)
    var series by mutableStateOf("")
    var season by mutableIntStateOf(0)
    var episode by mutableIntStateOf(0)
    var title by mutableStateOf("")
    var thumbnail by mutableStateOf<String?>(null)
    var rating by mutableStateOf<String?>(null)
    var seconds by mutableIntStateOf(0)
    /** Looking for a source for the next episode: the card stays, the countdown gives way to this. */
    var finding by mutableStateOf(false)

    companion object {
        /** "S1:E4 Malware", or just "S1:E4" when the episode has no name. */
        fun line(season: Int, episode: Int, title: String): String = "S$season:E$episode" + title.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
        fun heading(seconds: Int, finding: Boolean): String = if (finding) "FINDING A SOURCE…" else "UP NEXT IN ${seconds.coerceAtLeast(0)}"
    }
}

/**
 * The Up next card (#225), in the bottom-right corner as streaming apps show it: the countdown
 * above, then the next episode's picture with a play mark, its show, "S1:E4 Title" and age rating.
 * It is focused at once; OK plays the episode now. Back is the player's own (it stops).
 */
@Composable
internal fun UpNextCard(ui: UpNextUi, onPlay: () -> Unit) {
    if (!ui.visible) return
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(ui.visible) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(end = 40.dp, bottom = 40.dp), contentAlignment = Alignment.BottomEnd) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(UpNextUi.heading(ui.seconds, ui.finding), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 10.dp))
            val line = UpNextUi.line(ui.season, ui.episode, ui.title)
            Row(
                Modifier.width(560.dp).height(150.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xD9505257))
                    .border(if (focused) 4.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = .25f), RoundedCornerShape(18.dp))
                    .padding(10.dp)
                    .focusRequester(focus).onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        if (ok && e.type == KeyEventType.KeyUp && !ui.finding) onPlay()
                        ok
                    }
                    .focusable()
                    .semantics { contentDescription = "Up next: ${ui.series}, $line"; onClick { onPlay(); true } }
                    .testTag("up_next_card"),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2D33)), contentAlignment = Alignment.Center) {
                    ui.thumbnail?.let { coil.compose.AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    // The play-next mark: a white ring with a skip glyph.
                    Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = .88f)), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("▶", fontSize = 16.sp, color = Color(0xFF1F1F1F))
                            Box(Modifier.width(3.dp).height(14.dp).background(Color(0xFF1F1F1F)))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ui.series.isNotBlank()) Text(ui.series, fontSize = 16.sp, color = Color(0xFFE2E4E8), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(line, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ui.rating?.takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                            modifier = Modifier.border(1.5.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
            }
        }
    }
}
