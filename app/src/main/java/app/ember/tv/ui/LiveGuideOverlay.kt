package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.iptv.Channel
import app.ember.tv.iptv.Programme
import coil.compose.AsyncImage

/** #434: the channel list over the video; [selected] is the highlighted row, [watching] the channel playing. */
data class LiveGuideState(val channels: List<Channel>, val selected: Int, val watching: String, val clock: Long) {
    fun moved(by: Int): LiveGuideState =
        if (channels.isEmpty()) this else copy(selected = Math.floorMod(selected + by, channels.size), clock = System.currentTimeMillis())
    companion object { const val PAGE = 8 }
}

@Composable
fun LiveGuideOverlay(state: LiveGuideState, programmes: (Channel, Long) -> Pair<Programme?, Programme?>, source: (Channel) -> String) {
    val list = rememberLazyListState(maxOf(0, state.selected - 3))
    LaunchedEffect(state.selected) {
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == state.selected } || state.selected <= (visible.firstOrNull()?.index ?: 0) || state.selected >= (visible.lastOrNull()?.index ?: 0))
            list.scrollToItem(maxOf(0, state.selected - 3))
    }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0101114), 0.55f to Color(0xC0101114), 0.75f to Color.Transparent))) {
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.52f).padding(start = 40.dp, top = 32.dp, bottom = 24.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Channels", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text("${state.selected + 1} of ${state.channels.size}", fontSize = 14.sp, color = Color(0xFFB7BCC4))
                Spacer(Modifier.weight(1f))
                Text(timeOf(state.clock), fontSize = 16.sp, color = Color(0xFFE8EAED))
            }
            LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(state.channels, key = { _, c -> c.key }) { i, c ->
                    GuideRow(c, programmes(c, state.clock), state.clock, i == state.selected, c.key == state.watching, source(c))
                }
            }
            Text("OK watch · ▲▼ move · CH+/− page · ◀ recent · Back close", fontSize = 13.sp, color = Color(0xFFB7BCC4))
        }
    }
}

@Composable
private fun GuideRow(c: Channel, onAir: Pair<Programme?, Programme?>, clock: Long, selected: Boolean, watching: Boolean, source: String) {
    val (now, next) = onAir
    val ink = if (selected) Color(0xFF111215) else Color.White
    val soft = if (selected) Color(0xFF3B3E45) else Color(0xFFB7BCC4)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(TvTokens.Shape.Card.dp))
        .background(if (selected) Color(0xFFF1F3F4) else Color(0x33FFFFFF)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(c.number?.toString() ?: "", fontSize = 15.sp, color = soft, modifier = Modifier.width(44.dp), maxLines = 1)
        Box(Modifier.size(width = 64.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            c.logo?.let { AsyncImage(it, null, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (watching) Text("Watching", fontSize = 12.sp, color = Color.White, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(TvTokens.Shape.Tight.dp)).background(LiveRed).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            Text(now?.let { "${timeOf(it.start)}–${timeOf(it.stop)}  ${it.title}" } ?: source, fontSize = 14.sp, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            now?.let { p ->
                val progress = ((clock - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(TvTokens.Shape.Tight.dp)).background(if (selected) Color(0x33000000) else Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(if (selected) MaterialTheme.colorScheme.primary else LiveRed))
                }
            }
            if (selected) next?.let { Text("Next ${timeOf(it.start)}  ${it.title}", fontSize = 13.sp, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
