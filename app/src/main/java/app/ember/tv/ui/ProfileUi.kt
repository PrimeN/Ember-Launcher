package app.ember.tv.ui

import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import kotlinx.coroutines.delay

/** "Who's watching?": the profiles as round avatars; Manage opens Settings > Profiles. */
@Composable
fun ProfilePicker(vm: HomeViewModel, onManage: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog({vm.profiles.showProfilePicker=false},scrollContent=true,horizontalAlignment=Alignment.CenterHorizontally) {
        DialogHeading(stringResource(R.string.ui_who_s_watching))
        ButtonRow(spacing = 22.dp) {
            vm.profiles.all.forEach { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(if (p.id == vm.profiles.profileId) Modifier.focusRequester(first) else Modifier) {
                        ProfileAvatar(p, 84.dp) { vm.profiles.switchProfile(p.id) }
                    }
                    Text(p.name + if (p.kids) " · Kids" else "", fontSize = 15.sp, fontWeight = if (p.id == vm.profiles.profileId) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Chip(stringResource(R.string.ui_manage_profiles)) {
            vm.profiles.showProfilePicker = false
            onManage()
        }

    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}

/**
 * Four-digit PIN pad for the remote. [onDone] gets the digits; it returns false for a wrong PIN,
 * which clears the pad and says so.
 */
@Composable
fun PinPad(title: String, onDone: (String) -> Boolean, onCancel: () -> Unit, vm: HomeViewModel? = null) {
    var digits by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    TvDialog(onCancel,width=380.dp,scrollContent=true,horizontalAlignment=Alignment.CenterHorizontally) {
        DialogHeading(title)
        ButtonRow(spacing = 14.dp) {
            repeat(4) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape)
                        .background(if (i < digits.length) MaterialTheme.colorScheme.primary else Fg.copy(alpha = 0.25f)),
                )
            }
        }
        Text(
            when {
                vm != null && vm.pin.pinWait > 0 -> "Too many tries. Wait ${vm.pin.pinWait} s."
                wrong -> "Wrong PIN. Try again."
                else -> " "
            },
            color = LiveRed,
            fontSize = 13.sp,
        )
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕"))
        keys.forEachIndexed { r, row ->
            Row(Modifier.widthIn(max=252.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { c, k ->
                    Box(Modifier.weight(1f).then(if(r==0 && c==0)Modifier.focusRequester(first) else Modifier)) {
                        Chip(k, modifier = Modifier.fillMaxWidth()) {
                            when (k) {
                                "⌫" -> digits = digits.dropLast(1)
                                "✕" -> onCancel()
                                else -> if (digits.length < 4) {
                                    digits += k
                                    wrong = false
                                    if (digits.length == 4) {
                                        val ok = onDone(digits)
                                        if (!ok) {
                                            wrong = true
                                            digits = ""
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}

/** Every choice previews the actual avatar colour; only activation changes the saved selection. */
@Composable
internal fun ProfileColorPicker(selected:Int,onSelect:(Int)->Unit) {
    val names=stringArrayResource(R.array.profile_color_names)
    val colors=app.ember.tv.data.Profile.COLORS
    val current=selected.takeIf {it in colors.indices} ?: 0
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.profile_color_current,names[current]),fontSize=TvTokens.Type.CompactBody.sp,color=Mute)
        ButtonRow(Modifier.selectableGroup(),spacing=10.dp) {
            colors.forEachIndexed {index,value->
                val color=Color(value)
                val label=names[index]
                var focused by remember {mutableStateOf(false)}
                Box(contentAlignment=Alignment.Center) {
                    Focusable(onClick={onSelect(index)},modifier=Modifier.size(44.dp)
                        .onFocusChanged {focused=it.isFocused}.semantics {
                            contentDescription=label
                            this.selected=index==current
                        },role=Role.RadioButton,radius=TvTokens.Shape.Pill,
                        container=color,focusedContainer=color,focusedContent=readablePillText(color),focusedScale=TvTokens.Focus.Round) {
                        Box(Modifier.size(44.dp),contentAlignment=Alignment.Center) {
                            if(index==current)MaterialGlyph(Icons.Outlined.Check,readablePillText(color),22.dp)
                        }
                    }
                    ToolbarHint(label,focused)
                }
            }
        }
    }
}
