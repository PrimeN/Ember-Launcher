package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.player.DeviceTuning

/**
 * #474: "Set up for this TV": the box's name and what it is, then one line per player setting Ember
 * chose (a tick) or leaves to each video (a circle of arrows). Continue, or review them in Settings.
 */
@Composable
internal fun TuningCard(plan: DeviceTuning.Plan, close: () -> Unit, review: () -> Unit) {
    val go = remember { FocusRequester() }
    TvDialog(close, width = 820.dp, height = 640.dp, scrollContent = true) {
        Text("SET UP FOR THIS TV", color = Mute, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
        Text(plan.device.ifBlank { "This TV" }, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        if (plan.summary.isNotBlank()) Text(plan.summary, color = Mute, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        val rows = plan.rows.ifEmpty { plan.lines.map { l -> l.substringBefore(": ").let { DeviceTuning.Row(it, l.substringAfter(": ")) } } }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().background(Wash, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp).testTag("tuning_row"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(if (r.set) Icons.Outlined.Check else Icons.Outlined.Autorenew, null, Modifier.size(18.dp),
                        tint = if (r.set) MaterialTheme.colorScheme.primary else Mute)
                    Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(r.value, color = Mute, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 430.dp))
                }
            }
        }
        Text("Ember chose these from this box and screen. Change any of them in ${SettingsTab.PLAYER.path}.", color = Mute, fontSize = 13.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("Continue", modifier = Modifier.focusRequester(go).testTag("tuning_continue"), onClick = close)
            Chip("Review in Settings", modifier = Modifier.testTag("tuning_review")) { review() }
        }
    }
    LaunchedEffect(Unit) { runCatching { go.requestFocus() } }
}
