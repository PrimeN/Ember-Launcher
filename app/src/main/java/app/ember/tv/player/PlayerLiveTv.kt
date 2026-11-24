package app.ember.tv.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*

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
    showPlayerActions("Recent channels", channels.map { channel ->
        PlayerDialogAction((channel.number?.let { "$it · " } ?: "") + channel.name + " · " + liveSession?.sourceLabel(channel).orEmpty(), Icons.Outlined.LiveTv) {
            tuneChannel(channel.key)
        }
    })
}

/** #434: opens the guide over the video on the channel you're watching. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.openLiveGuide() {
    val session = liveSession ?: return
    val channels = session.channels()
    if (channels.isEmpty()) return
    handler.removeCallbacks(hideLiveBanner); liveBanner.value = null
    val at = channels.indexOfFirst { it.key == session.currentKey }.coerceAtLeast(0)
    liveGuide.value = app.ember.tv.ui.LiveGuideState(channels, at, session.currentKey, System.currentTimeMillis())
    keepLiveGuide()
}

private const val LIVE_GUIDE_IDLE_MS = 20_000L

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.keepLiveGuide() {
    handler.removeCallbacks(closeLiveGuide)
    handler.postDelayed(closeLiveGuide, LIVE_GUIDE_IDLE_MS)
}

/** Up/Down (held to scroll) pick a channel, channel up/down jump a page, OK watches it, Back or Right closes, Left shows recent channels. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.handleLiveGuideKey(event: android.view.KeyEvent): Boolean {
    val g = liveGuide.value ?: return false
    val down = event.action == android.view.KeyEvent.ACTION_DOWN
    fun move(by: Int) { if (down) { liveGuide.value = g.moved(by); keepLiveGuide() } }
    when (event.keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> move(-1)
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
        android.view.KeyEvent.KEYCODE_CHANNEL_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> move(-app.ember.tv.ui.LiveGuideState.PAGE)
        android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> move(app.ember.tv.ui.LiveGuideState.PAGE)
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (!down && event.repeatCount == 0) {
                val pick = g.channels.getOrNull(g.selected)
                closeLiveGuide.run()
                if (pick != null && pick.key != liveSession?.currentKey) { tuneChannel(pick.key); showLiveBanner() }
            }
        }
        android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (!down) closeLiveGuide.run()
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (!down) { closeLiveGuide.run(); showRecentChannels() }
        else -> return false
    }
    return true
}
