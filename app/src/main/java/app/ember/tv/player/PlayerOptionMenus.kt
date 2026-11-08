package app.ember.tv.player

import app.ember.tv.iptv.*
import app.ember.tv.R
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch
import java.util.Locale
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed

// PlayerActivity's quick and options menus: rows, labels and the picture/sound settings they change.
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.openMenu(which: String, tab: String?) {
    updateSubtitleSheet()
    controls.audioTracks = audioTracks()
    controls.menuTab = tab ?: "playback"
    controls.menuRow = null
    controls.menuFocus = ""
    controls.menu = which
    controls.visible = false
    controls.optionsTick++
}

/** Addon sources: chapters from the MKV file itself, when the media server gave none. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.loadFileChapters(p: ExoPlayer) {
    playbackEngine?.compatibility?.chapters()?.takeIf {it.isNotEmpty()}?.let {chapters=it;return}
    if (!advanced || trailer || chapters.isNotEmpty()) return
    val url = currentUrl ?: return
    if (!url.startsWith("http")) return
    val mkv = p.videoFormat?.containerMimeType == MimeTypes.VIDEO_MATROSKA ||
        p.audioFormat?.containerMimeType == MimeTypes.VIDEO_MATROSKA ||
        url.substringBefore('?').endsWith(".mkv", ignoreCase = true)
    if (!mkv) return
    val forUrl = url
    metadataLoads.load({ MkvChapters.read(forUrl) }) { found ->
        if (found.size > 1 && currentUrl == forUrl && chapters.isEmpty()) {
            chapters = found
            changed()
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.closeMenu() {
    controls.closeMenuSession()
    showControls()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.changed() {
    if(itemInitialized)refreshControlsTitle()
    controls.optionsTick++
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.passthrough(): Boolean {
    playbackEngine?.compatibility?.let {return it.isPassthrough()}
    if(!config.passthrough||codecs.pcm)return false
    val mime = player?.audioFormat?.sampleMimeType ?: return false
    return audioDecoder == null && mime in setOf(
        MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_DTS_X, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS,
    )
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.speedLabel(x: Float) = if (x == 1f) getString(R.string.pl_normal) else String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.') + "×"

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.boostLabel(i: Int) = if (i == 0) getString(R.string.ui_off) else "+${boosts[i] / 100} dB"

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.delayLabel(ms: Int) = when {
    ms == 0 -> getString(R.string.pl_delay_zero)
    ms > 0 -> getString(R.string.pl_delay_later, ms)
    else -> getString(R.string.pl_delay_earlier, ms)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.clock(ms: Long) = PlaybackErrors.clock(ms)

/** The rows of a menu: "quick", "video", "audio" or "subtitles". */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.optionRows(which: String): List<OptionRow> {
    val p = player ?: return emptyList()
    val tracks = controls.audioTracks.ifEmpty { audioTracks() }
    val audioIdx = tracks.indexOfFirst { it.second }
    val audioRow = OptionRow(
        getString(R.string.pl_sound_track), tracks.getOrNull(audioIdx)?.first ?: getString(R.string.ui_none), tracks.map { it.first }, audioIdx, MenuIcons.audio,
        onPick = { i ->
            pickAudioTrack(i)
            handler.postDelayed({ controls.audioTracks = audioTracks(); changed() }, 400)
        },
    )
    val subOpts = controls.subtitleOptions
    val subIdx = subOpts.indexOfFirst { it.second }
    val subRow = OptionRow(
        getString(R.string.settings_subtitles), subOpts.getOrNull(subIdx)?.first ?: getString(R.string.ui_off), subOpts.map { it.first }, subIdx, MenuIcons.subtitles,
        onPick = { i ->
            subtitleActions.getOrNull(i)?.let { subsTouched = true; it() }
            updateSubtitleSheet()
        },
    )
    val pass = passthrough()
    val boostRow = OptionRow(
        getString(R.string.pl_volume_boost), boostLabel(boostLevel), boosts.indices.map { boostLabel(it) }, boostLevel, MenuIcons.boost,
        note = if (pass) getString(R.string.pl_no_effect_while_the_sound_goes) else null,
        onPick = { setBoost(it) },
    )
    val speedRow = OptionRow(
        getString(R.string.pl_playback_speed), speedLabel(speed), speeds.map { speedLabel(it) }, speeds.indexOf(speed), MenuIcons.speed,
        note = if (pass) getString(R.string.pl_with_passthrough_the_sound_may_not) else null,
        onPick = { i -> speed = speeds[i]; p.setPlaybackSpeed(speed) },
    )
    return when (which) {
        "quick" -> PlayerMenuLayout.categories.map { (id,label) ->
            OptionRow(label,icon=when(id){"audio"->MenuIcons.audio;"subtitles"->MenuIcons.subtitles;"playback"->MenuIcons.speed;else->MenuIcons.more},action={openMenu("options",id)})
        }
        "video" -> buildList {
            val engines=listOf("auto","standard","compatibility")
            add(OptionRow("Playback engine",if(playbackEngine?.compatibility!=null)"Compatibility" else "Standard",listOf("Automatic","Standard","Compatibility"),engines.indexOf(config.engine),onPick={i -> config=config.copy(engine=engines[i]);prefs.playbackConfig=config;playbackEngine?.mode(engines[i])}))
            playbackEngine?.compatibility?.let { native ->
                fun picture(){native.picture(config.deband,config.interpolation,config.scaler,config.deinterlacing)}
                val deinterlaceModes=listOf("off","auto","always")
                add(OptionRow("Deinterlacing",listOf("Off","Automatic","Always")[deinterlaceModes.indexOf(config.deinterlacing).coerceAtLeast(0)],listOf("Off","Automatic","Always"),deinterlaceModes.indexOf(config.deinterlacing).coerceAtLeast(0),
                    note="Automatic handles interlaced video. Always is for streams detected incorrectly. Uses more processing; leave Off for progressive video.",
                    onPick={config=config.copy(deinterlacing=deinterlaceModes[it]);prefs.playbackConfig=config;picture()}))
                // On/off settings are switches that flip on OK (#105).
                add(OptionRow("Debanding",toggle=config.deband,action={config=config.copy(deband=!config.deband);prefs.playbackConfig=config;picture()}))
                add(OptionRow("Frame smoothing",toggle=config.interpolation,note="Uses more graphics processing. Turn off if playback slows.",action={config=config.copy(interpolation=!config.interpolation);prefs.playbackConfig=config;picture()}))
                val scales=listOf("bilinear","bicubic","lanczos")
                add(OptionRow("Upscaling",config.scaler,scales,scales.indexOf(config.scaler).coerceAtLeast(0),onPick={config=config.copy(scaler=scales[it]);prefs.playbackConfig=config;picture()}))
            }
            val z = zooms.indexOfFirst { it.first == config.zoom }.coerceAtLeast(0)
            add(OptionRow(getString(R.string.pl_zoom), zooms[z].second, zooms.map { it.second }, z, onPick = { i -> config = config.copy(zoom = zooms[i].first); prefs.playbackConfig = config; applyZoom() }))
            add(OptionRow(getString(R.string.pl_aspect_ratio), aspects[aspect].first, aspects.map { it.first }, aspect, onPick = { i -> aspect = i; applyAspect() }))
            val ff = playbackEngine?.compatibility?.softwareVideo ?: codecs.ffmpegVideo
            add(
                OptionRow(
                    getString(R.string.pl_video_decoder), if (ff) getString(R.string.pl_ffmpeg_software) else getString(R.string.pl_tv_s_decoder), listOf(getString(R.string.pl_tv_s_decoder), getString(R.string.pl_ffmpeg_software)), if (ff) 1 else 0,
                    note = getString(R.string.pl_ffmpeg_plays_formats_the_tv_s),
                    onPick = { i -> if ((i == 1) != (playbackEngine?.compatibility?.softwareVideo ?: codecs.ffmpegVideo)) switchVideoDecoder(i == 1) },
                ),
            )
            val h = huds.indexOfFirst { it.first == config.hud }.coerceAtLeast(0)
            add(
                OptionRow(getString(R.string.pl_playback_stats_hud), huds[h].second, huds.map { it.second }, h, onPick = { i ->
                    config = config.copy(hud = huds[i].first)
                    prefs.playbackConfig = config
                    handler.removeCallbacks(statsTicker)
                    handler.post(statsTicker)
                }),
            )
        }
        "playback" -> buildList {
            if (chapters.isNotEmpty()) {
                val pos = p.currentPosition
                val cur = chapters.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
                add(OptionRow(getString(R.string.pl_chapters), clock(chapters[cur].startMs), chapters.map { clock(it.startMs) + "   " + it.name }, cur, onPick = { i -> p.seekTo(chapters[i].startMs) }))
            }
            add(speedRow)
            if(!live) {
                val modes=listOf("off","one","all");val mode=fileQueue?.repeat ?: if(p.repeatMode==Player.REPEAT_MODE_ONE)"one" else "off"
                val labels=if(fileQueue!=null)listOf("Off","Repeat item","Repeat queue") else listOf("Off","Repeat item")
                add(OptionRow("Repeat",labels.getOrElse(modes.indexOf(mode)){"Off"},labels,modes.indexOf(mode).coerceAtLeast(0),onPick={i->if(fileQueue!=null)fileQueue!!.repeat=modes[i] else p.repeatMode=if(i==1)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF}))
                add(OptionRow("Set repeat start",repeatRange.start?.let { clock(it) }.orEmpty(),action={repeatRange.markStart(p.currentPosition);changed()}))
                if(repeatRange.start!=null)add(OptionRow("Set repeat end",repeatRange.end?.let { clock(it) }.orEmpty(),action={if(!repeatRange.markEnd(p.currentPosition))Toast.makeText(this@optionRows,"Choose an end at least one second after the start.",Toast.LENGTH_SHORT).show();changed()}))
                if(repeatRange.start!=null)add(OptionRow("Clear repeat range",action={repeatRange.clear();changed()}))
                fileQueue?.let {q->
                    add(OptionRow("Queue","${q.index+1} of ${q.items.size}",action={showFileQueue()}))
                    add(OptionRow("Add files to queue",action={pickFile("queue")}))
                }
            }
        }
        "audio" -> buildList {
            add(audioRow)
            add(OptionRow("Load audio file", "Whole-title soundtrack · compatibility playback", action={pickFile("audio")}))
            add(boostRow)
            val ms = (audioDelay.us / 1000).toInt()
            val di = delays.indices.minByOrNull { kotlin.math.abs(delays[it] - ms) } ?: 10
            add(
                OptionRow(getString(R.string.pl_delay), delayLabel(ms), delays.map { delayLabel(it) }, di,
                    note = getString(R.string.pl_lip_sync_later_sound_fixes_a),
                    onPick = { i -> audioDelay.us = delays[i] * 1000L; playbackEngine?.compatibility?.audioDelay(delays[i].toLong()); updateSoundLabel() }),
            )
            if (ms != config.audioDelayMs) add(OptionRow(getString(R.string.pl_keep_delay), action = { keepSound(); changed() }))
        }
        "subtitles" -> buildList {
            add(subRow)
            add(OptionRow("Load subtitle file", "SRT, VTT, ASS, TTML, SUP or IDX + SUB",action={pickFile("sub")}))
            playbackEngine?.compatibility?.let { native ->
                val options=p.currentTracks.groups.filter {it.type==C.TRACK_TYPE_TEXT&&!it.isSelected}
                val ids=listOf<Int?>(null)+options.map {it.mediaTrackGroup.id.substringAfterLast(':').toInt()}
                val labels=listOf("Off")+options.map { val f=it.getTrackFormat(0);listOfNotNull(f.language?.let(app.ember.tv.data.Subtitles::languageName),f.label).joinToString(" · ") }
                add(OptionRow("Secondary subtitles",labels.getOrElse(ids.indexOf(native.secondaryId)){"Off"},labels,ids.indexOf(native.secondaryId).coerceAtLeast(0),onPick={native.secondary(ids[it])}))
                add(OptionRow("Secondary timing","Adjust separately",subShifts.map {String.format(Locale.US,"%+.2f s",it/1000.0)},20,onPick={native.secondaryDelay(subShifts[it])}))
            } ?: add(OptionRow("Dual subtitles","Use compatibility playback",action={playbackEngine?.switch();changed()}))
            val subtitleSpeeds=listOf(0.95904f,0.96f,1f,1.001f,1.041667f,1.042709f)
            val subSpeed=if(subsInitialized)subs.engine.speed else 1f
            add(OptionRow("Subtitle speed",String.format(Locale.US,"%.3f×",subSpeed),subtitleSpeeds.map {String.format(Locale.US,"%.3f×",it)},subtitleSpeeds.indexOf(subSpeed).coerceAtLeast(0),onPick={val value=subtitleSpeeds[it];subs.engine.speed=value;p.subtitleSpeed=value;playbackEngine?.compatibility?.subtitleSpeed(value)}))
            val off = if (subsInitialized) subs.engine.offsetMs else 0L
            val si = subShifts.indices.minByOrNull { kotlin.math.abs(subShifts[it] - off) } ?: 20
            add(
                OptionRow(getString(R.string.pl_timing), String.format(Locale.US, "%+.2f s", off / 1000.0), subShifts.map { String.format(Locale.US, "%+.2f s", it / 1000.0) }, si,
                    note = getString(R.string.pl_for_downloaded_subtitles_positive_the_text),
                    onPick = { i -> if (subsInitialized) { subs.engine.offsetMs = subShifts[i]; p.subtitleDelayMilliseconds = subShifts[i]; playbackEngine?.compatibility?.subtitleDelay(subShifts[i]); refreshSubtitlePanel() } }),
            )
            val c = prefs.subtitleConfig
            fun look(title: String, opts: List<Pair<String, String>>, cur: String, set: (String) -> app.ember.tv.data.SubtitleConfig) {
                val i = opts.indexOfFirst { it.first == cur }.coerceAtLeast(0)
                add(OptionRow(title, opts[i].second, opts.map { it.second }, i, onPick = { j -> applySubLook(set(opts[j].first)) }))
            }
            look(getString(R.string.pl_size), subSizes, c.size) { c.copy(size = it) }
            look(getString(R.string.pl_position), subPositions, c.position) { c.copy(position = it) }
            look(getString(R.string.pl_style), subLooks, c.background) { c.copy(background = it) }
            add(OptionRow(getString(R.string.pl_get_more), action = { closeMenu(); showInfo() }))
        }
        else -> emptyList()
    }
}

/** Subtitle size, position and style, changed while watching; kept for every video. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.applySubLook(c: app.ember.tv.data.SubtitleConfig) {
    prefs.subtitleConfig = c
    baseSubCfg = baseSubCfg?.copy(size = c.size, position = c.position, background = c.background) ?: c
    view.subtitleView?.let { SubtitleEngine.style(it, c) }
    playbackEngine?.compatibility?.captionStyle(c)
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.setBoost(i: Int) {
    boostLevel = i
    val p = player ?: return
    playbackEngine?.compatibility?.let {it.volumeBoost(boosts[i]/100.0);return}
    // Session 0 is the whole TV's output; never touch it.
    if (i > 0 && p.audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
        boostLevel = 0
        Toast.makeText(this, getString(R.string.pl_volume_boost_is_not_available_until), Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        if (i == 0) {
            loudness?.enabled = false
            return
        }
        val le = loudness ?: android.media.audiofx.LoudnessEnhancer(p.audioSessionId).also { loudness = it }
        le.setTargetGain(boosts[i])
        le.enabled = true
    }.onFailure {
        boostLevel = 0
        Toast.makeText(this, getString(R.string.pl_volume_boost_is_not_available_on), Toast.LENGTH_SHORT).show()
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.applyZoom() {
    playbackEngine?.compatibility?.geometry(config.zoom,aspects[aspect].second)
    view.resizeMode = when (config.zoom) {
        "crop" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
}

/** Forces a picture shape; Auto goes back to the video's own. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.applyAspect() {
    playbackEngine?.compatibility?.geometry(config.zoom,aspects[aspect].second)
    val frame = view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return
    val r = aspects[aspect].second
    if (r > 0f) {
        frame.setAspectRatio(r)
    } else {
        val v = player?.videoSize ?: return
        if (v.width > 0 && v.height > 0) frame.setAspectRatio(v.width * v.pixelWidthHeightRatio / v.height)
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun PlayerActivity.switchVideoDecoder(ffmpeg: Boolean) {
    val mode=if(ffmpeg)DecoderMode.FFMPEG else DecoderMode.AUTO
    userVideoMode=mode
    codecs.ffmpegVideo=ffmpeg
    if(playbackEngine?.compatibility!=null)playbackEngine?.compatibility?.softwareVideo(ffmpeg) else decoderManager.selectVideoDecoder(mode)
}
