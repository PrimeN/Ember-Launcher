package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.outlined.Info
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
            Chip(stringResource(R.string.st_50_ms_2)) { change(AudioTiming.adjust(value, -1)) }
            Chip(stringResource(R.string.st_50_ms)) { change(AudioTiming.adjust(value, 1)) }
            Chip(stringResource(R.string.pl_reset)) { change(0) }
        }
    }
}

/** The whole row is one labeled switch on every settings surface. */
@Composable
internal fun SettingToggle(label: String, note: String?, on: Boolean, unavailable: String? = null, onToggle: () -> Unit) {
    RecordSettingOption(label)
    var focused by remember { mutableStateOf(false) }
    val floating = LocalFloating.current
    val explain = rememberUnavailableNotice(unavailable)
    Focusable(onClick = { if (unavailable == null) onToggle() else explain() }, role = Role.Switch,
        modifier = Modifier.fillMaxWidth().semantics {
            toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            stateDescription = (if (on) "On" else "Off") + (unavailable?.let { ", not available: $it" } ?: "")
        }.onFocusChanged { focused = it.isFocused }.unavailableLook(unavailable), radius = TvTokens.Shape.Control,
        container = if (floating) Color.Transparent else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (floating) 46.dp else 52.dp)
            .padding(horizontal = TvTokens.Space.ButtonX.dp, vertical = if (floating) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = if (floating) TvTokens.Type.Label.sp else TvTokens.Type.SettingsLabel.sp)
                (unavailable ?: note)?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = if (focused) OnPillColor.copy(alpha = .75f) else Mute) }
            }
            Box(Modifier.size(44.dp, 24.dp).clip(RoundedCornerShape(50))
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Gray),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(3.dp).size(18.dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
    }
}

/**
 * A setting that opens a picker (#110): label on the left, the current choice on the right and an
 * arrow, in the same full-width row as [SettingToggle].
 */
@Composable
internal fun SettingValueRow(label: String, value: String, modifier: Modifier = Modifier, note: String? = null, unavailable: String? = null, onClick: () -> Unit) {
    RecordSettingOption(label)
    var focused by remember { mutableStateOf(false) }
    val floating = LocalFloating.current
    val explain = rememberUnavailableNotice(unavailable)
    Focusable(onClick = { if (unavailable == null) onClick() else explain() },
        modifier = modifier.fillMaxWidth().semantics { stateDescription = value + (unavailable?.let { ", not available: $it" } ?: "") }
            .onFocusChanged { focused = it.isFocused }.unavailableLook(unavailable),
        radius = TvTokens.Shape.Control,
        container = if (floating) Color.Transparent else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (floating) 46.dp else 52.dp)
            .padding(horizontal = TvTokens.Space.ButtonX.dp, vertical = if (floating) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = if (floating) TvTokens.Type.Label.sp else TvTokens.Type.SettingsLabel.sp)
                (unavailable ?: note)?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = if (focused) OnPillColor.copy(alpha = .75f) else Mute) }
            }
            Text(value, fontSize = TvTokens.Type.Label.sp, color = if (focused) OnPillColor.copy(alpha = .8f) else Mute,
                // #402: the label takes the free space, so the value and arrow sit at the right edge (two weights split it in half).
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.widthIn(max = 440.dp))
            androidx.compose.foundation.Image(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (focused) OnPillColor else Mute), modifier = Modifier.size(20.dp))
        }
    }
}

/** Reveals extra fields; this is a disclosure action, not a saved on/off preference. */
@Composable
internal fun SettingsDisclosure(label:String,expanded:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val state=androidx.compose.ui.res.stringResource(if(expanded)app.ember.tv.R.string.settings_expanded else app.ember.tv.R.string.settings_collapsed)
    Focusable(onClick=onClick,modifier=modifier.widthIn(max=360.dp).fillMaxWidth().semantics {stateDescription=state},
        radius=TvTokens.Shape.Pill,container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=TvTokens.Focus.None) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp).padding(horizontal=TvTokens.Space.ButtonX.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(label,fontSize=TvTokens.Type.Label.sp,modifier=Modifier.weight(1f))
            androidx.tv.material3.Icon(if(expanded)androidx.compose.material.icons.Icons.Outlined.ExpandLess else
                androidx.compose.material.icons.Icons.Outlined.ExpandMore,null,modifier=Modifier.size(22.dp))
        }
    }
}

/**
 * A one-press action as a full-width settings row (#386), lined up with the toggles and choices around
 * it so the d-pad always reaches it.
 */
@Composable
internal fun SettingActionRow(label: String, note: String? = null, modifier: Modifier = Modifier, unavailable: String? = null, onClick: () -> Unit) {
    RecordSettingOption(label)
    var focused by remember { mutableStateOf(false) }
    val floating = LocalFloating.current
    val explain = rememberUnavailableNotice(unavailable)
    Focusable(onClick = { if (unavailable == null) onClick() else explain() },
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.unavailableLook(unavailable),
        radius = TvTokens.Shape.Control,
        container = if (floating) Color.Transparent else Wash,
        focusedContainer = PillColor,
        focusedContent = OnPillColor,
        outline = false, focusedScale = TvTokens.Focus.None) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (floating) 46.dp else 52.dp)
            .padding(horizontal = TvTokens.Space.ButtonX.dp, vertical = if (floating) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = if (floating) TvTokens.Type.Label.sp else TvTokens.Type.SettingsLabel.sp)
                (unavailable ?: note)?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = if (focused) OnPillColor.copy(alpha = .75f) else Mute) }
            }
        }
    }
}

/**
 * #418: a setting that can't apply right now stays where it is, dimmed, with the reason in place
 * of its note. It still takes focus (so the page doesn't jump) and pressing it repeats the reason.
 */
internal fun Modifier.unavailableLook(reason: String?): Modifier = if (reason == null) this else this.then(Modifier.alpha(0.45f))

@Composable
internal fun rememberUnavailableNotice(reason: String?): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(reason) { { if (reason != null) android.widget.Toast.makeText(context, reason, android.widget.Toast.LENGTH_SHORT).show() } }
}

/** A small ⓘ beside a section title (#388): the longer explanation opens in a popup instead of filling the page. */
@Composable
internal fun SettingInfoButton(title: String, info: String) {
    var open by remember { mutableStateOf(false) }
    val anchor = remember { androidx.compose.ui.focus.FocusRequester() }
    ToolbarIcon("About $title", androidx.compose.material.icons.Icons.Outlined.Info,
        modifier = Modifier.focusRequester(anchor).size(34.dp)) { open = true }
    if (open) SettingInfoDialog(title, info) { open = false }
    RestoreToolbarFocus(open, anchor)
}

@Composable
internal fun SettingInfoDialog(title: String, info: String, close: () -> Unit) {
    val ok = remember { androidx.compose.ui.focus.FocusRequester() }
    TvDialog(close, width = 600.dp, scrollContent = true) {
        DialogHeading(title)
        info.split("\n\n").filter { it.isNotBlank() }.forEach {
            Text(it.trim(), color = Fg, fontSize = TvTokens.Type.Label.sp, lineHeight = 22.sp)
        }
        Chip("Got it", modifier = Modifier.focusRequester(ok)) { close() }
    }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { ok.requestFocus() } }
}
