package app.ember.tv.player

import app.ember.tv.iptv.*
import app.ember.tv.R
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.ember.tv.tracker.Kind
import kotlinx.coroutines.launch
import java.util.Locale
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds

// PlayerActivity's skip-intro markers, sound timing, info panel and subtitle panel.
/** Server markers take priority; eligible IMDb titles can fall back to the public IntroDB lookup. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.loadMarkers() {
    metadataLoads.reset()
    markers = emptyList()
    markersFromIntroDb = false
    autoSkipped.clear()
    showSkip(null)
    controls.trickplay = null
    chapters = emptyList()
    val ref = serverRef
    val s = servers
    val sourceId = serverPlayback?.sourceId
    if (advanced && !trailer && ref != null && s != null) {
        metadataLoads.load({ s.trickplay(ref, sourceId) }) { controls.trickplay = it }
        metadataLoads.load({ s.chapters(ref) }) { chapters = it }
    }
    if (trailer || config.skipIntro == "off") return
    val query = if (config.introDb && !live) IntroDbQuery.from(item) else null
    metadataLoads.load({ resolveMarkers(
        server = { if (ref != null && s != null) s.markers(ref) else emptyList() },
        database = { query?.let { introDbClient.markers(it) }.orEmpty() },
    ) }) { markers = it.markers; markersFromIntroDb = it.introDb }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.checkMarkers() {
    val p = player ?: return
    if (subsInitialized && p.playbackState==Player.STATE_READY) subs.onReady()
    serverPlayback?.takeIf { it.parts.size > 1 }?.let { transport ->
        val index = playbackEngine?.compatibility?.partIndex ?: transport.seek(p.currentPosition)?.first ?: p.currentPeriodIndex.coerceIn(transport.parts.indices)
        if (index != serverPartIndex) {
            serverPartIndex = index
            val part = transport.parts[index]
            if (subsInitialized) subs.startSearch(part.url, app.ember.tv.data.Subtitles.Query(
                imdb = item.imdbId, movie = item.kind == Kind.MOVIE, season = item.season, episode = item.episode,
                release = item.title, hash = null, size = null), part.subtitles, preserve = true)
        }
    }
    if (markers.isEmpty() || ended || config.skipIntro == "off" || markersFromIntroDb && !config.introDb) return showSkip(null)
    val pos = p.currentPosition
    val eligible = if (markersFromIntroDb) IntroDbSegments.forRuntime(markers, p.duration) else markers
    val m = eligible.firstOrNull { pos >= it.startMs && pos < it.endMs - 1_000 }
    if (m != null && m.kind != "credits" && config.skipIntro == "auto" && m !in autoSkipped) {
        autoSkipped += m
        p.seekTo(m.endMs)
        Toast.makeText(this, if (m.kind == "recap") getString(R.string.pl_skipped_the_recap) else getString(R.string.pl_skipped_the_intro), Toast.LENGTH_SHORT).show()
        return showSkip(null)
    }
    showSkip(m)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showSkip(m: app.ember.tv.mediaserver.Marker?) {
    if (!skipButtonInitialized) return
    skipTarget = m
    if (advanced) {
        // The advanced controls draw their own pill.
        skipButton.visibility = View.GONE
        controls.skipLabel = m?.let {
            when (it.kind) {
                "recap" -> getString(R.string.pl_skip_recap)
                "credits" -> if (nextEpisodes.isNotEmpty()) getString(R.string.ui_next_episode) else getString(R.string.pl_skip_credits)
                else -> getString(R.string.ui_skip_intro)
            }
        }
        return
    }
    if (m == null) {
        skipButton.visibility = View.GONE
        return
    }
    skipButton.text = when (m.kind) {
        "recap" -> getString(R.string.pl_action_arrow, getString(R.string.pl_skip_recap))
        "credits" -> getString(R.string.pl_action_arrow, getString(if (nextEpisodes.isNotEmpty()) R.string.ui_next_episode else R.string.pl_skip_credits))
        else -> getString(R.string.pl_action_arrow, getString(R.string.ui_skip_intro))
    }
    skipButton.visibility = View.VISIBLE
}

/** OK while the Skip button shows: jump past the marker; in the credits, go to the next episode. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.doSkip() {
    val p = player ?: return
    val m = skipTarget ?: return
    if (m.kind == "credits") {
        // Ending the video starts the usual "Up next" countdown (or closes the player).
        val d = p.duration
        if (d > 0) p.seekTo(d) else p.seekTo(m.endMs)
    } else {
        p.seekTo(m.endMs)
    }
    showSkip(null)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.shiftSound(deltaMs: Int?) {
    val now = (audioDelay.us / 1000).toInt()
    val next = if (deltaMs == null) 0 else (now + deltaMs).coerceIn(-1000, 1000)
    audioDelay.us = next * 1000L
    updateSoundLabel()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.keepSound() {
    val ms = (audioDelay.us / 1000).toInt()
    config = config.copy(audioDelayMs = ms)
    prefs.playbackConfig = config
    Toast.makeText(this, getString(R.string.pl_sound_timing_saved_for_all_videos), Toast.LENGTH_SHORT).show()
    updateSoundLabel()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.updateSoundLabel() {
    if (!soundLabelInitialized) return
    val ms = (audioDelay.us / 1000).toInt()
    val state = when {
        ms == 0 -> getString(R.string.pl_sound_in_step)
        ms > 0 -> getString(R.string.pl_sound_later, ms)
        else -> getString(R.string.pl_sound_earlier, -ms)
    }
    val timing = getString(R.string.pl_sound_timing, state)
    soundLabel.text = if (ms != config.audioDelayMs) getString(R.string.pl_label_pair, timing, getString(R.string.pl_this_video_only)) else timing
}

/** Switches the display to a refresh rate (and optionally resolution) that fits the video, once per title. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.matchDisplayMode(p: ExoPlayer) {
    if (config.frameRate != "always" || modeApplied) return
    val f = p.videoFormat ?: return
    modeApplied = true
    val display = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
    display ?: return
    Playback.applyMode(this, Playback.modeFor(display, f.frameRate, f.width, f.height, config.matchResolution))
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.infoText(): String {
    val p = player ?: return ""
    val display = if (android.os.Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay
    val mode = display?.mode?.let { m -> getString(R.string.pl_info_display, "${m.physicalWidth}×${m.physicalHeight} @ ${String.format(Locale.US, "%.3f", m.refreshRate).trimEnd('0').trimEnd('.')} Hz") }
    val buffer = getString(R.string.pl_info_buffered, ((p.bufferedPosition - p.currentPosition) / 1000).coerceAtLeast(0))
    return listOfNotNull(
        Playback.describeVideo(p.videoFormat, videoDecoder, hdr10PlusMetadata && playbackEngine?.compatibility == null, playbackEngine?.compatibility != null),
        Playback.describeAudio(p.audioFormat, audioDecoder),
        mode,
        buffer,
        getString(R.string.pl_info_hide),
    ).joinToString("\n")
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showInfo(hideAfterMs: Long = 0) {
    panel.visibility = View.VISIBLE
    refreshSubtitlePanel()
    handler.removeCallbacks(infoUpdater)
    handler.post(infoUpdater)
    handler.removeCallbacks(hideInfo)
    if (hideAfterMs > 0) {
        handler.postDelayed(hideInfo, hideAfterMs)
    } else {
        // Opened by hand: give the remote to the subtitle buttons.
        subList.getChildAt(0)?.requestFocus()
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.hidePanel() {
    if (!panelInitialized) return
    panel.visibility = View.GONE
    panel.scrollTo(0,0)
    view.requestFocus()
}

/** Rebuilds the subtitle choices: off, the video's own tracks, then downloads (best first). */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.refreshSubtitlePanel() {
    if (!subListInitialized || !subsInitialized) return
    val hadFocus = subList.findFocus() != null
    subStatus.text = subs.status ?: ""
    subStatus.visibility = if (subs.status == null) View.GONE else View.VISIBLE
    subList.removeAllViews()
    val sel = subs.selection
    fun add(label: String, chosen: Boolean, action: () -> Unit) {
        subList.addView(styled(Button(this)).apply {
            text = if (chosen) getString(R.string.pl_chosen_option, label) else label
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setOnClickListener {
                subsTouched = true
                action()
            }
        })
    }
    add(getString(R.string.ui_off), sel is SubtitleController.Selection.Off) { subs.selectOff() }
    subs.embedded().forEach { (g, t, label) ->
        add(getString(R.string.pl_in_the_video, label), sel == SubtitleController.Selection.Embedded(g, t)) { subs.selectEmbedded(g, t) }
    }
    subs.candidates.take(24).forEach { c ->
        val chosen = sel is SubtitleController.Selection.External && sel.candidate == c
        val label = listOfNotNull(
            if (c.hashMatch) "★" else null,
            c.languageName,
            if (c.forced) "Forced" else null, if (c.hearingImpaired) getString(R.string.pl_hi) else null,
            c.provider,
            c.release?.take(60),
        ).joinToString(" · ")
        add(label, chosen) { subs.selectExternal(c) }
    }
    val subtitleTiming = getString(R.string.pl_subtitle_timing, String.format(Locale.US, "%+.1f s", subs.engine.offsetMs / 1000.0))
    timingLabel.text = if (sel is SubtitleController.Selection.External) subtitleTiming
        else getString(R.string.pl_label_pair, subtitleTiming, getString(R.string.pl_for_downloaded))
    updateSoundLabel()
    if (hadFocus && panelVisible) subList.getChildAt(0)?.requestFocus()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.shiftSubtitles(deltaMs: Long?) {
    if (!subsInitialized) return
    subs.engine.offsetMs = if (deltaMs == null) 0 else subs.engine.offsetMs + deltaMs
    player?.subtitleDelayMilliseconds = subs.engine.offsetMs
    playbackEngine?.compatibility?.subtitleDelay(subs.engine.offsetMs)
    refreshSubtitlePanel()
    updateSubtitleSheet()
}

/** Fills the advanced Subtitles sheet: off, the video's own tracks, then the best downloads. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.updateSubtitleSheet() {
    if (!advanced || !subsInitialized) return
    val sel = subs.selection
    val rows = ArrayList<Pair<String, Boolean>>()
    val acts = ArrayList<() -> Unit>()
    rows += getString(R.string.ui_off) to (sel is SubtitleController.Selection.Off); acts += { subs.selectOff() }
    subs.embedded().forEach { (g, t, label) ->
        rows += getString(R.string.pl_in_the_video, label) to (sel == SubtitleController.Selection.Embedded(g, t)); acts += { subs.selectEmbedded(g, t) }
    }
    subs.candidates.take(12).forEach { c ->
        val label = listOfNotNull(if (c.hashMatch) "★" else null, c.languageName, if (c.forced) "Forced" else null, if (c.hearingImpaired) getString(R.string.pl_hi) else null, c.provider).joinToString(" · ")
        rows += label to (sel is SubtitleController.Selection.External && sel.candidate == c); acts += { subs.selectExternal(c) }
    }
    if(sel is SubtitleController.Selection.Manual){rows+=sel.label to true;acts+={}}
    subtitleActions = acts
    controls.subtitleOptions = rows
    controls.subtitleShift = subs.engine.offsetMs
    changed()
}
