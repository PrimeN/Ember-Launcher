package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Info
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.HomeViewModel
import app.ember.tv.data.CatalogDef
import androidx.compose.ui.platform.testTag
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Prefs
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.LinkMode
import app.ember.tv.tracker.Tracker
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import app.ember.tv.MainActivity
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Legal
import app.ember.tv.data.About
import app.ember.tv.data.MetaEnhance
import androidx.compose.ui.draw.clip
import app.ember.tv.data.Meta
import app.ember.tv.data.Posters
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Backup
import app.ember.tv.data.Profile
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings > Player: which player and how it behaves. */
internal val PLAYER_KEYS = listOf("player-choice", "player_look", "resume", "leaving", "skip-intro", "next-episode", "trailers", "playback_info")
/** Settings > Audio & video: the built-in player's technical sound and picture options. */
internal val AUDIO_VIDEO_KEYS = listOf("audio-output", "audio-timing", "decoding", "dolby_vision", "display", "buffer")
/** Settings > Languages & subtitles starts with the preferred sound track. */
internal val LANGUAGE_KEYS = listOf("audio_track")
internal fun androidx.compose.foundation.lazy.LazyListScope.playerPage(vm: HomeViewModel, keys: List<String> = PLAYER_KEYS) {
    keys.forEach { key -> item(key = "player:$key") { PlayerPart(vm, key) } }
}
@Composable
private fun PlayerPart(vm: HomeViewModel, key: String) {
    val c = vm.playerSettings.playbackConfig
    when (key) {
        "player-choice" -> Section(stringResource(R.string.ui_player), stringResource(R.string.ui_used_when_you_pick_a_source)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                item(key = "builtin") {
                    Chip(stringResource(R.string.ui_built_in), selected = vm.playing.playerPref == Prefs.PLAYER_BUILTIN) { vm.playing.updatePlayerPref(Prefs.PLAYER_BUILTIN) }
                }
                item(key = "ask") {
                    Chip(stringResource(R.string.ui_ask_every_time), selected = vm.playing.playerPref == Prefs.PLAYER_ASK) { vm.playing.updatePlayerPref(Prefs.PLAYER_ASK) }
                }
                items(vm.playing.players, key = { it.pkg }) { p ->
                    Chip(p.label, selected = vm.playing.playerPref == p.pkg) { vm.playing.updatePlayerPref(p.pkg) }
                }
            }
        }
        "player_look" -> Section(stringResource(R.string.ui_player_look), stringResource(R.string.ui_advanced_ember_s_controls_with_the), id = "player_look") {
        // #207: each layout as a small picture.
        PictureChoice("player", SettingDefinitions.playerInterface.options, app.ember.tv.player.PlayerInterfaces.resolve(c, vm.look.uiStyle)) { v ->
            vm.playerSettings.updatePlaybackConfig { it.copy(playerInterface = v) }
        }
    }
        "audio-timing" -> Section(stringResource(R.string.ui_sound_timing_lip_sync), stringResource(R.string.ui_for_receivers_soundbars_and_projectors_that), id = "audio-timing") {
        AudioTimingControls(c.audioDelayMs) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(audioDelayMs = v) } }
    }
        "dolby_vision" -> Section(stringResource(R.string.ui_dolby_vision), stringResource(R.string.ui_auto_plays_dolby_vision_only_when)) {
        Choice(
            listOf("auto" to "Auto", "always" to "Always use it", "hdr10" to "Always HDR10"),
            c.dolbyVision,
        ) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(dolbyVision = v) } }
    }
        "resume" -> Section(stringResource(R.string.ui_resume), stringResource(R.string.ui_when_a_title_has_a_resume), id = "resume") {
        Choice(
            SettingDefinitions.resume.options,
            c.resume,
        ) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(resume = v) } }
    }
        "leaving" -> Section("Leaving the player", "What Back and Home do while a video plays.", id = "leaving") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(SettingDefinitions.leaving.options, if (c.confirmExit) "ask" else "leave", stringResource(R.string.tx_back_button)) { v ->
                vm.playerSettings.updatePlaybackConfig { it.copy(confirmExit = v == "ask") }
            }
            Note("Ask first shows Stop watching? with Keep watching selected. Pressing Back again leaves, so a double press still exits.")
            ToggleRow("Home: keep playing in a small window", null, c.pip) { vm.playerSettings.updatePlaybackConfig { it.copy(pip = !it.pip) } }
            Choice(app.ember.tv.player.QuickReturn.keys, app.ember.tv.player.QuickReturn.key(c), stringResource(R.string.tx_back_to_the_last_video_from)) { v ->
                vm.playerSettings.updatePlaybackConfig { app.ember.tv.player.QuickReturn.choose(it, v) }
            }
            Note("Android does not let apps stop the Home button, so Home cannot ask first. Picture-in-picture keeps the video playing in a corner where the TV supports it. " +
                "Back to the last video: for 4 hours after you leave a video part-way, press the chosen key on Home to continue where you stopped. " +
                "Press Back twice suits remotes without a Play button. Quick settings also starts with a Back to … tile unless this is Off.")
        }
    }
        "skip-intro" -> Section(stringResource(R.string.ui_skip_intro), stringResource(R.string.ui_uses_the_intro_recap_and_credits), id = "skip-intro") {
        Choice(
            SettingDefinitions.skipIntro.options,
            c.skipIntro,
        ) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(skipIntro = v) } }
        ToggleRow("IntroDB fallback", "When server markers are missing, send only the IMDb ID and episode numbers to IntroDB. Community timings can differ between releases. Skip intro Off disables lookups.", c.introDb) {
            vm.playerSettings.updatePlaybackConfig { it.copy(introDb = !it.introDb) }
        }
    }
        "audio-output" -> Section(stringResource(R.string.ui_audio_output), stringResource(R.string.ui_for_a_receiver_or_soundbar_over), id = "audio-output") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                "Passthrough (bitstream)", if (c.passthrough) null else "Off: decode everything to PCM",
                c.passthrough,
            ) { vm.playerSettings.updatePlaybackConfig { it.copy(passthrough = !it.passthrough) } }
            if (c.passthrough) {
                Choice(
                    listOf("auto" to "As HDMI reports", "manual" to "Choose myself"),
                    c.passthroughMode, "Formats",
                ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(passthroughMode = m) } }
                if (c.passthroughMode == "manual") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                        items(app.ember.tv.player.Playback.FORMAT_LABELS, key = { it.first }) { (id, label) ->
                            val on = id in c.formats
                            SwitchChip(label, on) {
                                vm.playerSettings.updatePlaybackConfig { it.copy(formats = if (on) it.formats - id else it.formats + id) }
                            }
                        }
                    }
                }
                Note(
                    stringResource(R.string.ui_dolby_truehd_dts_hd_and_atmos) +
                        "Use \"choose myself\" only if your receiver can play a format that the HDMI link does not report (common with ARC). " +
                        "A format the receiver cannot take plays as silence, so switch it off again.",
                )
            }
        }
    }
        "decoding" -> Section(stringResource(R.string.ui_decoding), stringResource(R.string.ui_ffmpeg_decodes_audio_the_tv_cannot)) {
            Choice(listOf("auto" to "Automatic","standard" to "Standard","compatibility" to "Compatibility"),c.engine, stringResource(R.string.tx_playback_engine)) {value -> vm.playerSettings.updatePlaybackConfig {it.copy(engine=value)}}
            Note("Automatic keeps Ember's controls and tries compatibility playback when a format needs it.")
            Choice(listOf("off" to "Off","auto" to "Automatic","always" to "Always"),c.deinterlacing, stringResource(R.string.tx_compatibility_deinterlacing)) { mode ->
                vm.playerSettings.updatePlaybackConfig {it.copy(deinterlacing=mode)}
            }
            Note("For interlaced Live TV or older video in Compatibility playback. Automatic follows the video's scan type; Always is for streams detected incorrectly. Uses more processing. Off is the default.")
            Note("Decoder preferences below apply to Standard playback. Compatibility has its own decoder controls while watching.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                listOf("on" to "Software when needed", "prefer" to "Prefer software", "off" to "TV decoders only"),
                c.ffmpeg, stringResource(R.string.ui_sound_decoding),
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(ffmpeg = m) } }
            Note("Software includes FFmpeg and MPEG-H. MPEG-H outputs stereo PCM. AC-4 uses the device decoder when available.")
            Choice(
                listOf(
                    "fallback" to stringResource(R.string.ui_ffmpeg_video_fallback),
                    "always" to stringResource(R.string.ui_ffmpeg_video_always),
                    "off" to stringResource(R.string.ui_ffmpeg_video_off),
                ),
                c.ffmpegVideo, stringResource(R.string.ui_video_decoding),
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(ffmpegVideo = m) } }
            ToggleRow(stringResource(R.string.ui_tunneled_playback), null, c.tunneling) {
                vm.playerSettings.updatePlaybackConfig { it.copy(tunneling = !it.tunneling) }
            }
            Note(
                stringResource(R.string.ui_video_always_uses_the_tv_s) +
                    "Tunneled playback gives tighter A/V sync on some TVs; turn it off if video stutters or will not start.",
            )
        }
    }
        "display" -> Section(stringResource(R.string.ui_display), stringResource(R.string.ui_match_the_display_to_the_video)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                listOf("always" to "Match frame rate", "seamless" to "Only if seamless", "off" to "Off"),
                c.frameRate,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(frameRate = m) } }
            if (c.frameRate == "always") {
                ToggleRow(stringResource(R.string.ui_match_resolution_too), null, c.matchResolution) {
                    vm.playerSettings.updatePlaybackConfig { it.copy(matchResolution = !it.matchResolution) }
                }
            }
            Note(
                stringResource(R.string.ui_frame_rate_matching_plays_23_976) +
                    "Leave resolution matching off to let the projector's own scaler handle 1080p; turn it on to send 1080p as 1080p. Needs a TV or box that offers those modes.",
            )
        }
    }
        "buffer" -> Section(stringResource(R.string.ui_buffer), stringResource(R.string.ui_how_far_ahead_the_player_loads)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                listOf("normal" to "Normal", "large" to "Large (4K remux)", "huge" to "Huge"),
                c.buffer,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(buffer = m) } }
            Note(stringResource(R.string.ui_large_keeps_up_to_two_minutes))
        }
    }
        "audio_track" -> Section(stringResource(R.string.ui_audio_track), stringResource(R.string.ui_which_track_a_title_starts_with)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                listOf("quality" to "Best quality", "language" to "My language, then quality", "default" to "File's default"),
                c.audioPick,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(audioPick = m) } }
            Note(stringResource(R.string.ui_best_quality_prefers_truehd_and_atmos))
        }
    }
        "trailers" -> Section(stringResource(R.string.ui_trailers), stringResource(R.string.ui_experimental)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(stringResource(R.string.ui_play_trailers_in_ember_experimental), null, c.trailersInApp) { vm.playerSettings.updatePlaybackConfig { it.copy(trailersInApp = !it.trailersInApp) } }
            Note(
                stringResource(R.string.ui_plays_youtube_trailers_in_the_built) +
                    "YouTube changes often, so this can stop working until Ember is updated; when it fails, the YouTube app opens instead. " +
                    "Trailers are stereo (no Atmos), and fetching streams this way is against YouTube's terms.",
            )
        }
    }
        "playback_info" -> Section(stringResource(R.string.ui_playback_info)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // #218: the two choices as pictures.
            PictureChoice("info", listOf("start" to stringResource(R.string.ui_show_at_start), "ask" to "Only when I ask"), if (c.infoOnStart) "start" else "ask") { v ->
                vm.playerSettings.updatePlaybackConfig { it.copy(infoOnStart = v == "start") }
            }
            Note(stringResource(R.string.ui_during_playback_press_info_menu_or))
        }
    }
        "next-episode" -> Section(stringResource(R.string.ui_next_episode), stringResource(R.string.ui_works_in_the_built_in_player)) {
            // #217: one slider: Off, or the countdown before the next episode plays.
            StepSlider(stringResource(R.string.ui_play_next_episode_automatically), NextEpisodeSteps.values,
                NextEpisodeSteps.of(vm.playing.autoplay, vm.playing.countdown), NextEpisodeSteps::label, width = 520.dp) { secs ->
                if (secs == 0) { if (vm.playing.autoplay) vm.playing.updateAutoplay(false) }
                else { if (!vm.playing.autoplay) vm.playing.updateAutoplay(true); vm.playing.updateCountdown(secs) }
            }
        }
    }
}
/** Next episode as slider steps (#217): 0 is off; the rest are countdown seconds. */
internal object NextEpisodeSteps {
    val values = listOf(0, 5, 10, 15, 20, 30)
    fun of(autoplay: Boolean, countdown: Int): Int = if (!autoplay) 0 else values.drop(1).minByOrNull { kotlin.math.abs(it - countdown) } ?: 10
    fun label(secs: Int): String = if (secs == 0) "Off" else "After ${secs}s"
}

// ---------------------------------------------------------------- Addons
internal fun LazyListScope.streamingPage(vm: HomeViewModel, openKeys: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev // read so the page updates when the Watchmode key changes
    item(key = "streaming") { StreamingServicesHeader(vm, openKeys) }
    if (vm.watch.watchmodeAvailable) {
        items(vm.watch.listedServices, key = { "svc:${it.externalId ?: it.id}" }) { s ->
            Row(
                modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.name, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (vm.watch.installedPackage(s) != null) "Installed" else null,
                        when (s.type) { "free" -> "Free"; "tve" -> "TV provider"; else -> null },
                    ).joinToString(" · "),
                    color = Mute,
                    fontSize = 13.sp,
                )
                val on = vm.watch.isServiceOn(s)
                SwitchChip("", on) { vm.watch.toggleService(s) }
            }
        }
    }
}

// ---------------------------------------------------------------- Play button
/** Settings > Play button: the rules for one-press play on title screens. */
@Composable
internal fun PlayButtonPage(vm: HomeViewModel) {
    val r = vm.playerSettings.playRules
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_play_button), stringResource(R.string.ui_one_press_plays_the_best_source)) {
            ToggleRow(stringResource(R.string.ui_show_the_play_button), stringResource(R.string.ui_on_title_screens_above_the_sources), r.enabled) { vm.playerSettings.updatePlayRules { it.copy(enabled = !it.enabled) } }
        }
        if (r.enabled) {
            Section(stringResource(R.string.ui_highest_picture), stringResource(R.string.ui_pick_no_higher_than_your_screen)) {
                Choice(listOf("4K" to "4K", "FHD" to "1080p", "HD" to "720p"), r.maxQuality) { q -> vm.playerSettings.updatePlayRules { it.copy(maxQuality = q) } }
            }
            Section(stringResource(R.string.ui_hdr), stringResource(R.string.ui_choose_hdr10_first_if_your_tv)) {
                Choice(
                    listOf("dv" to "Dolby Vision first", "hdr" to "HDR10 first", "sdr" to "SDR first", "any" to "No preference"),
                    r.hdr,
                ) { h -> vm.playerSettings.updatePlayRules { it.copy(hdr = h) } }
            }
            Section(stringResource(R.string.ui_skip_these_codecs), stringResource(R.string.ui_for_players_that_cannot_decode_them)) {
                ButtonRow(spacing = 10.dp) {
                    app.ember.tv.data.PlayRules.CODECS.forEach { c ->
                        Chip(c + if (c in r.avoidCodecs) ": skipped" else "", selected = c in r.avoidCodecs) {
                            vm.playerSettings.updatePlayRules { it.copy(avoidCodecs = if (c in it.avoidCodecs) it.avoidCodecs - c else it.avoidCodecs + c) }
                        }
                    }
                }
            }
            Section(stringResource(R.string.ui_largest_file), stringResource(R.string.ui_very_large_files_need_a_fast)) {
                Choice(app.ember.tv.data.PlayRules.SIZES.map { it.toString() to if (it == 0) "No limit" else "$it GB" }, r.maxSizeGb.toString()) { v ->
                    vm.playerSettings.updatePlayRules { it.copy(maxSizeGb = v.toInt()) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow(stringResource(R.string.ui_best_sound_first), stringResource(R.string.ui_atmos_and_dts_x_then_truehd), r.bestAudio) { vm.playerSettings.updatePlayRules { it.copy(bestAudio = !it.bestAudio) } }
                ToggleRow(stringResource(R.string.ui_only_sources_that_start_at_once), stringResource(R.string.ui_your_servers_cached_debrid_and_direct), r.readyOnly) { vm.playerSettings.updatePlayRules { it.copy(readyOnly = !it.readyOnly) } }
                ToggleRow(stringResource(R.string.ui_your_own_server_first), stringResource(R.string.ui_a_copy_on_your_plex_jellyfin), r.serverFirst) { vm.playerSettings.updatePlayRules { it.copy(serverFirst = !it.serverFirst) } }
            }
        }
    }
}
