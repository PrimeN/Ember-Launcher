package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Settings pages that list services (API keys #214, media servers #220, trackers #221) as a grid of
 * tiles: a status mark, the name, a tag and a short line. OK on a tile opens its details.
 */
enum class ServiceMark { OK, PROBLEM, OFF, ADD }

data class ServiceTileInfo(val id: String, val name: String, val mark: ServiceMark, val tag: String? = null, val line: String? = null, val tagStrong: Boolean = false)

internal object ServiceTiles {
    const val PER_ROW = 4
    /** What a screen reader says for a tile. */
    fun spoken(t: ServiceTileInfo): String = listOfNotNull(t.name, t.tag, t.line ?: when (t.mark) {
        ServiceMark.OK -> "ready"; ServiceMark.PROBLEM -> "needs attention"; ServiceMark.OFF -> "not set up"; ServiceMark.ADD -> null
    }).joinToString(", ")
}

@Composable
internal fun ServiceGrid(tiles: List<ServiceTileInfo>, onOpen: (ServiceTileInfo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(ServiceTiles.PER_ROW).forEach { line ->
            Row(Modifier.width(960.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                line.forEach { t -> ServiceTile(t, Modifier.weight(1f)) { onOpen(t) } }
                repeat(ServiceTiles.PER_ROW - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun ServiceTile(t: ServiceTileInfo, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.testTag("service_tile").semantics { contentDescription = ServiceTiles.spoken(t) },
        radius = TvTokens.Shape.Card, container = PanelHi) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ServiceStatusMark(t.mark)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (t.mark == ServiceMark.OFF) ink.copy(alpha = .6f) else ink)
                t.tag?.let { ServiceTag(it, t.tagStrong) }
                t.line?.let { Text(it, fontSize = 12.sp, color = ink.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Green tick when ready; red cross when it needs attention; grey dash when it isn't set up; a plus to add one. */
@Composable
internal fun ServiceStatusMark(mark: ServiceMark) {
    val (fill, glyph) = when (mark) {
        ServiceMark.OK -> Color(0xFF2E9E5B) to "✓"
        ServiceMark.PROBLEM -> Color(0xFFD64545) to "✕"
        ServiceMark.OFF -> Color(0xFF6B7078) to "–"
        ServiceMark.ADD -> Color(0xFF4A5060) to "+"
    }
    Box(Modifier.size(26.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
internal fun ServiceTag(text: String, strong: Boolean) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = if (strong) Color(0xFF1F1F1F) else Fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (strong) Color(0xFFF2C14E) else Fg.copy(alpha = .14f)).padding(horizontal = 7.dp, vertical = 2.dp))
}
