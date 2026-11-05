package app.ember.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.ui.Focusable
import coil.compose.AsyncImage

/**
 * Settings > Player > Player interface: Streaming. A layout in the style of streaming-service TV
 * players: plain transport icons top left (the focused one is named underneath), title and episode
 * top right, the title's logo on the left, then play, times and a thin progress bar, with the
 * Sound and Subtitles drop-ups and the menus underneath. Ember's accent colours the bar.
 * Every action is the same one the other layouts use.
 */
@Composable
internal fun StreamingControls(s: ControlsState, a: ControlsActions) {
    AnimatedVisibility(s.visible, enter = fadeIn(), exit = fadeOut()) {
        val play = remember { FocusRequester() }
        LaunchedEffect(s.shownAt) { runCatching { play.requestFocus() } }
        val d = s.details
        // The whole layout is drawn at 75% of the interface size; text keeps the user's text scale.
        val parent = androidx.compose.ui.platform.LocalDensity.current
        val density = remember(parent) { androidx.compose.ui.unit.Density(parent.density * SCALE, parent.fontScale) }
        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        Box(Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = .62f), .45f to Color.Transparent))
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .55f), .25f to Color.Transparent, .62f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f)))
            .onKeyEvent { a.touched(); false }) {

            // Top left: back, start over, next. The focused icon's name appears underneath.
            var label by remember { mutableStateOf<String?>(null) }
            Column(Modifier.padding(start = 56.dp, top = 40.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    StreamingIcon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", { label = it }, onClick = a.close)
                    StreamingIcon(Icons.Outlined.Replay, "Start over", { label = it }) { a.seekBy(-s.position) }
                    if (s.nextEpisodes.isNotEmpty() || s.fileQueueNext)
                        StreamingIcon(Icons.Outlined.SkipNext, if (s.fileQueueLabel != null) "Next file" else "Next episode", { label = it }, onClick = a.next)
                }
                Text(label?.uppercase() ?: "", fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, color = Color.White,
                    modifier = Modifier.padding(top = 10.dp).height(18.dp))
            }

            // Top right: title, then season, episode and episode name.
            Column(Modifier.align(Alignment.TopEnd).padding(end = 56.dp, top = 40.dp).widthIn(max = 520.dp), horizontalAlignment = Alignment.End) {
                Text(d?.name ?: s.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                val episode = listOfNotNull(s.seasonEpisode, d?.episodeTitle?.let { "“$it”" }).joinToString(" ")
                if (episode.isNotBlank()) Text(episode, fontSize = 17.sp, color = Color.White.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            }

            // Left, a little below the middle: the title's logo, or its name.
            val logo = d?.logo
            var logoOk by remember(logo) { mutableStateOf(logo != null) }
            Box(Modifier.align(Alignment.CenterStart).padding(start = 56.dp, top = 40.dp).width(320.dp).height(110.dp), contentAlignment = Alignment.BottomStart) {
                if (logo != null && logoOk) AsyncImage(logo, d.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.BottomStart, onError = { logoOk = false })
                else Text(d?.name ?: s.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 42.sp)
            }

            if (s.sheet == "info") d?.let { InfoPanel(it) }
            if (s.sheet == "episodes") SideSheet("Episodes") {
                itemsIndexed(s.nextEpisodes) { index, episode -> SheetRow("S${episode.season} · E${episode.episode}  ${episode.title}", first = index == 0) { s.sheet = null; a.playEpisode(index) } }
            }

            // Bottom: play, elapsed, progress, total; then the choices and menus.
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 56.dp, end = 56.dp, bottom = 34.dp)) {
                val trick = s.trickplay
                if (s.seekPreview >= 0f && s.duration > 0) {
                    if (trick != null) BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
                        val x = (maxWidth * s.seekPreview - 120.dp).coerceIn(0.dp, (maxWidth - 240.dp).coerceAtLeast(0.dp))
                        Box(Modifier.offset(x = x).align(Alignment.BottomStart).padding(bottom = 8.dp)) { Thumbnail(trick, (s.seekPreview * s.duration).toLong()) }
                    } else s.localUri?.let { LocalSeekPreview(it, (s.seekPreview * s.duration).toLong()) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StreamingIcon(if (s.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (s.playing) "Pause" else "Play", null,
                        Modifier.focusRequester(play), size = 52.dp, onClick = a.playPause)
                    Text(time(if (s.seekPreview >= 0f) (s.seekPreview * s.duration).toLong() else s.position), fontSize = 17.sp, color = Color.White)
                    Box(Modifier.weight(1f)) { SeekBar(s, a) }
                    Text(if (s.duration > 0) time(s.duration) else "", fontSize = 17.sp, color = Color.White)
                }
                // Sound and Subtitles are drop-ups: OK opens a short list above the button, and picking an
                // entry applies it straight away. Back closes the list and returns to its button.
                val soundButton = remember { FocusRequester() }
                val subtitleButton = remember { FocusRequester() }
                var lastSheet by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(s.sheet) {
                    val closed = lastSheet
                    lastSheet = s.sheet
                    if (s.sheet == null && s.menu == null) runCatching { when (closed) { SOUND -> soundButton.requestFocus(); SUBTITLES -> subtitleButton.requestFocus() } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (s.audioTracks.isNotEmpty()) {
                        val current = s.audioTracks.firstOrNull { it.second }?.first ?: "Default"
                        DropUp("Sound", current, s.sheet == SOUND, soundButton, onToggle = { s.sheet = if (s.sheet == SOUND) null else SOUND },
                            options = s.audioTracks, onPick = { i -> s.sheet = null; if (!s.audioTracks[i].second) a.pickAudio(i) },
                            onClose = { s.sheet = null })
                    }
                    if (s.subtitleOptions.isNotEmpty()) {
                        val on = s.subtitleOptions.indexOfFirst { it.second }
                        val current = if (on <= 0) "Off" else s.subtitleOptions[on].first
                        val options = s.subtitleOptions.mapIndexed { i, t -> (if (i == 0) "Off" else t.first) to t.second }
                        DropUp("Subtitles", current, s.sheet == SUBTITLES, subtitleButton, onToggle = { s.sheet = if (s.sheet == SUBTITLES) null else SUBTITLES },
                            options = options, onPick = { i -> s.sheet = null; if (!s.subtitleOptions[i].second) a.pickSubtitle(i) },
                            onClose = { s.sheet = null }, more = "Subtitle settings" to { s.sheet = null; a.openMenu("options", "subtitles") })
                    }
                    if (s.nextEpisodes.isNotEmpty()) StreamingSquare(Icons.AutoMirrored.Outlined.FormatListBulleted, "Episodes") { s.sheet = if (s.sheet == "episodes") null else "episodes" }
                    if (s.fileQueueLabel != null) StreamingSquare(Icons.AutoMirrored.Outlined.FormatListBulleted, "Queue", a.queue)
                    if (d != null) StreamingSquare(Icons.Outlined.Info, "Details") { s.sheet = if (s.sheet == "info") null else "info" }
                    StreamingSquare(Icons.Outlined.Settings, "Player settings") { a.openMenu("quick", null) }
                }
            }
        }
        }
    }
}

private const val SCALE = .75f
private const val SOUND = SOUND_SHEET
private const val SUBTITLES = SUBTITLE_SHEET

/**
 * "Sound: English ▴": OK opens the choices in a list that rises above the button without moving
 * anything else. The current choice is ticked and focused; Left and Right stay inside the list and
 * Down from the last entry closes it.
 */
@Composable
private fun DropUp(name: String, current: String, open: Boolean, button: FocusRequester, onToggle: () -> Unit,
    options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit, onClose: () -> Unit, more: Pair<String, () -> Unit>? = null) {
    Box {
        StreamingChip("$name: $current", arrow = if (open) "▾" else "▴", modifier = Modifier.focusRequester(button), onClick = onToggle)
        if (open) {
            val count = options.size + if (more != null) 1 else 0
            val selected = options.indexOfFirst { it.second }.coerceAtLeast(0)
            val first = remember { FocusRequester() }
            var focusedRow by remember { mutableIntStateOf(selected) }
            val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
            val maxW = with(androidx.compose.ui.platform.LocalDensity.current) { 460.dp.roundToPx() }
            val maxH = with(androidx.compose.ui.platform.LocalDensity.current) { 420.dp.roundToPx() }
            Box(Modifier.align(Alignment.TopStart)
                .layout { m, _ ->
                    // Zero size in the row: the list is drawn above the button and does not push anything.
                    val p = m.measure(androidx.compose.ui.unit.Constraints(maxWidth = maxW, maxHeight = maxH))
                    layout(0, 0) { p.place(0, -p.height - gap) }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight -> true
                        androidx.compose.ui.input.key.Key.DirectionUp -> focusedRow == 0
                        androidx.compose.ui.input.key.Key.DirectionDown -> if (focusedRow == count - 1) { onClose(); true } else false
                        else -> false
                    }
                }
                .widthIn(min = 220.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xF2181818)).padding(vertical = 8.dp)) {
                androidx.compose.foundation.lazy.LazyColumn(state = list) {
                    itemsIndexed(options, key = { i, t -> "$i:${t.first}" }) { i, (label, on) ->
                        DropRow(label, on, { if (it) focusedRow = i }, if (i == selected) Modifier.focusRequester(first) else Modifier) { onPick(i) }
                    }
                    if (more != null) item("more") {
                        DropRow(more.first, false, { if (it) focusedRow = options.size }, dim = true, onClick = more.second)
                    }
                }
            }
        }
    }
}

@Composable
private fun DropRow(text: String, selected: Boolean, onFocus: (Boolean) -> Unit, modifier: Modifier = Modifier, dim: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick, modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp).onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
        radius = 4, container = Color.Transparent, focusedContainer = Color.White, focusedContent = Color.Black, outline = false, focusedScale = 1f) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(18.dp)) {
                if (selected) Image(Icons.Outlined.Check, null, colorFilter = ColorFilter.tint(if (focused) Color.Black else Color.White), modifier = Modifier.fillMaxSize())
            }
            Text(text, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (focused) Color.Black else Color.White.copy(alpha = if (dim) .7f else 1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A plain icon: no circle until focused, then a soft white disc behind it. */
@Composable
private fun StreamingIcon(icon: ImageVector, name: String, onLabel: ((String?) -> Unit)?, modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick, modifier = modifier.size(size).onFocusChanged {
        val was = focused
        focused = it.isFocused
        if (it.isFocused) onLabel?.invoke(name) else if (was) onLabel?.invoke(null)
    }, radius = 50, container = Color.Transparent, focusedContainer = Color.White.copy(alpha = .92f), focusedContent = Color.Black,
        outline = false, focusedScale = 1.06f) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(icon, name, colorFilter = ColorFilter.tint(if (focused) Color.Black else Color.White), modifier = Modifier.size(size * .62f))
        }
    }
}

/** A rectangular button under the progress bar, with an optional arrow after the text. */
@Composable
private fun StreamingChip(text: String, modifier: Modifier = Modifier, arrow: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick, modifier = modifier.heightIn(min = 40.dp).widthIn(max = 420.dp).onFocusChanged { focused = it.isFocused },
        radius = 4, container = Color.White.copy(alpha = .16f), focusedContainer = Color.White, focusedContent = Color.Black, outline = false, focusedScale = 1f) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color.Black else Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (arrow != null) Text(arrow, fontSize = 13.sp, color = if (focused) Color.Black else Color.White.copy(alpha = .8f))
        }
    }
}

/** A square icon button matching the chips. */
@Composable
private fun StreamingSquare(icon: ImageVector, name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick, modifier = Modifier.size(40.dp).onFocusChanged { focused = it.isFocused }, radius = 4,
        container = Color.White.copy(alpha = .16f), focusedContainer = Color.White, focusedContent = Color.Black, outline = false, focusedScale = 1f) {
        Box(Modifier.fillMaxSize().background(Color.Transparent, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Image(icon, name, colorFilter = ColorFilter.tint(if (focused) Color.Black else Color.White), modifier = Modifier.size(22.dp))
        }
    }
}
