package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.iptv.LiveBanner
import coil.compose.AsyncImage

@Composable
fun LivePlaybackBanner(info: LiveBanner?) {
    if (info == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)
        .background(Color(0xDF17191E), RoundedCornerShape(16.dp)).padding(16.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        info.channel.logo?.let { AsyncImage(it, null, modifier = Modifier.size(64.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LIVE · " + (info.channel.number?.let { "$it · " } ?: "") + info.channel.name, fontSize = 20.sp, color = Color.White)
            Text(info.now?.let { it.title + " · " + timeOf(it.start) + "–" + timeOf(it.stop) } ?: "No current programme information", fontSize = 16.sp, color = Color.White)
            info.now?.let { programme ->
                val progress = ((info.clock - programme.start).toFloat() / (programme.stop - programme.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.Gray).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LiveRed))
                }
            }
            Text(info.source + (info.next?.let { " · Next ${timeOf(it.start)}: ${it.title}" } ?: "") + " · Guide opens channel guide", fontSize = 13.sp, color = Color(0xFFCDD1D8))
        }
    }
}
