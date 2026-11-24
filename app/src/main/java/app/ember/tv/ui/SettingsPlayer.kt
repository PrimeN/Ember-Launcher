package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.runtime.mutableIntStateOf
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

/** #387: the switch between the everyday player options and all of them, first on both pages. */
internal const val PLAYER_MODE_KEY = "player-mode"
/** Settings > Player: which player and how it behaves. */
internal val PLAYER_KEYS = listOf(PLAYER_MODE_KEY, "player-choice", "player_look", "player-shortcuts", "resume", "seek-mode", "leaving", "skip-intro", "next-episode", "trailers", "playback_info")
/** Settings > Audio & video: the built-in player's technical sound and picture options. */
internal val AUDIO_VIDEO_KEYS = listOf(PLAYER_MODE_KEY, "audio-output", "audio-timing", "decoding", "decoder-rules", "audio-effects", "dolby_vision", "display", "buffer")
/** Settings > Languages & subtitles starts with the preferred sound track. */
internal val LANGUAGE_KEYS = listOf("audio_track")
/** #387: what Simple shows: the player, resume, next episode and lip sync. */
internal val PLAYER_SIMPLE_KEYS = setOf(PLAYER_MODE_KEY, "player-choice", "resume", "next-episode", "audio-timing")
/** The sections a page shows; a search hit on an advanced option shows them all for that visit. */
internal fun playerKeys(all: List<String>, advanced: Boolean, target: String? = null): List<String> =
    if (advanced || (target != null && target in all && target !in PLAYER_SIMPLE_KEYS)) all else all.filter { it in PLAYER_SIMPLE_KEYS }
internal fun androidx.compose.foundation.lazy.LazyListScope.playerPage(vm: HomeViewModel, keys: List<String> = PLAYER_KEYS) {
    keys.forEach { key -> item(key = "player:$key") { PlayerPart(vm, key) } }
}
@Composable
private fun PlayerPart(vm: HomeViewModel, key: String) {
    val c = vm.playerSettings.playbackConfig
    val context=LocalContext.current
    fun paragraphs(vararg parts: String) = parts.joinToString("\n\n") { it.trim() }
    when (key) {
        PLAYER_MODE_KEY -> ToggleRow("Advanced settings",
            if (vm.playerSettings.advanced) "Showing every player option" else "Showing the everyday options",
            vm.playerSettings.advanced) { vm.playerSettings.updateAdvanced(!vm.playerSettings.advanced) }
        "player-choice" -> Section(stringResource(R.string.ui_player), "Which player opens a source.", id = "player-choice", info = stringResource(R.string.ui_used_when_you_pick_a_source)) {
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
        "player_look" -> Section(stringResource(R.string.ui_player_look), "How the player's controls look.", id = "player_look",
            info = stringResource(R.string.ui_advanced_ember_s_controls_with_the)) {
        // #207: each layout as a small picture.
        PictureChoice("player", SettingDefinitions.playerInterface.options, app.ember.tv.player.PlayerInterfaces.resolve(c, vm.look.uiStyle)) { v ->
            vm.playerSettings.updatePlaybackConfig { it.copy(playerInterface = v) }
        }
    }
        "audio-timing" -> Section(stringResource(R.string.ui_sound_timing_lip_sync), "Fix sound that runs early or late.", id = "audio-timing",
            info = stringResource(R.string.ui_for_receivers_soundbars_and_projectors_that)) {
        AudioTimingControls(c.audioDelayMs) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(audioDelayMs = v) } }
        ToggleRow("Separate Bluetooth timing", "Follows the selected audio output", c.bluetoothDelayMs != null) {
            vm.playerSettings.updatePlaybackConfig { it.copy(bluetoothDelayMs = if(it.bluetoothDelayMs == null) it.audioDelayMs else null) }
        }
        c.bluetoothDelayMs?.let { ms -> AudioTimingControls(ms) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(bluetoothDelayMs = v) } } }
    }
        "seek-mode" -> Section("Seeking", "How skipping forward and back lands.", id="seek-mode",
            info = "Fast jumps to nearby keyframes, so skipping is quicker but less exact. Saved resume positions stay precise either way.") {
            Choice(listOf("precise" to "Precise", "fast" to "Fast / nearby keyframe"), c.seekMode) { value -> vm.playerSettings.updatePlaybackConfig {it.copy(seekMode=value)} }
            // #420
            ToggleRow("Seek thumbnails", if (c.seekThumbnails) "Pictures above the progress bar while you seek. Streamed files are read in the background."
                else "Off for streamed files; your media server's thumbnails still show.", c.seekThumbnails) {
                vm.playerSettings.updatePlaybackConfig { it.copy(seekThumbnails = !it.seekThumbnails) }
            }
        }
        "decoder-rules" -> Section("Decoder preferences", "Hints for choosing a decoder.", id="decoder-rules",
            info = paragraphs("Hints for automatic selection. Explicit in-player choices take priority.",
                "Codec rules take priority over file extensions. Standard playback orders available device decoders; compatibility playback can use software decoding.")) {
            val rules=c.decoderRules
            val choices=app.ember.tv.player.DecoderRules.choices
            Choice(choices,rules.local,"Local files") { value -> vm.playerSettings.updatePlaybackConfig {it.copy(decoderRules=it.decoderRules.copy(local=value))} }
            Choice(choices,rules.network,"Network and server streams") { value -> vm.playerSettings.updatePlaybackConfig {it.copy(decoderRules=it.decoderRules.copy(network=value))} }
            var expanded by remember {mutableStateOf(false)}
            SettingsDisclosure(if(expanded) "Hide format rules" else "Format-specific rules",expanded) {expanded=!expanded}
            if(expanded) {
                app.ember.tv.player.DecoderRules.codecChoices.forEach { (mime,label) ->
                    Choice(choices,rules.codecs[mime] ?: "inherit",label) { value -> vm.playerSettings.updatePlaybackConfig {it.copy(decoderRules=it.decoderRules.copy(codecs=it.decoderRules.codecs+(mime to value)))} }
                }
                app.ember.tv.player.DecoderRules.extensionChoices.forEach { ext ->
                    Choice(choices,rules.extensions[ext] ?: "inherit",".$ext files") { value -> vm.playerSettings.updatePlaybackConfig {it.copy(decoderRules=it.decoderRules.copy(extensions=it.decoderRules.extensions+(ext to value)))} }
                }
            }
            SettingActionRow("Reset decoder preferences", "Back to Automatic for every file and format") {
                vm.playerSettings.updatePlaybackConfig {it.copy(decoderRules=app.ember.tv.player.DecoderRules())}
                vm.showNotice("Decoder preferences reset.")
            }
        }
        "audio-effects" -> Section("Audio effects", "Equalizer, bass and night listening.", id="audio-effects",
            info = paragraphs("For decoded audio. HDMI passthrough remains unchanged.",
                "Boosts reserve headroom to reduce clipping. Disable passthrough to process encoded surround tracks in compatibility playback.")) {
            Choice(app.ember.tv.player.AudioEffects.presets,c.audioEffects.preset,"Equalizer") {value -> vm.playerSettings.updatePlaybackConfig {it.copy(audioEffects=it.audioEffects.copy(preset=value))}}
            Choice(listOf("0" to "Off","2" to "+2 dB","4" to "+4 dB","6" to "+6 dB"),c.audioEffects.bass.toString(),"Bass") {value -> vm.playerSettings.updatePlaybackConfig {it.copy(audioEffects=it.audioEffects.copy(bass=value.toInt()))}}
            Choice(app.ember.tv.player.AudioEffects.channelChoices,c.audioEffects.channels,"Channels") {value -> vm.playerSettings.updatePlaybackConfig {it.copy(audioEffects=it.audioEffects.copy(channels=value))}}
            // #310
            Choice(app.ember.tv.player.NightListening.choices,c.audioEffects.compression,"Night listening") {value -> vm.playerSettings.updatePlaybackConfig {it.copy(audioEffects=it.audioEffects.copy(compression=value))}}
        }
        "player-shortcuts" -> Section("Player shortcuts", "Up to eight quick controls.", id="player-shortcuts",
            info = "Choose up to eight quick controls for the player. Every option remains in the full menu.") {
            val selected=app.ember.tv.player.PlayerShortcuts.normalized(c.quickShortcuts)
            fun save(values:List<String>) {vm.playerSettings.updatePlaybackConfig {it.copy(quickShortcuts=values)}}
            selected.forEachIndexed { index,id ->
                val label=app.ember.tv.player.PlayerShortcuts.choices.first {it.first==id}.second
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(label,modifier=Modifier.weight(1f),maxLines=1)
                    if(index>0)ToolbarIcon("Move $label earlier",Icons.Outlined.ArrowUpward) {val list=selected.toMutableList();java.util.Collections.swap(list,index,index-1);save(list)}
                    if(index<selected.lastIndex)ToolbarIcon("Move $label later",Icons.Outlined.ArrowDownward) {val list=selected.toMutableList();java.util.Collections.swap(list,index,index+1);save(list)}
                    ToolbarIcon("Remove $label",Icons.Outlined.Close) {save(selected-id)}
                }
            }
            if(selected.size<8)ButtonRow {
                app.ember.tv.player.PlayerShortcuts.choices.filter {it.first !in selected}.forEach { (id,label) -> Chip("Add $label") {save(selected+id)} }
            }
            SettingActionRow("Reset shortcuts") {save(app.ember.tv.player.PlayerShortcuts.defaults);vm.showNotice("Player shortcuts reset.")}
        }
        "dolby_vision" -> Section(stringResource(R.string.ui_dolby_vision), "When to use Dolby Vision.", id = "dolby_vision", info = stringResource(R.string.ui_auto_plays_dolby_vision_only_when)) {
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
        "leaving" -> Section(stringResource(R.string.st_leaving_the_player), stringResource(R.string.st_what_back_and_home_do_while), id = "leaving",
            info = paragraphs(stringResource(R.string.st_ask_first_shows_stop_watching_with), stringResource(R.string.st_android_does_not_let_apps_stop),
                stringResource(R.string.st_back_to_the_last_video_for) + stringResource(R.string.st_press_back_twice_suits_remotes_without))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(SettingDefinitions.leaving.options, if (c.confirmExit) "ask" else "leave", stringResource(R.string.tx_back_button)) { v ->
                vm.playerSettings.updatePlaybackConfig { it.copy(confirmExit = v == "ask") }
            }
            ToggleRow(stringResource(R.string.st_home_keep_playing_in_a_small), null, c.pip) { vm.playerSettings.updatePlaybackConfig { it.copy(pip = !it.pip) } }
            Choice(app.ember.tv.player.QuickReturn.keys, app.ember.tv.player.QuickReturn.key(c), stringResource(R.string.tx_back_to_the_last_video_from)) { v ->
                vm.playerSettings.updatePlaybackConfig { app.ember.tv.player.QuickReturn.choose(it, v) }
            }
        }
    }
        "skip-intro" -> Section(stringResource(R.string.ui_skip_intro), "Intro, recap and credits buttons.", id = "skip-intro",
            info = paragraphs(stringResource(R.string.ui_uses_the_intro_recap_and_credits), stringResource(R.string.st_when_server_markers_are_missing_send))) {
        Choice(
            SettingDefinitions.skipIntro.options,
            c.skipIntro,
        ) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(skipIntro = v) } }
        ToggleRow(stringResource(R.string.st_introdb_fallback), "When your server has no markers", c.introDb) {
            vm.playerSettings.updatePlaybackConfig { it.copy(introDb = !it.introDb) }
        }
    }
        "audio-output" -> Section(stringResource(R.string.ui_audio_output), stringResource(R.string.ui_for_a_receiver_or_soundbar_over), id = "audio-output",
            info = paragraphs(stringResource(R.string.ui_dolby_truehd_dts_hd_and_atmos), stringResource(R.string.st_use_choose_myself_only_if_your) +
                stringResource(R.string.st_a_format_the_receiver_cannot_take))) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                stringResource(R.string.st_passthrough_bitstream), if (c.passthrough) null else stringResource(R.string.st_off_decode_everything_to_pcm),
                c.passthrough,
            ) { vm.playerSettings.updatePlaybackConfig { it.copy(passthrough = !it.passthrough) } }
            // #421
            ToggleRow("Keep surround when decoding", "DTS and TrueHD that aren't passed through are decoded by Ember in 5.1 or 7.1; some TVs' own decoders give stereo.",
                c.keepSurround, unavailable = if (c.ffmpeg == "off") "Needs FFmpeg sound decoding (Decoding)." else null) {
                vm.playerSettings.updatePlaybackConfig { it.copy(keepSurround = !it.keepSurround) }
            }
            // #422: formats whose passthrough kept failing on this TV.
            val context = androidx.compose.ui.platform.LocalContext.current
            var healthRev by remember { mutableIntStateOf(0) }
            val blocked = remember(healthRev) { app.ember.tv.player.PassthroughHealth.blocked(context) }
            if (blocked.isNotEmpty()) {
                val names = app.ember.tv.player.Playback.FORMAT_LABELS.filter { it.first in blocked }.joinToString(", ") { it.second }
                SettingActionRow("Try passthrough again", "Kept failing on this TV, now decoded: $names.") {
                    app.ember.tv.player.PassthroughHealth.reset(context); healthRev++
                }
            }
            if (c.passthrough) {
                Choice(
                    listOf("auto" to "As HDMI reports", "soundbar" to "Soundbar on the TV (ARC)", "manual" to "Choose myself"),
                    c.passthroughMode, "Formats",
                ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(passthroughMode = m) } }
                // #423
                if (c.passthroughMode == "soundbar") Note("Dolby Digital, DD+ (with Atmos) and DTS go to the soundbar; DTS-HD is sent as DTS. Tracks it can take are chosen first, so a film keeps 5.1 instead of stereo.")
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
            }
        }
    }
        "decoding" -> Section(stringResource(R.string.ui_decoding), "Which engine and decoders play video.", id = "decoding",
            info = paragraphs(stringResource(R.string.ui_ffmpeg_decodes_audio_the_tv_cannot), stringResource(R.string.st_automatic_keeps_ember_s_controls_and),
                "Lighter software decoding skips some deblocking to reduce processor work. Explicit decoder rules and in-player choices take priority.",
                "Advanced (gpu-next) gives better scaling and HDR tone mapping. If it fails on this device, Ember retries once on Stable (gpu).",
                stringResource(R.string.st_for_interlaced_live_tv_or_older), stringResource(R.string.st_decoder_preferences_below_apply_to_standard),
                stringResource(R.string.st_software_includes_ffmpeg_and_mpeg_h),
                stringResource(R.string.ui_video_always_uses_the_tv_s) + stringResource(R.string.st_tunneled_playback_gives_tighter_a_v))) {
            Choice(listOf("auto" to "Automatic","standard" to "Standard","compatibility" to "Compatibility"),c.engine, stringResource(R.string.tx_playback_engine)) {value -> vm.playerSettings.updatePlaybackConfig {it.copy(engine=value)}}
            // #418: options for the engine that isn't in use stay in place, dimmed, saying why.
            val noCompatibility = if (c.engine == "standard") "Not used: the engine is set to Standard." else null
            val noStandard = if (c.engine == "compatibility") "Not used: the engine is set to Compatibility." else null
            // #311
            Choice(app.ember.tv.player.NativeDecoderProfile.choices,app.ember.tv.player.NativeDecoderProfile.normalized(c.compatibilityDecoder),"Compatibility decoding",unavailable=noCompatibility) {value -> vm.playerSettings.updatePlaybackConfig {it.copy(compatibilityDecoder=value)}}
            Choice(app.ember.tv.player.RendererPolicy.choices,app.ember.tv.player.RendererPolicy.initial(c.compatibilityRenderer),"Compatibility renderer",unavailable=noCompatibility) {value -> vm.playerSettings.updatePlaybackConfig {it.copy(compatibilityRenderer=value)}}
            Choice(listOf("off" to "Off","auto" to "Automatic","always" to "Always"),c.deinterlacing, stringResource(R.string.tx_compatibility_deinterlacing),unavailable=noCompatibility) { mode ->
                vm.playerSettings.updatePlaybackConfig {it.copy(deinterlacing=mode)}
            }
            ToggleRow("Automatic Hi10P fallback","10-bit H.264 on NVIDIA Shield",c.hi10pFallback) {vm.playerSettings.updatePlaybackConfig {it.copy(hi10pFallback=!it.hi10pFallback)}}
            Choice(
                listOf("on" to "Software when needed", "prefer" to "Prefer software", "off" to "TV decoders only"),
                c.ffmpeg, stringResource(R.string.ui_sound_decoding), unavailable = noStandard,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(ffmpeg = m) } }
            Choice(
                listOf(
                    "fallback" to stringResource(R.string.ui_ffmpeg_video_fallback),
                    "always" to stringResource(R.string.ui_ffmpeg_video_always),
                    "off" to stringResource(R.string.ui_ffmpeg_video_off),
                ),
                c.ffmpegVideo, stringResource(R.string.ui_video_decoding), unavailable = noStandard,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(ffmpegVideo = m) } }
            ToggleRow(stringResource(R.string.ui_tunneled_playback), null, c.tunneling,
                unavailable = noStandard ?: if (c.ffmpegVideo == "always") "Not used while software decodes all video." else null) {
                vm.playerSettings.updatePlaybackConfig { it.copy(tunneling = !it.tunneling) }
            }
            ToggleRow("Remember what works","Learns which decoder and player work for a title",c.rememberDecoderChoices) {vm.playerSettings.updatePlaybackConfig {it.copy(rememberDecoderChoices=!it.rememberDecoderChoices)}}
            // #386: a full-width row the d-pad reaches like the rows around it, and it says when it is done.
            SettingActionRow("Reset compatibility and decoder history","Forget which decoder and player worked for each title") {
                app.ember.tv.player.DecoderHealth.clear(context)
                vm.showNotice("Compatibility and decoder history cleared.")
            }
    }
        "display" -> Section(stringResource(R.string.ui_display), stringResource(R.string.ui_match_the_display_to_the_video), id = "display",
            info = paragraphs(stringResource(R.string.ui_frame_rate_matching_plays_23_976), stringResource(R.string.st_leave_resolution_matching_off_to_let))) {
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
            // #431
            SettingActionRow("Set up for this TV", "Sets frame-rate matching and the buffer from this box's memory and the screen's refresh rates.") { vm.tuneForThisTv() }
        }
    }
        "buffer" -> Section(stringResource(R.string.ui_buffer), stringResource(R.string.ui_how_far_ahead_the_player_loads), id = "buffer",
            info = stringResource(R.string.ui_large_keeps_up_to_two_minutes)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                listOf("normal" to "Normal", "large" to "Large (4K remux)", "huge" to "Huge"),
                c.buffer,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(buffer = m) } }
            // #309: network time limits for both players, and optional custom buffering.
            val stream = c.stream.normalized()
            fun saveStream(value: app.ember.tv.player.StreamConfig) = vm.playerSettings.updatePlaybackConfig { it.copy(stream = value.normalized()) }
            app.ember.tv.player.timeoutFields.forEach { f ->
                Choice(f.choices(stream).map { it.toString() to "$it ${f.unit}" }, f.read(stream).toString(), f.label) { v -> saveStream(f.write(stream, v.toInt())) }
            }
            ToggleRow("Custom buffering", "Set memory and timing yourself", stream.custom) { saveStream(stream.copy(custom = !stream.custom)) }
            if (stream.custom) {
                app.ember.tv.player.bufferFields.forEach { f ->
                    Choice(f.choices(stream).map { it.toString() to "$it ${f.unit}" }, f.read(stream).toString(), f.label) { v -> saveStream(f.write(stream, v.toInt())) }
                }
                ToggleRow("Wait for refill", "Pause until the buffer refills", stream.pauseForBuffer) { saveStream(stream.copy(pauseForBuffer = !stream.pauseForBuffer)) }
                val plan = remember(c) { app.ember.tv.player.StreamConfig.plan(context, c) }
                Note("Up to ${plan.forwardMiB} MiB ahead and ${plan.rewindMiB} MiB behind on this TV. Applies to the next video.")
            }
        }
    }
        "audio_track" -> Section(stringResource(R.string.ui_audio_track), stringResource(R.string.ui_which_track_a_title_starts_with), id = "audio_track",
            info = stringResource(R.string.ui_best_quality_prefers_truehd_and_atmos)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(
                listOf("quality" to "Best quality", "language" to "My language, then quality", "default" to "File's default"),
                c.audioPick,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(audioPick = m) } }
        }
    }
        "trailers" -> Section(stringResource(R.string.ui_trailers), stringResource(R.string.ui_experimental), id = "trailers",
            info = paragraphs(stringResource(R.string.ui_plays_youtube_trailers_in_the_built), stringResource(R.string.st_youtube_changes_often_so_this_can),
                stringResource(R.string.st_trailers_are_stereo_no_atmos_and))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(stringResource(R.string.ui_play_trailers_in_ember_experimental), null, c.trailersInApp) { vm.playerSettings.updatePlaybackConfig { it.copy(trailersInApp = !it.trailersInApp) } }
        }
    }
        "playback_info" -> Section(stringResource(R.string.ui_playback_info), "What shows when a video starts.", id = "playback_info",
            info = stringResource(R.string.ui_during_playback_press_info_menu_or)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // #218: the two choices as pictures.
            PictureChoice("info", listOf("start" to stringResource(R.string.ui_show_at_start), "ask" to "Only when I ask"), if (c.infoOnStart) "start" else "ask") { v ->
                vm.playerSettings.updatePlaybackConfig { it.copy(infoOnStart = v == "start") }
            }
        }
    }
        "next-episode" -> Section(stringResource(R.string.ui_next_episode), stringResource(R.string.ui_works_in_the_built_in_player), id = "next-episode") {
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
        Section("Sources", "Finding and starting a source.") {
            // #428
            ToggleRow("Skip dead links", "When a source fails before it plays, the next ready source plays instead.", vm.playing.skipDeadLinks) { vm.playing.updateSkipDeadLinks(!vm.playing.skipDeadLinks) }
            // #431
            ToggleRow("Check file length", "Stops and asks when a file is much shorter or longer than the title, which catches samples, trailers, fakes and wrong episodes.", vm.playing.lengthCheck) { vm.playing.updateLengthCheck(!vm.playing.lengthCheck) }
            // #429
            ToggleRow("Find sources early", "Looks up sources for the Continue watching title you're on, so they're ready when you open it.", vm.playing.earlySources) { vm.playing.updateEarlySources(!vm.playing.earlySources) }
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
                        Chip(c + if (c in r.avoidCodecs) stringResource(R.string.st_skipped) else "", selected = c in r.avoidCodecs) {
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
