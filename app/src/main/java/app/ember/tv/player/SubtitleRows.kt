package app.ember.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text

/**
 * #474: one subtitle choice as the sheets show it: the name with small tags (SDH, FORCED, MATCH),
 * a second line with the format or the release, and where it comes from on the right.
 */
data class SubtitleRow(val title: String, val tags: List<String> = emptyList(), val detail: String? = null, val source: String? = null) {
    companion object {
        /** A short format name from a codec, MIME type or file link: SRT, ASS, PGS, VobSub, VTT… */
        fun formatOf(vararg hints: String?): String? = hints.firstNotNullOfOrNull { h ->
            val t = h?.lowercase() ?: return@firstNotNullOfOrNull null
            when {
                "subrip" in t || t.endsWith(".srt") || ".srt?" in t -> "SRT"
                "ssa" in t || ".ass" in t -> "ASS"
                "pgs" in t -> "PGS"
                "vobsub" in t -> "VobSub"
                "vtt" in t -> "WebVTT"
                "ttml" in t -> "TTML"
                "dvb" in t -> "DVB"
                "608" in t || "708" in t -> "Closed captions"
                "tx3g" in t -> "MP4 text"
                else -> null
            }
        }
    }
}

private val Ink = Color(0xFF1F1F1F)

@Composable
internal fun SubtitleChoiceRow(row: SubtitleRow, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val text = if (focused) Ink else Color.White
    val soft = if (focused) Ink.copy(alpha = .62f) else Color.White.copy(alpha = .6f)
    Row(
        Modifier.focusRequester(fr).fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) onClick()
                ok
            }
            .semantics { this.selected = selected }
            .focusable()
            .padding(horizontal = 18.dp, vertical = if (row.detail != null) 10.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.title, fontSize = 17.sp, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                row.tags.forEach { t ->
                    Text(t, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = soft, maxLines = 1,
                        modifier = Modifier.border(1.dp, soft, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            row.detail?.let { Text(it, fontSize = 13.sp, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        row.source?.let { Text(it, fontSize = 13.sp, color = soft, maxLines = 1) }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = text)
    }
}

/** The "Subtitle style" pill at the top of the subtitle sheets: size, colour, position and timing. */
@Composable
internal fun SubtitleStylePill(first: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val text = if (focused) Ink else Color.White
    Row(
        Modifier.focusRequester(fr).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else Color.White.copy(alpha = .1f))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) onClick()
                ok
            }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.TextFields, null, Modifier.size(20.dp), tint = text)
        Text("Subtitle style and timing", fontSize = 16.sp, color = text, maxLines = 1)
    }
}
