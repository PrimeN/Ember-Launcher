package app.ember.tv.ui

import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.data.AudioTiming

@Composable
internal fun AudioTimingControls(value: Int, modifier: Modifier = Modifier, change: (Int) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AudioTiming.label(value), fontSize = 16.sp)
        ButtonRow {
            Chip("−50 ms") { change(AudioTiming.adjust(value, -1)) }
            Chip("+50 ms") { change(AudioTiming.adjust(value, 1)) }
            Chip("Reset") { change(0) }
        }
    }
}

/** The whole row is one labeled switch on every settings surface. */
@Composable
internal fun SettingToggle(label: String, note: String?, on: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val floating = LocalFloating.current
    Focusable(onClick = onToggle, role = Role.Switch,
        modifier = Modifier.fillMaxWidth().semantics {
            toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            stateDescription = if (on) "On" else "Off"
        }.onFocusChanged { focused = it.isFocused }, radius = TvTokens.Shape.Control,
        container = if (floating) Color.Transparent else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false, focusedScale = 1f) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (floating) 46.dp else 52.dp)
            .padding(horizontal = TvTokens.Space.ButtonX.dp, vertical = if (floating) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = if (floating) TvTokens.Type.Label.sp else TvTokens.Type.SettingsLabel.sp)
                note?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = if (focused) OnPillColor.copy(alpha = .75f) else Mute) }
            }
            Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(50))
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Gray),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(3.dp).size(18.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
    }
}

/** Reveals extra fields; this is a disclosure action, not a saved on/off preference. */
@Composable
internal fun SettingsDisclosure(label:String,expanded:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val state=androidx.compose.ui.res.stringResource(if(expanded)app.ember.tv.R.string.settings_expanded else app.ember.tv.R.string.settings_collapsed)
    Focusable(onClick=onClick,modifier=modifier.widthIn(max=360.dp).fillMaxWidth().semantics {stateDescription=state},
        radius=TvTokens.Shape.Pill,container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=1f) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).padding(horizontal=TvTokens.Space.ButtonX.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(label,fontSize=TvTokens.Type.Label.sp,modifier=Modifier.weight(1f))
            androidx.tv.material3.Icon(if(expanded)androidx.compose.material.icons.Icons.Outlined.ExpandLess else
                androidx.compose.material.icons.Icons.Outlined.ExpandMore,null,modifier=Modifier.size(22.dp))
        }
    }
}
