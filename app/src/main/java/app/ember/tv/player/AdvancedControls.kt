package app.ember.tv.player

import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import app.ember.tv.ui.isEmberX
import app.ember.tv.ui.EmberXColors
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import app.ember.tv.ui.navigationGlass
import app.ember.tv.ui.Focusable
import app.ember.tv.ui.Wash
import app.ember.tv.ui.PillColor
import app.ember.tv.ui.OnPillColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.data.Episode
import coil.compose.AsyncImage
import java.util.Locale

/**
 * State of the advanced player controls (Settings > Player > Player look: Advanced). Kept up to
 * date by PlayerActivity; read by [AdvancedControls].
 */
class ControlsState {
    var fileQueueLabel by mutableStateOf<String?>(null)
    var fileQueueNext by mutableStateOf(false)
    var localUri by mutableStateOf<String?>(null)
    var visible by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var title by mutableStateOf("")
    var details by mutableStateOf<PlayerDetails?>(null)
    var seasonEpisode by mutableStateOf<String?>(null)
    var nextEpisodes by mutableStateOf<List<Episode>>(emptyList())
    /** Which side sheet is open: null, "info", "audio", "episodes". */
    var sheet by mutableStateOf<String?>(null)
    var audioTracks by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
    /** Changes whenever the controls are shown again, so focus returns to Play. */
    var shownAt by mutableLongStateOf(0L)
    var seekPreview by mutableFloatStateOf(-1f)
    /** Subtitle choices for the Subtitles sheet: label and whether it is the one in use. */
    var subtitleOptions by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
    /** Current subtitle timing shift, in ms. */
    var subtitleShift by mutableLongStateOf(0L)
    /** "Skip intro" / "Skip recap" / "Skip credits" while one is on screen, else null. */
    var skipLabel by mutableStateOf<String?>(null)
    /** Open menu: null, "quick" (the small menu) or "options" (the full page with tabs). */
    var menu by mutableStateOf<String?>(null)
    /** Tab of the options page: "video", "audio", "subtitles". */
    var menuTab by mutableStateOf("playback")
    /** Title of the option whose choices are open, or null for the list. */
    var menuRow by mutableStateOf<String?>(null)
    /** Row to focus in a menu: "" the first, a row's title, or null to leave focus where it is. */
    var menuFocus by mutableStateOf<String?>("")
    internal fun closeMenuSession() {
        menu=null
        menuRow=null
        menuFocus=""
        menuTab="playback"
    }
    /** Bumped whenever an option's value may have changed, so menus read them again. */
    var optionsTick by mutableIntStateOf(0)
    /** Playback stats (HUD): label and value lines; empty when off. */
    var stats by mutableStateOf<List<Pair<String, String>>>(emptyList())
    /** The accent colour (Settings > Appearance), for the progress bar and the menus. */
    var accent by mutableStateOf(Color(0xFFFF7A2F))
    /** Settings > Player > Player interface (PlayerInterfaces), for the layouts sharing these controls. */
    var design by mutableStateOf(PlayerInterfaces.MODERN)
    /** Seek thumbnails from Plex or Jellyfin, when the server has them. */
    var trickplay by mutableStateOf<app.ember.tv.mediaserver.Trickplay?>(null)
}

/** What the buttons do; PlayerActivity provides them. */
class ControlsActions(
    val playPause: () -> Unit,
    val seekBy: (Long) -> Unit,
    val skip: () -> Unit,
    /** Opens a menu: "quick", or "options" on a tab ("video", "audio", "subtitles"). */
    val openMenu: (String, String?) -> Unit,
    /** The rows of a menu: "quick", "video", "audio" or "subtitles". */
    val options: (String) -> List<OptionRow>,
    val next: () -> Unit,
    val playEpisode: (Int) -> Unit,
    /** Any key on the controls: keeps them from hiding. */
    val touched: () -> Unit,
    val queue: () -> Unit = {},
    val close: () -> Unit = {},
    /** Quick choices outside the menus: an index into [ControlsState.audioTracks] / [ControlsState.subtitleOptions]. */
    val pickAudio: (Int) -> Unit = {},
    val pickSubtitle: (Int) -> Unit = {},
)

private val Scrim = Color(0xCC000000)
internal val Soft = Color(0xFFB9BDC4)
internal val PillFocus:Color
    @Composable get()=app.ember.tv.ui.PillColor

@Composable
fun AdvancedControls(s: ControlsState, a: ControlsActions) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPlayerAccent provides s.accent) {
    Box(Modifier.fillMaxSize()) {
        StatsHud(s)
        // Hidden under a menu, which sits in the same corner.
        if (s.menu == null) SkipPill(s, a)
        if(isEmberX)EmberXControls(s,a) else if(s.design==PlayerInterfaces.STREAMING)StreamingControls(s,a) else Controls(s, a)
        when (s.menu) {
            "quick" -> QuickMenu(s, a)
            "options" -> OptionsPage(s, a)
        }
    }
    }
}

/** The accent colour inside the player's controls and menus. */
internal val LocalPlayerAccent = androidx.compose.runtime.staticCompositionLocalOf { Color(0xFFFF7A2F) }

/** "Skip intro" pill, bottom right; shown with or without the controls. OK skips. */
@Composable
private fun SkipPill(s: ControlsState, a: ControlsActions) {
    val label = s.skipLabel ?: return
    var focused by remember { mutableStateOf(false) }
    val bottom = if (s.visible) 190.dp else 56.dp
    Box(Modifier.fillMaxSize().padding(end = 56.dp, bottom = bottom), contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier.clip(RoundedCornerShape(50))
                .background(if (focused) PillFocus else Color(0xCC1D1E22))
                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(50))
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (ok && e.type == KeyEventType.KeyUp) a.skip()
                    ok
                }
                .focusable()
                .padding(horizontal = 26.dp, vertical = 12.dp),
        ) {
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Color(0xFF1F1F1F) else Color.White)
        }
    }
}

@Composable
private fun Controls(s: ControlsState, a: ControlsActions) {
    AnimatedVisibility(visible = s.visible, enter = fadeIn(), exit = fadeOut()) {
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(s.shownAt) { runCatching { playFocus.requestFocus() } }
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(0f to Color(0x99000000), 0.35f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0xE6000000)))
                .onKeyEvent { a.touched(); false },
        ) {
            // Top left: subtitles, sound, next episode, info.
            Row(Modifier.padding(start = 56.dp, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrackButtons(s)
                IconButton(Icons.Outlined.Tune, "Options") { a.openMenu("quick", null) }
                if(s.fileQueueLabel!=null)IconButton(Icons.AutoMirrored.Outlined.FormatListBulleted,"Queue"){a.queue()}
                IconButton(Icons.Outlined.Info, "Details") { s.sheet = if (s.sheet == "info") null else "info" }
            }

            // Synopsis and cast: open with Info, and while paused.
            val d = s.details
            val showInfo = d != null && (s.sheet == "info" || (!s.playing && s.sheet == null))
            if (showInfo && d != null) InfoPanel(d)
            if (s.sheet == "episodes") SideSheet("Episodes") {
                itemsIndexed(s.nextEpisodes) { i, e -> SheetRow("S${e.season} · E${e.episode}  ${e.title}", first = i == 0) { s.sheet = null; a.playEpisode(i) } }
            }
            TrackSheet(s, a)

            // Bottom: title line, progress, transport buttons.
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 56.dp, end = 56.dp, bottom = 36.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val logo = d?.logo
                    var logoOk by remember(logo) { mutableStateOf(logo != null) }
                    if (logo != null && logoOk) {
                        AsyncImage(
                            model = logo,
                            contentDescription = d.name,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.height(44.dp).width(220.dp),
                            onError = { logoOk = false },
                        )
                    } else {
                        Text(d?.name ?: s.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    }
                    val bits = listOfNotNull(d?.year, d?.rating, s.seasonEpisode, d?.episodeTitle)
                    if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), fontSize = 16.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(14.dp))
                val tp = s.trickplay
                if (tp != null && s.seekPreview >= 0f && s.duration > 0) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
                        val x = (maxWidth * s.seekPreview - 120.dp).coerceIn(0.dp, (maxWidth - 240.dp).coerceAtLeast(0.dp))
                        Box(Modifier.offset(x = x).align(Alignment.BottomStart).padding(bottom = 8.dp)) {
                            Thumbnail(tp, (s.seekPreview * s.duration).toLong())
                        }
                    }
                }
                if(s.trickplay==null && s.localUri!=null && s.seekPreview>=0f && s.duration>0)LocalSeekPreview(s.localUri!!,(s.seekPreview*s.duration).toLong())
                SeekBar(s, a)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(time(if (s.seekPreview >= 0f) (s.seekPreview * s.duration).toLong() else s.position), fontSize = 13.sp, color = Soft)
                    Spacer(Modifier.weight(1f))
                    Text(if (s.duration > 0) "-" + time(s.duration - s.position) + "   " + time(s.duration) else "", fontSize = 13.sp, color = Soft)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(Icons.Outlined.Replay10, "Back 10 seconds") { a.seekBy(-10_000) }
                    IconButton(
                        if (s.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (s.playing) "Pause" else "Play",
                        big = true,
                        modifier = Modifier.focusRequester(playFocus),
                    ) { a.playPause() }
                    IconButton(Icons.Outlined.Forward30, "Forward 30 seconds") { a.seekBy(30_000) }
                    if (s.nextEpisodes.isNotEmpty()||s.fileQueueNext) IconButton(Icons.Outlined.SkipNext,if(s.fileQueueLabel!=null)"Next file" else "Next episode") {a.next()}
                    if(s.nextEpisodes.isNotEmpty())IconButton(Icons.AutoMirrored.Outlined.FormatListBulleted,"Episodes"){s.sheet=if(s.sheet=="episodes")null else "episodes"}
                }
            }
        }
    }
}

internal fun time(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val sec = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

/** A round icon button: outlined, filled white with a dark icon when focused. */
@Suppress("ModifierParameter") // big is the common option; modifier is always passed by name.
@Composable
private fun IconButton(icon: ImageVector, label: String, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val size = if(big && isEmberX)56.dp else if (big) 48.dp else 40.dp
    Focusable(onClick=onClick,modifier=modifier.size(size).onFocusChanged {focused=it.isFocused},
        radius=app.ember.tv.ui.TvTokens.Shape.Pill,container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=app.ember.tv.ui.TvTokens.Focus.Round) {
        // Focused: the white focus fill shows as designed; the glass returns when focus leaves (#39).
        Box(Modifier.fillMaxSize().navigationGlass(CircleShape,focused=focused),contentAlignment=Alignment.Center) {
            Image(icon,contentDescription=label,colorFilter=ColorFilter.tint(if(focused)OnPillColor else Color.White),modifier=Modifier.size(if(big)26.dp else 21.dp))
        }
    }
}

/** The progress bar. Focused, Left and Right move a preview point; OK jumps there. */
@Composable
internal fun SeekBar(s: ControlsState, a: ControlsActions) {
    var focused by remember { mutableStateOf(false) }
    val dur = s.duration.coerceAtLeast(1)
    val played = if (s.seekPreview >= 0f) s.seekPreview else s.position.toFloat() / dur
    Box(
        Modifier.fillMaxWidth().height(18.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) s.seekPreview = -1f
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.nativeKeyEvent.isCanceled && s.seekPreview >= 0f) {
                        a.seekBy((s.seekPreview * dur).toLong() - s.position)
                        s.seekPreview = -1f
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
                val step = when {
                    e.nativeKeyEvent.repeatCount > 20 -> 120_000L
                    e.nativeKeyEvent.repeatCount > 6 -> 30_000L
                    else -> 10_000L
                }
                when (e.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val base = if (s.seekPreview >= 0f) s.seekPreview * dur else s.position.toFloat()
                        val delta = if (e.key == Key.DirectionRight) step else -step
                        s.seekPreview = ((base + delta) / dur).coerceIn(0f, 1f)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (focused) 6.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth((s.buffered.toFloat() / dur).coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(50)).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(played.coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(50)).background(s.accent))
        if (focused) {
            Row(Modifier.fillMaxWidth(played.coerceIn(0.001f, 1f)), horizontalArrangement = Arrangement.End) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(s.accent).border(2.dp, Color.White, CircleShape))
            }
        }
    }
}

/** Synopsis on the left, cast and crew on the right (with photos), as on the reference player. */
@Composable
internal fun InfoPanel(d: PlayerDetails) {
    Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 120.dp, bottom = 190.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f)) {
            if (!d.synopsis.isNullOrBlank()) {
                Text(stringResource(R.string.ui_synopsis), fontSize = 13.sp, color = Soft, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(d.synopsis, fontSize = 17.sp, lineHeight = 25.sp, color = Color.White, maxLines = 7, overflow = TextOverflow.Ellipsis)
            }
        }
        if (d.cast.isNotEmpty()) {
            Column(Modifier.width(260.dp).fillMaxHeight()) {
                Text(stringResource(R.string.ui_cast_crew_2), fontSize = 13.sp, color = Soft, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(d.cast) { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF3A3B40)), contentAlignment = Alignment.Center) {
                                Text(p.name.take(1), fontSize = 16.sp, color = Color.White)
                                if (!p.photo.isNullOrBlank()) AsyncImage(model = p.photo, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column {
                                Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                                p.role?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = Soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A panel on the right for choosing a sound track or an episode. */
@Composable
internal fun SideSheet(title: String, start: Int = 0, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.padding(18.dp).width(300.dp).fillMaxHeight(.72f).clip(RoundedCornerShape(24.dp)).background(Color(0xF21D1E22)).padding(vertical = 10.dp),
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.padding(start = 24.dp, bottom = 10.dp))
            // Opens scrolled near [start], so the row that takes focus is already on screen.
            LazyColumn(state = androidx.compose.foundation.lazy.rememberLazyListState((start - 3).coerceAtLeast(0)), content = content)
        }
    }
}

@Composable
internal fun SheetRow(label: String, first: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        Modifier.focusRequester(fr).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(14.dp))
            .background(if (focused) PillFocus else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) onClick()
                ok
            }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (focused) Color(0xFF1F1F1F) else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One seek thumbnail: the right cell of a Plex/Jellyfin sprite sheet, cropped. */
@Composable
internal fun Thumbnail(t: app.ember.tv.mediaserver.Trickplay, positionMs: Long) {
    if (!t.valid) return
    val (url, col, row) = t.at(positionMs)
    val w = 240.dp
    val h = w * (t.height.toFloat() / t.width.coerceAtLeast(1))
    Box(Modifier.size(w, h).clip(RoundedCornerShape(10.dp)).background(Color.Black).border(2.dp, Color.White, RoundedCornerShape(10.dp))) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(x = -w * col, y = -h * row)
                .requiredSize(w * t.cols, h * t.rows),
        )
    }
}


/** Cinema transport matches the TV reference while keeping all existing player actions/engines. */
@Composable
private fun EmberXControls(s:ControlsState,a:ControlsActions) {
    AnimatedVisibility(s.visible,enter=fadeIn(),exit=fadeOut()) {
        val play=remember {FocusRequester()}
        LaunchedEffect(s.shownAt){runCatching {play.requestFocus()}}
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha=.88f),.22f to Color.Transparent,.70f to Color.Transparent,1f to Color.Black.copy(alpha=.96f)))
            .onKeyEvent {a.touched();false}) {
            Row(Modifier.fillMaxWidth().padding(horizontal=32.dp,vertical=24.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(Icons.AutoMirrored.Outlined.ArrowBack,"Back",onClick=a.close)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(s.details?.name ?: s.title,fontSize=25.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                    val episode=listOfNotNull(s.seasonEpisode,s.details?.episodeTitle).joinToString(" · ")
                    if(episode.isNotBlank())Text(episode,color=EmberXColors.Secondary,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconButton(Icons.Outlined.Info,"Details"){s.sheet=if(s.sheet=="info")null else "info"}
            }
            Row(Modifier.align(Alignment.Center),horizontalArrangement=Arrangement.spacedBy(28.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(Icons.Outlined.Replay10,"Back 10 seconds"){a.seekBy(-10_000)}
                IconButton(if(s.playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(s.playing)"Pause" else "Play",big=true,
                    modifier=Modifier.focusRequester(play),onClick=a.playPause)
                IconButton(Icons.Outlined.Forward10,"Forward 10 seconds"){a.seekBy(10_000)}
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal=32.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                val trick=s.trickplay
                if(s.seekPreview>=0f && s.duration>0) {
                    if(trick!=null)Thumbnail(trick,(s.seekPreview*s.duration).toLong())
                    else s.localUri?.let {LocalSeekPreview(it,(s.seekPreview*s.duration).toLong())}
                }
                SeekBar(s,a)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(time(if(s.seekPreview>=0f)(s.seekPreview*s.duration).toLong() else s.position)+"  ·  "+time(s.duration),
                        fontSize=13.sp,color=Color.White,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if(s.nextEpisodes.isNotEmpty() || s.fileQueueNext)IconButton(Icons.Outlined.SkipNext,if(s.fileQueueLabel!=null)"Next file" else "Next episode",onClick=a.next)
                    if(s.nextEpisodes.isNotEmpty())IconButton(Icons.AutoMirrored.Outlined.FormatListBulleted,"Episodes"){s.sheet=if(s.sheet=="episodes")null else "episodes"}
                    if(s.fileQueueLabel!=null)IconButton(Icons.AutoMirrored.Outlined.FormatListBulleted,"Queue",onClick=a.queue)
                    TrackButtons(s)
                    IconButton(Icons.Outlined.Settings,"Player settings"){a.openMenu("quick",null)}
                }
            }
            if(s.sheet=="info")s.details?.let {InfoPanel(it)}
            if(s.sheet=="episodes")SideSheet("Episodes") {
                itemsIndexed(s.nextEpisodes){index,episode->SheetRow("S${episode.season} · E${episode.episode}  ${episode.title}",first=index==0){s.sheet=null;a.playEpisode(index)}}
            }
            TrackSheet(s,a)
        }
    }
}

internal const val SOUND_SHEET = "sound"
internal const val SUBTITLE_SHEET = "subtitles"

/**
 * Subtitles and Sound open a short list on the right instead of the full Options page: the current
 * choice is ticked and focused, picking applies it and closes the list, and Back closes it. Focus
 * returns to the button that opened it. The last line opens the full settings for that tab.
 */
@Composable
private fun TrackButtons(s: ControlsState) {
    val subtitles = remember { FocusRequester() }
    val sound = remember { FocusRequester() }
    var last by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.sheet) {
        val closed = last
        last = s.sheet
        if (s.sheet == null && s.menu == null) runCatching { when (closed) { SUBTITLE_SHEET -> subtitles.requestFocus(); SOUND_SHEET -> sound.requestFocus() } }
    }
    IconButton(Icons.Outlined.Subtitles, "Subtitles", modifier = Modifier.focusRequester(subtitles)) { s.sheet = if (s.sheet == SUBTITLE_SHEET) null else SUBTITLE_SHEET }
    IconButton(Icons.AutoMirrored.Outlined.VolumeUp, "Sound", modifier = Modifier.focusRequester(sound)) { s.sheet = if (s.sheet == SOUND_SHEET) null else SOUND_SHEET }
}

@Composable
private fun TrackSheet(s: ControlsState, a: ControlsActions) {
    when (s.sheet) {
        SOUND_SHEET -> {
            val tracks = s.audioTracks
            val on = tracks.indexOfFirst { it.second }.coerceAtLeast(0)
            SideSheet("Sound", start = on) {
                if (tracks.isEmpty()) item("none") { SheetRow("No other sound tracks", first = true) { s.sheet = null } }
                itemsIndexed(tracks, key = { i, t -> "a$i:${t.first}" }) { i, (label, chosen) ->
                    SheetRow(tick(label, chosen), first = i == on) { s.sheet = null; if (!chosen) a.pickAudio(i) }
                }
                item("more") { SheetRow("Sound settings…") { s.sheet = null; a.openMenu("options", "audio") } }
            }
        }
        SUBTITLE_SHEET -> {
            val options = s.subtitleOptions
            val on = options.indexOfFirst { it.second }.coerceAtLeast(0)
            SideSheet("Subtitles", start = on) {
                itemsIndexed(options, key = { i, t -> "s$i:${t.first}" }) { i, (label, chosen) ->
                    SheetRow(tick(if (i == 0) "Off" else label, chosen), first = i == on) { s.sheet = null; if (!chosen) a.pickSubtitle(i) }
                }
                item("more") { SheetRow("Subtitle settings…", first = options.isEmpty()) { s.sheet = null; a.openMenu("options", "subtitles") } }
            }
        }
    }
}

private fun tick(label: String, chosen: Boolean) = if (chosen) "✓  $label" else "     $label"
