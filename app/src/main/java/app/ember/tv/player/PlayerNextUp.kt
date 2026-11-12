package app.ember.tv.player

import app.ember.tv.iptv.*
import app.ember.tv.R
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.ember.tv.data.Episode
import app.ember.tv.mediaserver.ReportState
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.ScrobbleState
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

// PlayerActivity's auto-play, file queue, next episode and media-server reporting.
// ---- auto-play ----
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.readNextEpisodes() {
    val ids = intent.getStringArrayExtra(PlayRequest.EXTRA_NEXT_IDS) ?: return
    val seasons = intent.getIntArrayExtra(PlayRequest.EXTRA_NEXT_SEASONS) ?: return
    val numbers = intent.getIntArrayExtra(PlayRequest.EXTRA_NEXT_NUMBERS) ?: return
    val titles = intent.getStringArrayExtra(PlayRequest.EXTRA_NEXT_TITLES) ?: emptyArray()
    val thumbs = intent.getStringArrayExtra(PlayRequest.EXTRA_NEXT_THUMBS) ?: emptyArray()
    for (i in ids.indices) {
        val s = seasons.getOrNull(i) ?: continue
        val n = numbers.getOrNull(i) ?: continue
        nextEpisodes += Episode(ids[i], s, n, titles.getOrNull(i) ?: getString(R.string.pl_episode_n, n), null, thumbs.getOrNull(i)?.takeIf { it.isNotBlank() })
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.playFile(media:QueueMedia) {
    savePosition();resumeKey=media.key;item=item.copy(key=resumeKey,title=media.title)
    resumed=false;ended=false;startOver=false;resumeFraction=-1f;audioUrl=null
    closeMenu();controls.sheet=null;controls.details=null
    controls.localUri=media.uri.takeIf {it.startsWith("content://")}
    if(subsInitialized)subs.config=subConfigFor(prefs.subtitleConfig)
    start(media.uri,media.title,emptyList(),media.title);refreshControlsTitle()
    platformSession?.nextAvailable(fileQueue?.let {it.index<it.items.lastIndex||it.repeat=="all"}==true)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showFileQueue() {
    val queue=fileQueue?:return
    android.app.AlertDialog.Builder(this).setTitle("Queue").setItems(queue.items.mapIndexed {i,m->(if(i==queue.index)"▶ " else "")+m.title}.toTypedArray()) {_,index->
        val operations=listOf("Play","Move earlier","Move later","Remove")
        android.app.AlertDialog.Builder(this).setTitle(queue.items[index].title).setItems(operations.toTypedArray()) {_,choice->
            when(choice){0->queue.select(index)?.let { playFile(it) };1->queue.move(index,index-1);2->queue.move(index,index+1);3->queue.remove(index)};changed()
        }.show()
    }.setNegativeButton("Close",null).show()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.onEpisodeEnded() {
    fileQueue?.let {q->val next=q.next(ended=true);if(next!=null)playFile(next) else finish();return}
    if (serverRef != null && nextEpisodes.isEmpty() && nextServerJob?.isActive == true) {
        lifecycleScope.launch { nextServerJob?.join(); onEpisodeEnded() }
        return
    }
    val next = nextEpisodes.firstOrNull()
    if (!prefs.autoplay || next == null) {
        finish()
        return
    }
    view.useController = false
    // #227: already counting down since the credits began; let it finish.
    if (upNextUi.visible) return
    showUpNext(next)
}

/** Shows the Up next card and starts its countdown (#225, #227): at the credits, near the end, or when the video ends. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showUpNext(next: Episode) {
    controls.visible = false
    controls.sheet = null
    closeMenu()
    upNextUi.apply {
        series = seriesName; season = next.season; episode = next.episode; title = next.title
        // #228: the episode's own picture, else the show's.
        thumbnail = next.thumbnail ?: controls.details?.backdrop; rating = intent.getStringExtra(PlayRequest.EXTRA_AGE_RATING)
        seconds = prefs.countdown; finding = false; visible = true
    }
    upNext.visibility = View.VISIBLE
    upNext.requestFocus()
    secondsLeft = prefs.countdown
    handler.removeCallbacks(tick)
    handler.post(tick)
}

/** Back on a card shown before the end (#227): hide it and keep watching the credits; it returns when the video ends. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.dismissUpNext() {
    handler.removeCallbacks(tick)
    upNextUi.visible = false
    upNext.visibility = View.GONE
    upNextDismissed = true
    view.requestFocus()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.playNext() {
    fileQueue?.let {q->q.next()?.let { playFile(it) };return}
    handler.removeCallbacks(tick)
    val next = nextEpisodes.firstOrNull() ?: return finish()
    upNextUi.finding = true
    lifecycleScope.launch {
        val found = runCatching { NextEpisodeLoader(this@playNext, servers).load(next, prevAddon, prevQuality, serverPlayback?.quality ?: app.ember.tv.mediaserver.ServerQuality.ORIGINAL, prevBingeGroup) }.getOrNull()
        // Leaving the player cancels the lookup; that is not a missing source.
        ensureActive()
        if (found == null || found.first.url == null) {
            Toast.makeText(this@playNext, getString(R.string.pl_no_playable_source_for_the_next), Toast.LENGTH_LONG).show()
            finish()
            return@launch
        }
        val (stream, subs) = found
        rememberTracks()
        if (!ended) { savePosition(); scrobble(ScrobbleState.STOP, progress()) }
        nextEpisodes.removeAt(0)
        platformSession?.nextAvailable(nextEpisodes.isNotEmpty())
        startOver = false
        prevAddon = stream.addonName
        prevQuality = stream.quality
        prevBingeGroup = stream.bingeGroup
        sourceHash = stream.videoHash?.let { it to stream.videoSize }
        serverReport(ReportState.STOP)
        serverRef = stream.serverRef
        serverPlayback = stream.serverPlayback
        serverLifecycle = app.ember.tv.mediaserver.ServerReportLifecycle()
        if (liveSession == null) applyStreamHeaders(stream.headers)
        reportedPlaying = false
        app.ember.tv.data.LocalLibrary.startedEpisode(resumeKey, next.id, next.season, next.episode, next.title,localActivityScope)
        resumeKey = next.id
        resumeFraction = -1f
        resumed = false
        ended = false
        val title = "$seriesName S${next.season}E${next.episode}"
        item = item.copy(key = next.id, title = title, season = next.season, episode = next.episode)
        baseSubCfg?.let { base -> if (this@playNext.subsInitialized) this@playNext.subs.config = subConfigFor(base) }
        subsTouched = false

        upNext.visibility = View.GONE
        upNextUi.visible = false; upNextUi.finding = false
        view.useController = !advanced
        controls.details = controls.details?.copy(episodeTitle = next.title.takeIf { it.isNotBlank() }, synopsis = null)
        refreshControlsTitle()
        start(stream.url!!, title, (stream.subtitles + subs).distinctBy { it.url }, stream.info?.filename ?: stream.title.lineSequence().firstOrNull())
        findNextServerEpisode()
        view.requestFocus()
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.serverReport(state: ReportState) {
    val ref = serverRef ?: return
    val s = servers ?: return
    val p = player ?: return
    val transport = serverPlayback
    val actual = serverLifecycle.event(state)
    val cleanup = state == ReportState.STOP && transport?.method == "Transcode" && !transcodeStopQueued
    if (actual == null && !cleanup) return
    if (cleanup) transcodeStopQueued = true
    val duration = playbackDuration(p)
    val position = if (ended) duration else p.currentPosition
    val previous = serverReportTail
    serverReportTail = PlayerActivity.background.launch {
        previous?.join()
        try { if (actual != null) s.report(ref, actual, position, duration, transport) }
        catch (_: Exception) { /* Reporting failure cannot retain a transcode session. */ }
        finally { if (cleanup && transport != null) runCatching { s.stopTranscode(transport) } }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.showServerRecovery() {
    if (serverRecoveryShowing || isFinishing) return
    val transport = serverPlayback ?: return
    serverRecoveryShowing = true
    val choices = buildList {
        add("Retry connection")
        if (transport.quality != app.ember.tv.mediaserver.ServerQuality.ORIGINAL) add("Play Original")
        add("Back to versions")
    }
    android.app.AlertDialog.Builder(this).setTitle("Server playback unavailable")
        .setItems(choices.toTypedArray()) { _, index ->
            serverRecoveryShowing = false
            if (choices[index] == "Back to versions") { endPlayback(); return@setItems }
            savePosition(); serverReport(ReportState.STOP)
            lifecycleScope.launch {
                val s = servers ?: return@launch
                s.refreshConnections()
                val quality = if (choices[index] == "Play Original") app.ember.tv.mediaserver.ServerQuality.ORIGINAL else transport.quality
                try {
                    val source = s.playback(transport.ref.value, transport.sourceId, quality)
                    serverPlayback = source.serverPlayback
                    serverLifecycle = app.ember.tv.mediaserver.ServerReportLifecycle()
                    resumed = false; ended = false; reportedPlaying = false
                    start(source.url ?: error("No stream returned."), item.title, source.serverPlayback?.parts?.firstOrNull()?.subtitles.orEmpty(), source.title)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { showServerRecovery() }
            }
        }.setOnCancelListener { serverRecoveryShowing = false; endPlayback() }.show()
}

/** Keep server autoplay on this account, including across season boundaries. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.findNextServerEpisode() {
    nextServerJob?.cancel()
    val ref = serverRef ?: return
    val s = servers ?: return
    nextEpisodes.clear()
    platformSession?.nextAvailable(false)
    if (item.kind == Kind.MOVIE) return
    nextServerJob = lifecycleScope.launch {
        try {
            s.nextEpisode(ref)?.let { next ->
                if (serverRef == ref) { nextEpisodes.add(app.ember.tv.mediaserver.ServerMetadata.episode(next)); platformSession?.nextAvailable(true) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* A discovery failure must not interrupt the current episode. */ }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.scrobble(state: ScrobbleState, progress: Float) {
    if (trailer || fileQueue!=null) return
    // Runs outside the activity so a stop still goes out after the screen closes.
    val snapshot = item
    PlayerActivity.background.launch { hub.scrobble(snapshot, state, progress) }
}
