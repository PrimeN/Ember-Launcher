package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.iptv.*

/** Provider URLs stay in encrypted storage and are never rendered in guide search results. */
@Composable
internal fun EpgTools(iptv: IptvController, initial: Channel? = null, close: () -> Unit) {
    var source by remember(initial?.key) { mutableStateOf(initial?.sourceId ?: iptv.sources.firstOrNull()?.id.orEmpty()) }
    var channel by remember(initial?.key) { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var feedChoice by remember { mutableStateOf<String?>(null) }
    var epgSearch by remember { mutableStateOf("") }
    LaunchedEffect(iptv.sources.map { it.id }) {
        if (iptv.sources.none { it.id == source }) { source = iptv.sources.firstOrNull()?.id.orEmpty(); channel = null }
    }
    MediaDialog(if (channel == null) "Programme guide tools" else "Match guide · ${channel!!.name}", close, resizeForKeyboard = true) {
        Text(iptv.guideProgress ?: "Link guides, correct time offsets and repair channel matching.", color = Mute, fontSize = 13.sp)
        iptv.message?.let { Text(it, color = Mute, fontSize = 13.sp) }
        if (channel == null) {
            ButtonRow { iptv.sources.forEach { item -> Chip(item.name, selected = source == item.id) { source = item.id; query = "" } } }
            val feeds = iptv.availableFeeds()
            val assigned = iptv.epg.feedIds(source, feeds)
            Text(stringResource(R.string.tx_guide_sources_priority_follows_the_order), fontSize = 16.sp)
            if (source.isEmpty()) Text(stringResource(R.string.tx_add_an_iptv_playlist_in_live), color = Mute)
            if (feeds.isEmpty()) Text(stringResource(R.string.tx_add_an_xmltv_guide_below_then), color = Mute)
            feeds.forEach { feed ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (source.isNotEmpty()) SwitchChip(feed.name, feed.id in assigned) { iptv.assignGuide(source, feed.id, feed.id !in assigned) }
                    if (feed.id in assigned) {
                        val minutes = iptv.epg.offsets[feed.id] ?: 0
                        Text(stringResource(R.string.tx_time_offset_min, (if (minutes > 0) "+" else ""), minutes), color = Mute, fontSize = 12.sp)
                        var offsetInput by remember(feed.id, minutes) { mutableStateOf(minutes.toString()) }
                        TvTextField(offsetInput, { offsetInput = it.take(6) }, Modifier.width(200.dp), placeholder = "Offset in minutes")
                        Chip("Apply time offset") {
                            val value = offsetInput.toIntOrNull()
                            message = when {
                                value == null || value !in -1440..1440 -> "Enter an offset from −1440 to +1440 minutes."
                                !iptv.setGuideOffset(feed.id, value) -> "Encrypted storage is unavailable. Offset was not changed."
                                else -> "Time offset updated."
                            }
                        }
                        ButtonRow(spacing = 6.dp) {
                            Chip("−30 min") { iptv.setGuideOffset(feed.id, minutes - 30) }
                            Chip("Reset") { iptv.setGuideOffset(feed.id, 0) }
                            Chip("+30 min") { iptv.setGuideOffset(feed.id, minutes + 30) }
                        }
                    }
                    if (iptv.epg.feeds.any { it.id == feed.id }) Chip("Remove shared guide") { iptv.removeGuide(feed.id) }
                }
            }
            Text(stringResource(R.string.tx_offsets_apply_immediately_refresh_after_a), color = Mute, fontSize = 12.sp)
            ButtonRow {
                Chip("Add shared guide") { adding = !adding }
                Chip(if (iptv.guideLoading) "Refreshing…" else "Refresh guides") { iptv.refreshGuide(source.takeIf { it.isNotEmpty() }) }
            }
            if (adding) {
                TvTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), placeholder = "Guide name")
                TvTextField(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "XMLTV URL", password = true)
                TvTextField(agent, { agent = it }, Modifier.fillMaxWidth(), placeholder = "User-Agent (optional)")
                Chip("Save guide") {
                    val error = iptv.addGuide(name, url, agent)
                    message = error ?: "Guide saved. Enable it for this playlist above."
                    if (error == null) { name = ""; url = ""; agent = ""; adding = false }
                }
            }
            message?.let { Text(it, color = Mute) }
            Text(stringResource(R.string.tx_repair_a_channel), fontSize = 16.sp)
            TvTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search channel names")
            val pool = iptv.visibleChannels.filter { it.sourceId == source && (query.isNotBlank() && it.name.contains(query.trim(), true) ||
                query.isBlank() && it.key !in iptv.guideMatches) }
            Text(if (query.isBlank()) "${pool.size} unmatched channels · showing up to 40. Search to find any channel."
                else "${pool.size} channels · showing up to 40", color = Mute, fontSize = 12.sp)
            pool.take(40).forEach { item -> Chip(item.name) { channel = item; epgSearch = ""; feedChoice = null } }
        } else {
            val item = channel!!
            val feeds = iptv.availableFeeds()
            val assigned = iptv.epg.feedIds(item.sourceId, feeds)
            val selected = feedChoice?.takeIf { it in assigned }
                ?: iptv.guideMatches[item.key]?.feedId?.takeIf { it in assigned } ?: assigned.firstOrNull()
            Text(iptv.guideMatches[item.key]?.let { match ->
                "${if (match.manual) "Manual" else "Automatic"}: " + (feeds.firstOrNull { it.id == match.feedId }?.name ?: "Guide") +
                    " · " + (iptv.parsedFeeds[match.feedId]?.labels?.get(match.channelId) ?: match.channelId)
            } ?: "No matching programme guide", color = Mute)
            ButtonRow {
                Chip("Automatic matching") { iptv.matchGuide(item, null, null) }
                Chip("Guide sources") { channel = null; source = item.sourceId }
            }
            ButtonRow { feeds.filter { it.id in assigned }.forEach { feed ->
                Chip(feed.name, selected = selected == feed.id) { feedChoice = feed.id }
            } }
            if (assigned.isEmpty()) Text(stringResource(R.string.tx_enable_a_guide_under_guide_sources), color = Mute)
            TvTextField(epgSearch, { epgSearch = it }, Modifier.fillMaxWidth(), placeholder = "Search guide channel names or IDs")
            val candidates = iptv.parsedFeeds[selected]?.labels.orEmpty().entries.filter {
                epgSearch.trim().length >= 2 && (it.value.contains(epgSearch.trim(), true) || it.key.contains(epgSearch.trim(), true))
            }.sortedBy { it.value }.take(80)
            if (epgSearch.trim().length < 2) Text(stringResource(R.string.tx_type_at_least_two_letters_refresh), color = Mute, fontSize = 12.sp)
            candidates.forEach { candidate -> Chip(candidate.value + " · " + candidate.key) {
                message = if (selected?.let { iptv.matchGuide(item, it, candidate.key) } == true)
                    "Channel matching saved." else "Encrypted storage is unavailable. Matching was not changed."
            } }
            message?.let { Text(it, color = Mute) }
        }
    }
}
