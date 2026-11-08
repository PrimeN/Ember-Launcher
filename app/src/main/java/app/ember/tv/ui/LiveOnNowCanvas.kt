package app.ember.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.ember.tv.iptv.Channel
import app.ember.tv.iptv.Programme

/** All channels in the authorized selection, virtualized; focusing a card never starts a stream. */
@Composable
internal fun LiveOnNowCanvas(channels: List<Channel>, programme: (Channel) -> Programme?, clock: Long,
    sourceNames: Map<String, String>, onFocus: (Channel) -> Unit, onOptions: (Channel) -> Unit,
    onPlay: (Int) -> Unit, modifier: Modifier = Modifier, entryKey: String? = null,
    entryVersion: Int = 0, onEntryHandled: () -> Unit = {}) {
    val grid = rememberLazyGridState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val latestHandled by rememberUpdatedState(onEntryHandled)
    LaunchedEffect(entryVersion) {
        if (entryVersion > 0) {
            val index = channels.indexOfFirst { it.key == entryKey }.coerceAtLeast(0)
            if (channels.isNotEmpty()) {
                grid.scrollToItem(index)
                withFrameNanos { }; withFrameNanos { }
                runCatching { requesters[channels[index].key]?.requestFocus() }
            }
            latestHandled()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("live_canvas")) {
        val columns = (maxWidth.value / (190f * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(2, 6)
        LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.fillMaxSize().focusGroup().testTag("live_canvas_grid"),
            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(channels, key = { _, channel -> channel.key }, contentType = { _, _ -> "channel" }) { index, channel ->
                val focus = remember(channel.key) { FocusRequester() }
                DisposableEffect(channel.key) {
                    requesters[channel.key] = focus
                    onDispose { requesters.remove(channel.key) }
                }
                BoxWithConstraints(Modifier.testTag("live_card_${channel.key}")) {
                    LiveFeaturedCard(channel, programme(channel), clock, sourceNames[channel.sourceId].orEmpty(), maxWidth,
                        Modifier.focusRequester(focus), onFocus = { onFocus(channel) },
                        onOptions = { onOptions(channel) }, onPlay = { onPlay(index) })
                }
            }
        }
    }
}
