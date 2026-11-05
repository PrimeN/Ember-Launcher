package app.ember.tv.player

import app.ember.tv.iptv.*
import android.widget.Toast

// Live TV in PlayerActivity: channel banner, zapping, number entry and per-channel headers.
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showLiveBanner() {
    if (!live) return
    liveBanner.value = liveSession?.information(System.currentTimeMillis())
    handler.removeCallbacks(hideLiveBanner)
    handler.postDelayed(hideLiveBanner, 5000)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.syncSession(delta: Int = 0, selection: Channel? = null): Boolean {
    val session = liveSession ?: return false
    val selected = selection ?: session.select(delta) ?: return false
    sessionChannel = selected
    val list = session.channels()
    zapUrls = list.map { it.url }.toTypedArray()
    zapNames = list.map { it.name }.toTypedArray()
    zapAgents = list.map { it.userAgent.orEmpty() }.toTypedArray()
    zapReferrers = list.map { it.referrer.orEmpty() }.toTypedArray()
    zapIndex = list.indexOfFirst { it.key == selected.key }.coerceAtLeast(0)
    return true
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.applyChannelHeaders(i: Int) {
    val channel = Channel("", "", "", "", userAgent = zapAgents.getOrNull(i), referrer = zapReferrers.getOrNull(i))
    sourceHeaders=ChannelHeaders.properties(channel)+mapOf("User-Agent" to ChannelHeaders.agent(channel))
    httpFactory.setUserAgent(ChannelHeaders.agent(channel))
    httpFactory.setDefaultRequestProperties(ChannelHeaders.properties(channel))
}

/** An addon stream's own headers (User-Agent, Referer, Origin...); none restores the defaults. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.applyStreamHeaders(headers: Map<String, String>) {
    val agent = app.ember.tv.data.StreamHeaders.userAgent(headers) ?: ChannelHeaders.DEFAULT_AGENT
    val rest = app.ember.tv.data.StreamHeaders.withoutUserAgent(headers)
    sourceHeaders = rest + ("User-Agent" to agent)
    httpFactory.setUserAgent(agent)
    httpFactory.setDefaultRequestProperties(rest)
}

/** Channel up (+1) or down (-1) through the group the channel was opened from. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.zap(delta: Int) {
    if (liveSession != null) {
        if (!syncSession(delta)) { finish(); return }
    } else {
        if (zapUrls.size < 2) return
        zapIndex = (zapIndex + delta + zapUrls.size) % zapUrls.size
    }
    applyChannelHeaders(zapIndex)
    liveBudget.reset()
    val name = zapNames.getOrNull(zapIndex).orEmpty()
    Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
    start(zapUrls[zapIndex], name, emptyList(), null)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.tuneChannel(key: String) {
    val channel = liveSession?.selectKey(key) ?: return
    if (!syncSession(selection = channel)) return
    applyChannelHeaders(zapIndex); liveBudget.reset()
    start(channel.url, channel.name, emptyList(), null)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showRecentChannels() {
    handler.removeCallbacks(commitNumber); numberDigits = ""; numberToast?.cancel()
    val channels = liveSession?.recent().orEmpty()
    if (channels.isEmpty()) return
    android.app.AlertDialog.Builder(this).setTitle("Recent channels")
        .setItems(channels.map { (it.number?.let { n -> "$n · " } ?: "") + it.name + " · " + liveSession?.sourceLabel(it).orEmpty() }.toTypedArray()) { _, index -> tuneChannel(channels[index].key) }
        .setNegativeButton("Close", null).show()
}
