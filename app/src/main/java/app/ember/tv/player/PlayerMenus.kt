package app.ember.tv.player

import app.ember.tv.ui.TvTokens

import app.ember.tv.ui.guardOpeningPress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Videocam
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * One line of the player's menus: a name and its current value, with either a list of choices
 * (opened on OK) or an [action]. PlayerActivity builds them fresh each time a menu is read.
 */
class OptionRow(
    val title: String,
    val value: String = "",
    val choices: List<String> = emptyList(),
    val selected: Int = -1,
    val icon: ImageVector? = null,
    /** A short line under the choices (e.g. why a setting has no effect right now). */
    val note: String? = null,
    val onPick: (Int) -> Unit = {},
    val action: (() -> Unit)? = null,
    /** An on/off setting (#105): shown as a switch that flips on OK, like Settings. Use with [action]. */
    val toggle: Boolean? = null,
)

private val MenuPanel:Color
    @Composable get()=if(app.ember.tv.ui.isEmberX)app.ember.tv.ui.EmberXColors.Surface.copy(alpha=.96f) else app.ember.tv.ui.PanelHi.copy(alpha=.72f)
private val Divider = Color(0x33FFFFFF)

private fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter

/** The rows of a menu, read again whenever [ControlsState.optionsTick] changes. */
@Composable
private fun rows(s: ControlsState, a: ControlsActions, which: String): List<OptionRow> {
    val tick = s.optionsTick
    return remember(tick, which) { a.options(which) }
}

/** Whether this row should take focus: the first row when a menu opens, or the row whose choices just closed. */
private fun wantsFocus(s: ControlsState, i: Int, r: OptionRow) = (s.menuFocus == "" && i == 0) || s.menuFocus == r.title

@Composable
private fun TakeFocus(s: ControlsState, fr: FocusRequester, focus: Boolean) {
    LaunchedEffect(focus) {
        if (focus) {
            runCatching { fr.requestFocus() }
            s.menuFocus = null
        }
    }
}

/** Opens a row: its choices, or its action. */
private fun open(s: ControlsState, row: OptionRow) {
    val act = row.action
    if (act != null) {
        act()
    } else if (row.choices.isNotEmpty()) {
        // Back from the choices returns to this row.
        s.menuFocus = row.title
        s.menuRow = row.title
    }
}

// ---- the small menu, bottom right ----

@Composable
fun QuickMenu(s: ControlsState, a: ControlsActions) {
    val list = rows(s, a, "quick")
    val openRow = list.firstOrNull { it.title == s.menuRow }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize().guardOpeningPress().background(Color(0x40000000)), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn()) {
        Column(
            Modifier.padding(end = 28.dp, bottom = 28.dp).width(PlayerMenuLayout.QUICK_WIDTH.dp).heightIn(max = 340.dp)
                .clip(RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 20).dp)).background(MenuPanel)
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 20).dp))
                .padding(vertical = 6.dp),
        ) {
            if (openRow == null) {
                LazyColumn {
                    itemsIndexed(list) { i, r ->
                        MenuLine(s, r.icon, r.title, r.value, chevron = r.action == null, focus = wantsFocus(s, i, r), toggle = r.toggle) { open(s, r) }
                    }
                }
            } else {
                Choices(openRow, compact = true) { s.menuRow = null; s.optionsTick++ }
            }
        }
        }
    }
}

/** A row of the small menu: icon, name, value in grey, and a chevron. */
@Composable
private fun MenuLine(s: ControlsState, icon: ImageVector?, title: String, value: String, chevron: Boolean, focus: Boolean, toggle: Boolean? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    TakeFocus(s, fr, focus)
    val fg = if (focused) Color(0xFF1F1F1F) else Color.White
    Row(
        Modifier.focusRequester(fr).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 18).dp))
            .background(if (focused) PillFocus else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e -> if (isOk(e.key) && e.type == KeyEventType.KeyUp) onClick(); isOk(e.key) }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Image(icon, null, colorFilter = ColorFilter.tint(fg), modifier = Modifier.size(18.dp))
        Text(title, fontSize = 13.sp, color = fg, maxLines = 1, modifier = Modifier.weight(1f))
        if (toggle != null) MenuSwitch(toggle, focused)
        else if (value.isNotBlank()) Text(value, fontSize = 12.sp, color = if (focused) Color(0xFF555555) else Soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.End)
        if (chevron) Image(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, colorFilter = ColorFilter.tint(fg), modifier = Modifier.size(18.dp))
    }
}

/** The choices of one option: the current one ticked, focus on the first choice. */
@Composable
private fun Choices(row: OptionRow, compact: Boolean, done: () -> Unit) {
    val start = 0
    val state = rememberLazyListState()
    Column {
        Row(
            Modifier.padding(horizontal = if (compact) 28.dp else 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(Icons.AutoMirrored.Outlined.ArrowBack, null, colorFilter = ColorFilter.tint(Soft), modifier = Modifier.size(20.dp))
            Text(row.title, fontSize = 13.sp, color = Soft, fontWeight = FontWeight.SemiBold)
        }
        row.note?.let { Text(it, fontSize = 14.sp, color = Soft, modifier = Modifier.padding(horizontal = if (compact) 28.dp else 16.dp, vertical = 4.dp)) }
        LazyColumn(state = state) {
            itemsIndexed(row.choices) { i, label ->
                ChoiceLine(label, chosen = i == row.selected, first = i == start, compact = compact) {
                    row.onPick(i)
                    done()
                }
            }
        }
    }
}

@Composable
private fun ChoiceLine(label: String, chosen: Boolean, first: Boolean, compact: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val fg = if (focused) Color(0xFF1F1F1F) else Color.White
    Row(
        Modifier.focusRequester(fr).fillMaxWidth().padding(horizontal = if (compact) 10.dp else 0.dp).clip(RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 16).dp))
            // As in Ember's menus: focus is a white pill, the chosen one is tinted with the accent.
            .background(if (focused) PillFocus else if (chosen) LocalPlayerAccent.current.copy(alpha = 0.3f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e -> if (isOk(e.key) && e.type == KeyEventType.KeyUp) onClick(); isOk(e.key) }
            .focusable()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (chosen) Image(Icons.Outlined.Check, null, colorFilter = ColorFilter.tint(if (focused) Color(0xFF1F1F1F) else LocalPlayerAccent.current), modifier = Modifier.size(18.dp))
    }
}

// ---- the options panel: Video, Audio, Subtitles, sliding in on the right ----

private val Tabs = listOf(
    Triple("playback", "Play", Icons.Outlined.Speed),
    Triple("video", "Picture", Icons.Outlined.Videocam),
    Triple("audio", "Audio", Icons.AutoMirrored.Outlined.VolumeUp),
    Triple("subtitles", "Subtitles", Icons.Outlined.Subtitles),
)

@Composable
fun OptionsPage(s: ControlsState, a: ControlsActions) {
    val list = rows(s, a, s.menuTab)
    val openRow = list.firstOrNull { it.title == s.menuRow }
    // Slides in from the right; the video stays in view on the left.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        Modifier.fillMaxSize().guardOpeningPress().background(Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0x99000000))),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedVisibility(shown, enter = slideInHorizontally { it / 2 } + fadeIn()) {
            Column(
                Modifier.padding(24.dp).width(PlayerMenuLayout.PANEL_WIDTH.dp).fillMaxHeight(.72f)
                    .clip(RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 20).dp)).background(MenuPanel)
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 20).dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                TabBar(s, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                if (openRow != null) {
                    Spacer(Modifier.height(6.dp))
                    Choices(openRow, compact = false) { s.menuRow = null; s.optionsTick++ }
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        itemsIndexed(list, key = { _, r -> s.menuTab + r.title }) { i, r ->
                            OptionLine(s, r, focus = wantsFocus(s, i, r)) { open(s, r) }
                        }
                    }
                }
            }
        }
    }
}

/** A row of the options page: name on the left, value and chevron on the right, a line under it. */
@Composable
private fun OptionLine(s: ControlsState, r: OptionRow, focus: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    TakeFocus(s, fr, focus)
    val fg = if (focused) Color(0xFF1F1F1F) else Color.White
    Column {
        Row(
            Modifier.focusRequester(fr).fillMaxWidth().clip(RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 14).dp))
                .background(if (focused) PillFocus else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e -> if (isOk(e.key) && e.type == KeyEventType.KeyUp) onClick(); isOk(e.key) }
                .focusable()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(r.title, fontSize = 13.sp, color = fg, modifier = Modifier.weight(1f), maxLines = 1)
            if (r.toggle != null) MenuSwitch(r.toggle, focused)
            else if (r.value.isNotBlank()) Text(r.value, fontSize = 12.sp, color = if (focused) Color(0xFF555555) else Soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false))
            if (r.action == null) Image(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, colorFilter = ColorFilter.tint(if (focused) fg else Soft), modifier = Modifier.size(18.dp))
        }
        if (r.toggle != null) r.note?.let { Text(it, fontSize = 11.sp, color = Soft, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) }
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(Divider))
    }
}

/** The switch of an on/off row (#105), drawn like Settings' switches. */
@Composable
private fun MenuSwitch(on: Boolean, focused: Boolean) {
    val track = when { on && focused -> Color(0xFF1F1F1F); on -> LocalPlayerAccent.current; focused -> Color(0x591F1F1F); else -> Color(0x4DFFFFFF) }
    Box(Modifier.padding(start = 10.dp).width(34.dp).height(18.dp).clip(RoundedCornerShape(50)).background(track),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(2.dp).size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
    }
}

/**
 * Video / Audio / Subtitles: Left and Right along the tabs show each one, and OK on a tab shows it.
 * Focus that only passes over a tab (while a choice list opens or closes, or on the way back to a
 * row) never switches the page: that sent a subtitle or sound pick back to the first tab.
 */
@Composable
private fun TabBar(s: ControlsState, modifier: Modifier) {
    var moving by remember { mutableStateOf(false) }
    fun show(id: String) {
        if (s.menuTab == id) return
        s.menuFocus = null
        s.menuTab = id
        s.menuRow = null
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1C1C1E)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).padding(6.dp)
            .onPreviewKeyEvent { e ->
                // Only a Left or Right press inside the bar counts as moving along the tabs.
                // Focus moves on the press; the release ends it, so a press at the end of the bar
                // (no tab to move to) cannot leave a later focus slip switching the page.
                if (e.type == KeyEventType.KeyDown) moving = e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                else if (e.type == KeyEventType.KeyUp) moving = false
                false
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tabs.forEach { (id, label, icon) ->
            var focused by remember { mutableStateOf(false) }
            val on = s.menuTab == id
            val tint = when {
                focused -> Color(0xFF1F1F1F)
                on -> Color.White
                else -> Color.White
            }
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (focused) PillFocus else if (on) LocalPlayerAccent.current.copy(alpha = 0.34f) else Color.Transparent)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused && moving) { moving = false; show(id) }
                    }
                    .onKeyEvent { e -> if (isOk(e.key) && e.type == KeyEventType.KeyUp) show(id); isOk(e.key) }
                    .focusable()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(icon, null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(16.dp))
                Text(label, fontSize = TvTokens.Type.Caption.sp, color = tint)
            }
        }
    }
}

// ---- playback stats ----

/** Playback stats over the video, top left (Options > Video > Playback stats). */
@Composable
fun StatsHud(s: ControlsState) {
    if (s.stats.isEmpty()) return
    Column(
        Modifier.padding(start = 48.dp, top = if (s.visible) 112.dp else 32.dp)
            .clip(RoundedCornerShape((if(app.ember.tv.ui.isEmberX)8 else 16).dp)).background(Color(0xA6000000)).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        s.stats.forEach { (k, v) ->
            Row {
                Text(k, fontSize = 15.sp, color = Soft, modifier = Modifier.width(190.dp))
                Text(v, fontSize = 15.sp, color = Color.White, maxLines = 2)
            }
        }
    }
}

/** Icons for the small menu's rows. */
object MenuIcons {
    val boost = Icons.AutoMirrored.Outlined.VolumeUp
    val speed = Icons.Outlined.Speed
    val audio = Icons.Outlined.MusicNote
    val subtitles = Icons.Outlined.Subtitles
    val more = Icons.Outlined.Settings
}
