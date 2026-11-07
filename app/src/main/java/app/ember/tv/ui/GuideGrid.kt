package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.iptv.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** One logical programme target per channel keeps gaps and tiny cells reachable. */
@Composable
internal fun GuideGrid(
    channels: List<Channel>, guideFor: (Channel) -> List<Programme>, favourites: Set<String>, clock: Long,
    onPlay: (Int) -> Unit, onFavourite: (Channel) -> Unit,
    entryKey: String? = null, entryVersion: Int = 0, onEntryHandled: () -> Unit = {},
    onProgramme: ((Channel, Programme?) -> Unit)? = null,
    panel: (@Composable (Channel?, Programme?, Long) -> Unit)? = null,
    desiredRows: Int = 6, showLogos: Boolean = true,
    showControls:Boolean=true,showPanel:Boolean=true,
    onSelection:(Channel?,Programme?,Long)->Unit={_,_,_->},onGuideFocus:()->Unit={},
    onExitSidebar:()->Boolean={false},onExitUp:()->Boolean={false},
    nowRequest:String?=null,onNowHandled:()->Unit={},
    jumpTime:Long?=null,onJumpHandled:()->Unit={},
) {
    var start by rememberSaveable { mutableLongStateOf(GuideWindow.start(clock)) }
    var time by rememberSaveable { mutableLongStateOf(clock) }
    var followLive by rememberSaveable {mutableStateOf(true)}
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.ROOT
    val dateLabel=remember(time,locale) {java.text.SimpleDateFormat("EEE, M/d",locale).format(java.util.Date(time)).uppercase(locale)}
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val vertical = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val rulerFocus=remember {FocusRequester()}
    var viewport by remember { mutableIntStateOf(0) }
    val ppm = with(LocalDensity.current) { 5.dp.toPx() }
    val ring = LiveColors.Focus
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val channelIndex = remember(channels) { channels.mapIndexed { index, channel -> channel.key to index }.toMap() }
    val slotCache = remember { GuideSlotCache() }
    fun slotsFor(channel: Channel) = slotCache.slots(channel.key, guideFor(channel), start)
    val focused = channelIndex[selectedKey]?.let { channels[it] }
    val programme = focused?.let { GuideWindow.at(slotsFor(it), time)?.programme }
    val currentSelection by rememberUpdatedState(onSelection)
    LaunchedEffect(focused,programme,time) {currentSelection(focused,programme,time)}
    LaunchedEffect(nowRequest) {
        if(nowRequest!=null) {start=GuideWindow.start(clock);time=clock;followLive=true;scroll.scrollTo(0);onNowHandled()}
    }
    LaunchedEffect(jumpTime) {
        jumpTime?.let { target->
            start=GuideWindow.start(target);time=target;followLive=target<=clock
            scroll.scrollTo(0)
            if(channels.isNotEmpty()) {
                val index=channelIndex[selectedKey] ?: 0
                selectedKey=channels[index].key
                vertical.scrollToItem(index);withFrameNanos { }
                requesters[selectedKey]?.let {runCatching {it.requestFocus()}}
            }
            onJumpHandled()
        }
    }
    val minuteBucket by remember(scroll, ppm) { derivedStateOf { (scroll.value / ppm / 15).toInt() * 15 } }
    val from = start + (minuteBucket - 60).coerceAtLeast(0) * 60_000L
    val to = minOf(start + GuideWindow.DURATION, start + (minuteBucket + (viewport / ppm).toInt() + 75) * 60_000L)
    fun reveal(value: Long) {
        val x = (value - start) / 60_000f * ppm
        if (x < scroll.value || x > scroll.value + viewport - 30 * ppm)
            scope.launch { scroll.scrollTo((x - viewport * .35f).toInt().coerceAtLeast(0)) }
    }
    fun moveChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        selectedKey = channel.key
        scope.launch {
            if (vertical.layoutInfo.visibleItemsInfo.none { it.key == "grid:${channel.key}" }) vertical.scrollToItem(index)
            withFrameNanos { }
            requesters[channel.key]?.requestFocus()
        }
    }
    LaunchedEffect(entryVersion) {
        if (entryVersion > 0) {
            if(channels.isNotEmpty()) {
            if (entryKey != null) { start = GuideWindow.start(clock); time = clock; followLive=true;scroll.scrollTo(0) }
            moveChannel(channelIndex[entryKey ?: selectedKey] ?: 0)
            }
            onEntryHandled()
        }
    }
    LaunchedEffect(clock) {
        if (followLive && GuideWindow.expired(start, clock)) { start = GuideWindow.start(clock); time = clock; followLive=true;scroll.scrollTo(0) }
        else if(followLive)time=clock
    }
    LaunchedEffect(channelIndex) {
        if (selectedKey != null && selectedKey !in channelIndex) selectedKey = null
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = (maxWidth * .20f).coerceIn(88.dp,136.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if(showControls)ButtonRow {
                ToolbarIcon("Earlier programmes", Icons.AutoMirrored.Outlined.ArrowBack) {followLive=false; time = (time - 2 * 3_600_000L).coerceAtLeast(start); reveal(time) }
                ToolbarIcon("Later programmes", Icons.AutoMirrored.Outlined.ArrowForward) {followLive=false; time = (time + 2 * 3_600_000L).coerceAtMost(start + GuideWindow.DURATION - 1); reveal(time) }
                ToolbarIcon("Jump to now", Icons.Outlined.Schedule) {followLive=true; start = GuideWindow.start(clock); time = clock; scope.launch { scroll.scrollTo(0) } }
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(time)), color = Mute, fontSize = 13.sp)
            }
            if (showPanel && panel != null) panel(focused, programme, time)
            else if(showPanel)Column(Modifier.fillMaxWidth().heightIn(min = 78.dp).background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text(focused?.let { (programme?.title ?: "No programme information") + " · " + it.name } ?: "Move through the guide to see programme information", fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                programme?.let { Text(timeOf(it.start) + "–" + timeOf(it.stop), color = Mute, fontSize = 12.sp) }
                programme?.description?.let { Text(it, color = Mute, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).clipToBounds()) {
                val fullWidth=with(LocalDensity.current){maxWidth.toPx()}
                val railPixels=with(LocalDensity.current){rail.toPx()}
                val labelPixels=with(LocalDensity.current){64.dp.toPx()}
                Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.Bottom) {
                    Column(Modifier.width(rail).liveRulerFocusGroup(rulerFocus,up=onExitUp,down={
                        if(channels.isEmpty())false else {moveChannel(channelIndex[selectedKey] ?: 0);true}
                    }),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(dateLabel,
                            color=LiveColors.Muted,fontSize=TvTokens.Type.Caption.sp,maxLines=1)
                        Row(Modifier.fillMaxWidth()) {
                            GuideTimeAction("Earlier programmes",Icons.AutoMirrored.Outlined.ArrowBack,Modifier.weight(1f)) {followLive=false;time=(time-2*3_600_000L).coerceAtLeast(start);reveal(time)}
                            GuideTimeAction("Jump to now",Icons.Outlined.Schedule,Modifier.weight(1f)) {followLive=true;time=clock;start=GuideWindow.start(clock);scope.launch {scroll.scrollTo(0)}}
                            GuideTimeAction("Later programmes",Icons.AutoMirrored.Outlined.ArrowForward,Modifier.weight(1f)) {followLive=false;time=(time+2*3_600_000L).coerceAtMost(start+GuideWindow.DURATION-1);reveal(time)}
                        }
                    }
                    Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                        repeat(24) {halfHour ->Box(Modifier.width(150.dp).height(22.dp)) {
                            Text(if(halfHour==0 && start==GuideWindow.start(clock))"ON NOW" else timeOf(start+halfHour*GuideWindow.HALF_HOUR),
                                color=LiveColors.Muted,fontSize=TvTokens.Type.Caption.sp,modifier=Modifier.align(Alignment.CenterStart))
                        }}
                    }
                }
                // Scroll changes update placement/drawing, not the entire guide composition.
                Text(timeOf(clock),color=LiveRed,fontSize=TvTokens.Type.Caption.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.width(64.dp)
                    .clearAndSetSemantics { }
                    .absoluteOffset {
                        val x=GuideWindow.marker(fullWidth,railPixels,(clock-start)/60_000f*ppm,scroll.value.toFloat(),rtl)
                        androidx.compose.ui.unit.IntOffset(if(x==null)-labelPixels.toInt()-1 else LivePresentation.markerLabelLeft(x,fullWidth,railPixels,labelPixels,rtl).toInt(),0)
                    })
                Canvas(Modifier.matchParentSize()) {
                    GuideWindow.marker(fullWidth,railPixels,(clock-start)/60_000f*ppm,scroll.value.toFloat(),rtl)?.let {x->
                        drawCircle(LiveRed,3.dp.toPx(),Offset(x,size.height-3.dp.toPx()))
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val rowHeight = (maxHeight / desiredRows.coerceIn(5, 7) - 4.dp)
                    .coerceAtLeast(if (LocalDensity.current.fontScale > 1.25f) 72.dp else 52.dp)
                LazyColumn(Modifier.fillMaxSize(), state = vertical, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                    if (channels.isEmpty()) item("none") { Text("No channels here", color = Mute) }
                    itemsIndexed(channels, key = { _, c -> "grid:${c.key}" }) { index, channel ->
                        val requester = remember(channel.key) { FocusRequester() }
                        DisposableEffect(channel.key) { requesters[channel.key] = requester; onDispose { requesters.remove(channel.key) } }
                        var rowFocused by remember { mutableStateOf(false) }
                        val slots = slotsFor(channel)
                        val selected = GuideWindow.at(slots, time)
                        val shown = remember(slots,from,to,selected) { (GuideWindow.visible(slots, from, to) + listOfNotNull(selected)).distinctBy { it.from }.sortedBy { it.from } }
                        val spoken = channel.name + ", " + (selected?.programme?.let { it.title + ", " + timeOf(it.start) + " to " + timeOf(it.stop) } ?: "No programme information, watch live")
                        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                            Focusable(onClick = { onPlay(index) }, onLongClick = { onFavourite(channel) }, focusedScale = TvTokens.Focus.None,
                                modifier = Modifier.width(rail).fillMaxHeight().onFocusChanged { if (it.isFocused) {selectedKey=channel.key;onGuideFocus()} }
                                    .onPreviewKeyEvent {event->
                                        when {
                                            event.key==(if(rtl)Key.DirectionRight else Key.DirectionLeft) -> if(event.type==KeyEventType.KeyDown)onExitSidebar() else true
                                            event.key==Key.DirectionUp && index==0 -> if(event.type==KeyEventType.KeyDown)runCatching {rulerFocus.requestFocus()}.getOrDefault(false) else true
                                            else ->false
                                        }
                                    },container=LiveColors.Cell,focusedContainer=LiveColors.Current,radius=TvTokens.Shape.Card) {
                                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (showLogos && channel.logo != null && rail>=110.dp) AsyncImage(channel.logo, null, modifier = Modifier.size(32.dp))
                                    Text((channel.number?.let { "$it · " } ?: "") + (if (channel.key in favourites) "★ " else "") + channel.name, fontSize = TvTokens.Type.Caption.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,color=LiveColors.Text)
                                }
                            }
                            Focusable(onClick = {
                                val p = selected?.programme
                                if (p != null && p.start > clock && onProgramme != null) onProgramme(channel, p) else onPlay(index)
                            }, onLongClick = { onFavourite(channel) }, focusedScale = TvTokens.Focus.None, outline = false,
                                container = androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(requester).onSizeChanged { viewport = it.width }
                                    .semantics { contentDescription = spoken
                                        stateDescription = listOfNotNull(if (channel.key in favourites) "Favourite" else null,
                                            selected?.programme?.let { if (clock in it.start until it.stop) "On now" else if (it.start > clock) "Upcoming" else "Earlier" }).joinToString(", ")
                                        customActions = listOf(
                                        CustomAccessibilityAction("Previous programme") {followLive=false; time = GuideWindow.move(slots, time, -1); reveal(time); true },
                                        CustomAccessibilityAction("Next programme") {followLive=false; time = GuideWindow.move(slots, time, 1); reveal(time); true }) }
                                    .onFocusChanged { rowFocused = it.isFocused; if (it.isFocused) { selectedKey = channel.key; onGuideFocus();reveal(time) } }
                                    .onPreviewKeyEvent { event ->
                                        val horizontal = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                                        val direction = if (event.key == Key.DirectionRight) 1 else -1
                                        when {
                                            horizontal -> {
                                                val delta = if (rtl) -direction else direction
                                                val next = GuideWindow.move(slots, time, delta)
                                                if (delta < 0 && time == slots.firstOrNull()?.from) false
                                                else { if (event.type == KeyEventType.KeyDown) { followLive=false;time = next; reveal(time) }; true }
                                            }
                                            event.key == Key.DirectionDown && index < channels.lastIndex -> { if (event.type == KeyEventType.KeyDown) moveChannel(index + 1); true }
                                            event.key == Key.DirectionUp && index > 0 -> { if (event.type == KeyEventType.KeyDown) moveChannel(index - 1); true }
                                            event.key==Key.DirectionUp && index==0 -> if(event.type==KeyEventType.KeyDown)runCatching {rulerFocus.requestFocus()}.getOrDefault(false) else true
                                            else -> false
                                        }
                                    }) {
                                Box(Modifier.fillMaxSize().clipToBounds()) {
                                    Row(Modifier.fillMaxSize().horizontalScroll(scroll).clearAndSetSemantics { }) {
                                        var cursor = start
                                        shown.forEach { slot -> key(slot.from) {
                                            if (slot.from > cursor) Spacer(Modifier.width(5.dp * ((slot.from - cursor) / 60_000f)))
                                            val active = rowFocused && slot == selected
                                            Box(Modifier.width(5.dp * ((slot.to - slot.from) / 60_000f)).fillMaxHeight().padding(end = 3.dp)
                                                .background(if (active) LiveColors.Focus else if (clock in slot.from until slot.to) LiveColors.Current else LiveColors.Cell, RoundedCornerShape(12.dp))
                                                .pointerInput(channel.key, slot.from) { detectTapGestures(
                                                    onLongPress = { onFavourite(channel) }, onTap = {followLive=false; time = slot.from; selectedKey = channel.key; requester.requestFocus();
                                                        if (slot.programme != null && slot.programme.start > clock && onProgramme != null) onProgramme(channel, slot.programme) else onPlay(index) }) }) {
                                                Text(slot.programme?.title ?: "No programme information", fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                    color = if (active) LiveColors.OnFocus else LiveColors.Text, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp))
                                            }
                                            cursor = slot.to
                                        } }
                                        if (cursor < start + GuideWindow.DURATION) Spacer(Modifier.width(5.dp * ((start + GuideWindow.DURATION - cursor) / 60_000f)))
                                    }
                                    if (rowFocused && selected != null) Canvas(Modifier.matchParentSize()) {
                                        val offset = (selected.from - start) / 60_000f * ppm - scroll.value
                                        val width = maxOf(24.dp.toPx(), (selected.to - selected.from) / 60_000f * ppm)
                                        val left = if (rtl) size.width - offset - width else offset
                                        drawRect(ring, Offset(left, 1f), Size(width, size.height - 2f), style = Stroke(2.dp.toPx()))
                                    }
                                }
                            }
                        }
                    }
                }
                Canvas(Modifier.matchParentSize()) {
                    GuideWindow.marker(size.width, rail.toPx(), (clock - start) / 60_000f * ppm, scroll.value.toFloat(), rtl)?.let { x ->
                        drawLine(LiveRed, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideTimeAction(label:String,icon:ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    var focused by remember {mutableStateOf(false)}
    Focusable(onClick=onClick,modifier=modifier.height(22.dp).semantics {contentDescription=label}.onFocusChanged {focused=it.isFocused},
        container=Color.Transparent,focusedContainer=LiveColors.Focus,focusedContent=LiveColors.OnFocus,radius=TvTokens.Shape.Small,outline=false,focusedScale=TvTokens.Focus.None) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            MaterialGlyph(icon,if(focused)LiveColors.OnFocus else LiveColors.Muted,14.dp)
            ToolbarHint(label,focused)
        }
    }
}
