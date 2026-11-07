package app.ember.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.outlined.Info
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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

// Settings pages, split out of SettingsScreen.kt. Watching: player, addons, catalogs, Live TV, subtitles, stream format, where to watch, Play button.

// ---------------------------------------------------------------- Playback



// ---------------------------------------------------------------- Player engine

@Composable
internal fun Choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    ButtonRow(Modifier.padding(vertical = 4.dp)) {
        options.forEach { (id, label) -> Chip(label, selected = selected == id) { onPick(id) } }
    }
}

@Composable
internal fun Note(text: String) = Text(text, color = Mute, fontSize = 12.sp, modifier = Modifier.width(760.dp))

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
        Choice(SettingDefinitions.playerInterface.options, app.ember.tv.player.PlayerInterfaces.resolve(c, vm.look.uiStyle)) { v ->
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
            Text("Back button", fontSize = 15.sp)
            Choice(SettingDefinitions.leaving.options, if (c.confirmExit) "ask" else "leave") { v ->
                vm.playerSettings.updatePlaybackConfig { it.copy(confirmExit = v == "ask") }
            }
            Note("Ask first shows Stop watching? with Keep watching selected. Pressing Back again leaves, so a double press still exits.")
            ToggleRow("Home: keep playing in a small window", null, c.pip) { vm.playerSettings.updatePlaybackConfig { it.copy(pip = !it.pip) } }
            Text("Back to the last video from Home", fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Choice(app.ember.tv.player.QuickReturn.keys, app.ember.tv.player.QuickReturn.key(c)) { v ->
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
                    listOf("auto" to "Formats: as HDMI reports", "manual" to "Formats: choose myself"),
                    c.passthroughMode,
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
            Text("Playback engine",fontSize=15.sp)
            Choice(listOf("auto" to "Automatic","standard" to "Standard","compatibility" to "Compatibility"),c.engine) {value -> vm.playerSettings.updatePlaybackConfig {it.copy(engine=value)}}
            Note("Automatic keeps Ember's controls and tries compatibility playback when a format needs it.")
            Text("Compatibility deinterlacing",fontSize=15.sp)
            Choice(listOf("off" to "Off","auto" to "Automatic","always" to "Always"),c.deinterlacing) { mode ->
                vm.playerSettings.updatePlaybackConfig {it.copy(deinterlacing=mode)}
            }
            Note("For interlaced Live TV or older video in Compatibility playback. Automatic follows the video's scan type; Always is for streams detected incorrectly. Uses more processing. Off is the default.")
            Note("Decoder preferences below apply to Standard playback. Compatibility has its own decoder controls while watching.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ui_sound_decoding), fontSize = 15.sp)
            Choice(
                listOf("on" to "Software when needed", "prefer" to "Prefer software", "off" to "TV decoders only"),
                c.ffmpeg,
            ) { m -> vm.playerSettings.updatePlaybackConfig { it.copy(ffmpeg = m) } }
            Note("Software includes FFmpeg and MPEG-H. MPEG-H outputs stereo PCM. AC-4 uses the device decoder when available.")
            Text(stringResource(R.string.ui_video_decoding), fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            Choice(
                listOf(
                    "fallback" to stringResource(R.string.ui_ffmpeg_video_fallback),
                    "always" to stringResource(R.string.ui_ffmpeg_video_always),
                    "off" to stringResource(R.string.ui_ffmpeg_video_off),
                ),
                c.ffmpegVideo,
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
            ToggleRow(stringResource(R.string.ui_show_at_start), null, c.infoOnStart) {
                vm.playerSettings.updatePlaybackConfig { it.copy(infoOnStart = !it.infoOnStart) }
            }
            Note(stringResource(R.string.ui_during_playback_press_info_menu_or))
        }
    }
        "next-episode" -> Section(stringResource(R.string.ui_next_episode), stringResource(R.string.ui_works_in_the_built_in_player)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                item(key = "autoplay") {
                    SwitchChip(stringResource(R.string.ui_play_next_episode_automatically), vm.playing.autoplay) { vm.playing.updateAutoplay(!vm.playing.autoplay) }
                }
                if (vm.playing.autoplay) {
                    items(listOf(5, 10, 15, 30), key = { "cd$it" }) { secs ->
                        Chip(stringResource(R.string.ui_countdown_secs, secs), selected = vm.playing.countdown == secs) { vm.playing.updateCountdown(secs) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Addons

internal fun LazyListScope.addonsPage(vm: HomeViewModel) {
    item(key = "addonshead") {
        var organizing by remember { mutableStateOf(false) }
        if (organizing) ContentOrganizer(vm) { organizing = false }
        Section(stringResource(R.string.ui_stremio_addons), stringResource(R.string.ui_each_catalog_an_addon_offers_becomes)) {
            Chip("Organize add-ons, catalogs and collections") { organizing = true }
            if (vm.addons.isEmpty()) {
                Text(stringResource(R.string.ui_no_addons_yet_paste_a_link), color = Mute, fontSize = 14.sp)
            }
        }
    }
    items(vm.addons, key = { it }) { url -> AddonRow(vm, url) }
    item(key = "addaddon") { AddAddonField(vm) }
    item(key = "addontimeout") {
        Section(stringResource(R.string.ui_time_limit), stringResource(R.string.ui_how_long_home_waits_for_an)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(listOf(5, 10, 15, 30).map { it.toString() to "$it s" }, vm.addonTimeout.toString()) { vm.updateAddonTimeout(it.toInt()) }
                if (vm.slowAddons.isNotEmpty()) {
                    Text(stringResource(R.string.ui_too_slow, vm.slowAddons.joinToString(", ")), color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
            }
        }
    }
}

/** One added addon: its name (yours or the manifest's), what it offers, rename and remove. */
@Composable
internal fun AddonRow(vm: HomeViewModel, url: String) {
    val info = vm.addonInfo[url]
    var renaming by remember(url) { mutableStateOf(false) }
    var newName by remember(url, renaming) { mutableStateOf(info?.name.orEmpty()) }
    Column(
        modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // What the addon's manifest says about itself (#60): logo, version, description, id,
        // how many catalogs it has and which types it covers.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AddonLogo(info?.logo, vm.addonLabel(url))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.addonLabel(url), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    info?.version?.let { v ->
                        Text("v$v", fontSize = TvTokens.Type.Caption.sp, color = Fg, maxLines = 1,
                            modifier = Modifier.background(WashHi, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                info?.description?.let { Text(it, fontSize = 13.sp, color = Fg, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                info?.originalName?.let { Text("Renamed from $it", fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1) }
                info?.manifestId?.let { Text("ID: $it", fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (info != null) {
                        if (info.catalogs.isNotEmpty()) {
                            androidx.tv.material3.Icon(Icons.Outlined.Folder, contentDescription = "Catalogs", tint = Mute, modifier = Modifier.size(16.dp))
                            Text(info.catalogs.size.toString(), fontSize = TvTokens.Type.Caption.sp, color = Mute)
                        }
                        if (info.types.isNotEmpty()) {
                            androidx.tv.material3.Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Types", tint = Mute,
                                modifier = Modifier.padding(start = if (info.catalogs.isNotEmpty()) 6.dp else 0.dp).size(16.dp))
                            Text(info.types.joinToString(", "), fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
                Text(
                    info?.let { a ->
                        (a.provides.mapNotNull {
                            when (it) {
                                "catalog" -> "Rows"
                                "meta" -> "Details"
                                "stream" -> "Sources"
                                "subtitles" -> "Subtitles"
                                else -> null
                            }
                        } + listOfNotNull("Has its own settings".takeIf { a.configurable })).joinToString(" · ").ifBlank { "Nothing this launcher uses" }
                    } ?: if (vm.addonAvailable(url)) "Manifest not loaded" else "Paused",
                    fontSize = TvTokens.Type.Caption.sp, color = Mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ButtonRow(spacing = 8.dp) {
            SwitchChip("Enabled", vm.addonAvailable(url)) { vm.pauseAddon(url, vm.addonAvailable(url)) }
            if (!renaming) Chip(stringResource(R.string.ui_rename)) { renaming = true }
            Chip("Refresh") { vm.refreshAddon(url) }
            Chip(stringResource(R.string.ui_remove)) { vm.removeAddon(url) }
        }
        if (renaming) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(newName, { newName = it }, info?.originalName ?: info?.name ?: "Name")
                Chip(stringResource(R.string.ui_save)) {
                    vm.renameAddon(url, newName)
                    renaming = false
                }
                if (info?.originalName != null) {
                    Chip(stringResource(R.string.ui_use_original)) {
                        vm.renameAddon(url, "")
                        renaming = false
                    }
                }
                Chip(stringResource(R.string.ui_cancel)) { renaming = false }
            }
        }
    }
}

@Composable
internal fun AddAddonField(vm: HomeViewModel) {
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Section(stringResource(R.string.ui_add_an_addon)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(link, { link = it; message = null }, "https://example.com/manifest.json", width = 760)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.ui_add_addon)) {
                    message = vm.addAddon(link)
                    if (message == null) link = ""
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Catalogs

internal fun LazyListScope.catalogsPage(vm: HomeViewModel) {
    item(key = "catshead") {
        Section(stringResource(R.string.ui_catalogs), stringResource(R.string.ui_choose_which_addon_catalogs_appear_as)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ui_the_order_is_shared_home_rows),
                    color = Mute,
                    fontSize = 13.sp,
                    modifier = Modifier.width(760.dp),
                )
                if (vm.allCatalogs.isEmpty()) {
                    Text(if (vm.addons.isEmpty()) "No addons yet. Add one in Settings > Addons." else "Loading catalogs...", color = Mute, fontSize = 13.sp)
                } else {
                    Chip(stringResource(R.string.ui_reset_order_and_choices)) { vm.resetCatalogs() }
                }
            }
        }
    }
    val list = vm.allCatalogs
    itemsIndexed(list, key = { _, c -> "cat:${c.key}" }) { i, c ->
        Column(
            modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${i + 1}", color = Mute, fontSize = 13.sp, modifier = Modifier.width(26.dp))
            Column {
                Text(c.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.addon.name, color = Mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          ButtonRow(spacing = 8.dp) {
            CatalogRenameButton(vm, c.key, c.originalLabel)
            if (c.homeable) {
                val on = vm.isOnHome(c)
                SwitchChip(stringResource(R.string.ui_home_b9499), on) { vm.toggleCatalogHome(c) }
                val live = vm.isOnLive(c)
                SwitchChip(stringResource(R.string.ui_live_tv_2386e), live) { vm.toggleCatalogLive(c) }
            } else {
                Text(stringResource(R.string.ui_not_for_home), color = Mute, fontSize = 12.sp)
            }
            if (c.browsable) {
                val on = vm.isInDiscover(c)
                SwitchChip(stringResource(R.string.ui_discover), on) { vm.toggleCatalogDiscover(c) }
            } else {
                Text(stringResource(R.string.ui_search_only), color = Mute, fontSize = 12.sp)
            }
            if (i > 0) Chip(stringResource(R.string.ui_up)) { vm.moveCatalog(c, -1) }
            if (i < list.lastIndex) Chip(stringResource(R.string.ui_down)) { vm.moveCatalog(c, 1) }
            if (i > 1) Chip(stringResource(R.string.ui_top)) { vm.moveCatalog(c, 0, toTop = true) }
          }
        }
    }
}

// ---------------------------------------------------------------- Live TV (IPTV)

@Composable
internal fun LiveTvSettingsPage(vm: HomeViewModel) {
    if (!vm.featureOn(app.ember.tv.data.Features.LIVE)) {
        Section(stringResource(R.string.ui_live_tv), stringResource(R.string.ui_switched_off_in_settings_features_so)) {
            Chip(stringResource(R.string.ui_turn_live_tv_on)) { vm.toggleFeature(app.ember.tv.data.Features.LIVE) }
        }
        return
    }
    val iptv = vm.iptv
    var kind by remember { mutableStateOf("m3u") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("ts") }
    var advanced by remember { mutableStateOf(false) }
    var guideTools by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_live_tv), stringResource(R.string.ui_iptv_channels_with_a_programme_guide)) {
            SourceStatusButton("IPTV source status",iptv.health.entries.values) {id->
                if(id.endsWith(":guide"))iptv.refreshGuide(id.removeSuffix(":guide")) else iptv.refresh(force=true,only=id)
            }
            iptv.guideProgress?.let { Text(it,color=Mute,fontSize=12.sp) }
            Chip("Programme guide tools") { guideTools = true }
            Note(
                stringResource(R.string.ui_add_an_m3u_playlist_link_or) +
                    "use channel up and down (or Up and Down) to change channel, and Info for the playback panel. " +
                    "Addon catalogs for Live TV are chosen in Settings > Catalogs.",
            )
        }

        Section(stringResource(R.string.ui_your_sources), if (iptv.sources.isEmpty()) "None yet." else "${iptv.channels.size} channels · guide for ${iptv.guide.size}") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                iptv.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
                iptv.sources.forEach { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 16.sp)
                            Text(
                                (if (s.kind == "xtream") "Xtream Codes" else "M3U playlist") + " · " +
                                    "${iptv.channels.count { it.sourceId == s.id }} channels" +
                                    if (s.epgUrl.isNotBlank()) " · own guide link" else "",
                                color = Mute,
                                fontSize = 12.sp,
                            )
                        }
                        SwitchChip("", s.enabled) { iptv.setEnabled(s.id, !s.enabled) }
                        Chip(stringResource(R.string.ui_remove)) { iptv.remove(s.id) }
                    }
                }
                if (iptv.sources.isNotEmpty()) {
                    ButtonRow(spacing = 10.dp) {
                        Chip(if (iptv.loading) "Loading channels..." else "Reload channels") { iptv.refresh(force = false) }
                        Chip(if (iptv.guideLoading) "Loading the guide..." else "Reload the guide") { iptv.refreshGuide() }
                    }
                }
            }
        }

        Section(stringResource(R.string.ui_add_a_source)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice(listOf("m3u" to "M3U playlist link", "xtream" to "Xtream Codes login"), kind) { kind = it }
                TextInput(name, { name = it }, "Name (optional), e.g. My provider")
                if (kind == "m3u") {
                    TextInput(url, { url = it }, "Playlist link, e.g. http://provider.tv/get.php?...", width = 760)
                } else {
                    TextInput(url, { url = it }, "Server, e.g. http://provider.tv:8080", width = 760)
                    TextInput(user, { user = it }, "Username")
                    TextInput(pass, { pass = it }, "Password", password = true)
                    Choice(listOf("ts" to "Streams: MPEG-TS (most compatible)", "m3u8" to "Streams: HLS"), format) { format = it }
                }
                SettingsDisclosure(stringResource(R.string.settings_advanced_options),advanced,Modifier.settingsAnchor("iptv-advanced")) {advanced=!advanced}
                if (advanced) {
                    TextInput(epg, { epg = it }, "Guide (XMLTV) link, if not the provider's own", width = 760)
                    TextInput(agent, { agent = it }, "User agent, only if your provider asks for one", width = 760)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (vm.iptvSetup.iptvAdding) "Adding..." else "Add source") {
                        vm.iptvSetup.addIptvSource(kind, name, url, user, pass, epg, agent, format) {
                            name = ""; url = ""; user = ""; pass = ""; epg = ""; agent = ""
                        }
                    }
                    vm.iptvSetup.iptvAddMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.width(600.dp)) }
                }
                Note(
                    stringResource(R.string.ui_logins_and_links_are_stored_encrypted) +
                        "The guide comes from the playlist (url-tvg) or the Xtream server unless you give a link, and refreshes every 12 hours.",
                )
            }
        }
    }
    if (guideTools) EpgTools(iptv) { guideTools = false }
}

// ---------------------------------------------------------------- Subtitles

@Composable
internal fun SubtitlesPage(vm: HomeViewModel, openKeys: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev
    val c = vm.playerSettings.subtitleConfig
    val osReady = ApiKeys.get("OPENSUBTITLES_API_KEY").isNotBlank()
    val subdlReady = ApiKeys.get("SUBDL_API_KEY").isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_subtitles_6a730), stringResource(R.string.ui_for_the_built_in_player_found)) {
            Note(
                stringResource(R.string.ui_during_playback_press_info_menu_or_fe603),
            )
        }

        Section(stringResource(R.string.ui_languages), if (c.languages.isEmpty()) "Using the TV's language${if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language != "en") " and English" else ""}." else "First is the main one. Up to three.") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.languages.isNotEmpty()) {
                    ButtonRow(spacing = 10.dp) {
                        c.languages.forEachIndexed { i, code ->
                            Chip("${i + 1}. ${app.ember.tv.data.Subtitles.languageName(code)} ✕") {
                                vm.playerSettings.updateSubtitleConfig { it.copy(languages = it.languages - code) }
                            }
                        }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                    items(app.ember.tv.data.Subtitles.LANGUAGE_CHOICES.filter { it.first !in c.languages }, key = { it.first }) { (code, label) ->
                        Chip("+ $label") {
                            if (c.languages.size < 3) vm.playerSettings.updateSubtitleConfig { it.copy(languages = it.languages + code) }
                        }
                    }
                }
            }
        }

        Section(stringResource(R.string.ui_turn_on_at_start), id = "subtitle-start") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    SettingDefinitions.subtitles.options,
                    c.autoSelect,
                ) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(autoSelect = m) } }
                Note(stringResource(R.string.ui_the_video_s_own_subtitle_in))
            }
        }

        Section(stringResource(R.string.ui_providers)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnOff("Your Stremio addons", c.addonSubs) { vm.playerSettings.updateSubtitleConfig { it.copy(addonSubs = !it.addonSubs) } }
                OnOff("OpenSubtitles (free, no key)", c.stremioOpenSubs) { vm.playerSettings.updateSubtitleConfig { it.copy(stremioOpenSubs = !it.stremioOpenSubs) } }
                OnOff("OpenSubtitles.com", c.openSubsCom, note = if (osReady) "Key set" else "Needs an API key") { vm.playerSettings.updateSubtitleConfig { it.copy(openSubsCom = !it.openSubsCom) } }
                OnOff("SubDL", c.subdl, note = if (subdlReady) "Key set" else "Needs an API key") { vm.playerSettings.updateSubtitleConfig { it.copy(subdl = !it.subdl) } }
                Chip(stringResource(R.string.ui_api_keys)) { openKeys() }
                Note(
                    stringResource(R.string.ui_opensubtitles_free_is_the_same_service) +
                        "OpenSubtitles.com with a key adds release names, frame rates, trusted uploaders and hearing-impaired flags; add your login for more downloads a day. " +
                        "SubDL adds a second large library.",
                )
            }
        }

        Section(stringResource(R.string.ui_matching)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnOff("Match to the exact file", c.hashMatch) { vm.playerSettings.updateSubtitleConfig { it.copy(hashMatch = !it.hashMatch) } }
                OnOff("Skip machine-translated", c.skipMachine) { vm.playerSettings.updateSubtitleConfig { it.copy(skipMachine = !it.skipMachine) } }
                Text(stringResource(R.string.ui_hearing_impaired_sdh), fontSize = 15.sp)
                Choice(
                    listOf("avoid" to "Avoid", "include" to "No preference", "prefer" to "Prefer", "exclude" to "Never"),
                    c.hearingImpaired,
                ) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(hearingImpaired = m) } }
                Note(
                    stringResource(R.string.ui_match_to_the_exact_file_reads) +
                        "The rest are ranked by how closely their release name matches the source (group, source such as WEB-DL or BluRay, resolution, edition), frame rate, uploader trust and downloads.",
                )
            }
        }

        Section(stringResource(R.string.ui_look)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnOff("Preserve authored subtitle styles",c.authoredStyles) {vm.playerSettings.updateSubtitleConfig {it.copy(authoredStyles=!it.authoredStyles)}}
                OnOff("Use system caption style",c.systemStyle) {vm.playerSettings.updateSubtitleConfig {it.copy(systemStyle=!it.systemStyle)}}
                Choice(listOf("small" to "Small", "normal" to "Normal", "large" to "Large", "xlarge" to "Extra large"), c.size) { m ->
                    vm.playerSettings.updateSubtitleConfig { it.copy(size = m) }
                }
                Choice(listOf("outline" to "Outline", "shadow" to "Shadow", "box" to "Dark box"), c.background) { m ->
                    vm.playerSettings.updateSubtitleConfig { it.copy(background = m) }
                }
                Choice(listOf("white" to "White", "yellow" to "Yellow"), c.color) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(color = m) } }
                Text(stringResource(R.string.ui_position), fontSize = 15.sp)
                Choice(listOf("low" to "Low", "normal" to "Normal", "high" to "Higher (above masking or a scope screen's bar)"), c.position) { m ->
                    vm.playerSettings.updateSubtitleConfig { it.copy(position = m) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Stremio: stream result format

@Composable
internal fun StremioFormatPage(vm: HomeViewModel) {
    var editing by remember { mutableStateOf<Boolean?>(null) } // null: none, false: name, true: description
    var showReference by remember { mutableStateOf(false) }
    val (previewName, previewDesc) = vm.sourceFormat.previewSource()
    val on = vm.sourceFormat.streamFormatOn

    // Three parts, in this order: how sources are tagged (badges), how their text is written
    // (format and templates, shown only when the custom format is on), and Collections.
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        PartHeader("Badges")
        BadgesSection(vm)

        PartHeader("Source text")
        Section(stringResource(R.string.ui_stream_results), stringResource(R.string.ui_how_addon_sources_are_shown_on)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow(stringResource(R.string.ui_custom_format), null, on) { vm.sourceFormat.updateStreamFormatOn(!on) }
                Note(
                    if (on) stringResource(R.string.ui_templates_use_the_same_syntax_as) +
                        "A connected keyboard can make editing templates easier. Templates are included in backups."
                    else "Off: sources show the title, Ember's tags and the badges above. On: your own name and description templates, AIOStreams-style.",
                )
            }
        }
        if (on) {
            Section(stringResource(R.string.ui_preview), if (vm.sourceFormat.previewIsExample) "An example source. Open a title first to preview its real sources." else "The first source of the title you opened last.") {
                Row(
                    modifier = Modifier.width(760.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text(previewName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, modifier = Modifier.width(260.dp))
                    Text(previewDesc, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
                }
            }
            TemplateSection(
                title = "Name",
                subtitle = "Left column of each source.",
                value = vm.sourceFormat.streamNameTemplate,
                isDefault = vm.sourceFormat.streamNameIsDefault,
                editing = editing == false,
                onEdit = { editing = false },
                onSave = { vm.sourceFormat.updateStreamTemplate(false, it); editing = null },
                onCancel = { editing = null },
                onReset = { vm.sourceFormat.updateStreamTemplate(false, "") },
            )
            TemplateSection(
                title = "Description",
                subtitle = "Right column of each source.",
                value = vm.sourceFormat.streamDescTemplate,
                isDefault = vm.sourceFormat.streamDescIsDefault,
                editing = editing == true,
                onEdit = { editing = true },
                onSave = { vm.sourceFormat.updateStreamTemplate(true, it); editing = null },
                onCancel = { editing = null },
                onReset = { vm.sourceFormat.updateStreamTemplate(true, "") },
            )
            Chip(if (showReference) "Hide the template reference" else "Template reference") { showReference = !showReference }
            if (showReference) {
                Section(stringResource(R.string.ui_template_reference)) {
                    Text(
                        """
                        Values: {stream.resolution}  {stream.quality}  {stream.encode}  {stream.visualTags}  {stream.audioTags}  {stream.audioChannels}  {stream.languages}  {stream.size}  {stream.bitrate}  {stream.title}  {stream.year}  {stream.seasonEpisode}  {stream.edition}  {stream.network}  {stream.releaseGroup}  {stream.seeders}  {stream.indexer}  {stream.filename}  {stream.message}  {stream.upscaled}  {stream.remastered}  {stream.repack}  {stream.unrated}  {stream.uncensored}  {service.cached}  {service.name}  {service.shortName}  {addon.name}
                        Checks: {field::exists["shown when it has a value"||"otherwise"]}  ::istrue  ::isfalse  ::>0  ::<5  ::=text  ::~contains  ::${'$'}startswith  ::^endswith, joined with ::and:: ::or::
                        Changes: ::replace('a','b')  ::join(' | ')  ::upper  ::lower  ::title  ::smallcaps  ::truncate(20)  ::first  ::length  ::bytes  ::sbytes  ::bitrate  ::sbitrate  ::default('n/a')
                        Lines that come out empty are left out. {tools.newLine} starts a new line inside a check.
                        service.cached is true for cached debrid links, false for ones that still have to download, and empty for plain links and torrents. It comes from the addon's own data when it sends it (AIOStreams with stream data on), otherwise from its labels such as ⚡, [RD+], ⏳ or [RD download].
                        """.trimIndent(),
                        color = Mute,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.width(760.dp),
                    )
                }
            }
        }

        PartHeader("Collections")
        CollectionsSettings(vm)
    }
}

/** A heading between the parts of a long Settings page. */
@Composable
private fun PartHeader(text: String) {
    Column(Modifier.width(760.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Mute.copy(alpha = 0.35f)))
    }
}

@Composable
internal fun TemplateSection(
    title: String,
    subtitle: String,
    value: String,
    isDefault: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember(editing, value) { mutableStateOf(value) }
    Section(title, subtitle + if (isDefault) " Default." else " Custom.") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Buttons sit above the text, so they stay easy to reach while the text box has focus.
            ButtonRow(spacing = 10.dp) {
                if (editing) {
                    Chip(stringResource(R.string.ui_save)) { onSave(draft) }
                    Chip(stringResource(R.string.ui_cancel)) { onCancel() }
                } else {
                    Chip(stringResource(R.string.ui_edit)) { onEdit() }
                    if (!isDefault) Chip(stringResource(R.string.ui_reset_to_default)) { onReset() }
                }
            }
            if (editing) {
                TvTextField(
                    draft,
                    { draft = it },
                    Modifier.width(760.dp),
                    singleLine = false,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    minHeight = 260.dp,
                )
            } else {
                Box(Modifier.width(760.dp).background(Panel, RoundedCornerShape(10.dp)).padding(14.dp)) {
                    Text(value, fontSize = 12.sp, lineHeight = 17.sp, color = Mute, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Where to watch

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


/** Settings > System > Updates: new versions from the GitHub repository, installed after Android asks. */
@Composable
internal fun UpdatesSection(vm: HomeViewModel) {
    Section(stringResource(R.string.ui_updates), "Ember ${app.ember.tv.BuildConfig.VERSION_NAME} · build ${app.ember.tv.BuildConfig.VERSION_CODE}",id="updates") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // What is installed now, next to what the check finds.
            Text("Installed: version ${app.ember.tv.BuildConfig.VERSION_NAME}, build ${app.ember.tv.BuildConfig.VERSION_CODE}" +
                (if (vm.updates.updateBeta) " · beta channel" else " · stable channel"), fontSize = 15.sp, color = Fg)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.ui_check_now)) { vm.updates.checkForUpdate(manual = true) }
                vm.updates.update?.let { u ->
                    Chip(if (vm.updates.updateProgress != null) "Downloading ${((vm.updates.updateProgress ?: 0f) * 100).toInt()}%" else "Install ${u.name}", selected = true) { vm.updates.installUpdate() }
                    if (vm.updates.updateProgress != null) Chip("Cancel") { vm.updates.cancelUpdate() }
                }
            }
            vm.updates.updateMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp) }
            vm.updates.update?.notes?.takeIf { it.isNotBlank() }?.let { Note(it.take(600)) }
            ToggleRow(
                "Beta updates",
                if (vm.updates.updateBeta) "Published beta builds from the public Ember repository. No token required."
                else "Published releases from the public Ember repository. No token required.",
                vm.updates.updateBeta,
            ) { vm.updates.toggleUpdateBeta() }
            if (vm.updates.updateBeta) {
                Text("Beta update source", color = Fg, fontSize = 14.sp)
                Choice(listOf("releases" to "Releases", "actions" to "Actions APK"), vm.updates.updateBetaSource) { vm.updates.updateBetaSource(it) }
                Note(if (vm.updates.updateBetaSource == "actions")
                    "Latest successful signed public beta build. Downloads use nightly.link; APKs expire after three days."
                    else "Only explicitly published public beta releases.")
            }
            ToggleRow(stringResource(R.string.ui_check_once_a_day), stringResource(R.string.ui_when_ember_starts_a_message_appears), vm.updates.updateAutoCheck) { vm.updates.toggleUpdateAutoCheck() }
            Note("Stable updates use published releases. Actions APKs are available after a successful beta build. Turning beta off returns to stable releases when a newer build is available.")
        }
    }
}

/** Settings > Stremio > Badges: bundled Detailed images, default Basic text, or a custom pack. */
@Composable
private fun BadgesSection(vm: HomeViewModel) {
    val f = vm.sourceFormat
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    Section(stringResource(R.string.ui_badges), stringResource(R.string.ui_badges_note)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonRow(spacing = 12.dp) {
                listOf(
                    app.ember.tv.data.Badges.LEGACY to stringResource(R.string.ui_badges_legacy),
                    app.ember.tv.data.Badges.MODERN to stringResource(R.string.ui_badges_modern),
                    app.ember.tv.data.Badges.CUSTOM to stringResource(R.string.ui_badges_custom),
                ).forEach { (mode, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chip(label, selected = f.badgeMode == mode) { f.updateBadgeMode(mode) }
                        ToolbarIcon("Preview $label badges", androidx.compose.material.icons.Icons.Outlined.Info) { preview = mode to label }
                    }
                }
            }
            Note(
                stringResource(
                    when (f.badgeMode) {
                        app.ember.tv.data.Badges.LEGACY -> R.string.ui_badges_legacy_note
                        app.ember.tv.data.Badges.CUSTOM -> R.string.ui_badges_custom_note
                        else -> R.string.ui_badges_modern_note
                    },
                ),
            )
            if (f.badgeMode == app.ember.tv.data.Badges.MODERN) {
                Note(stringResource(R.string.ui_badges_categories_note))
                ButtonRow(spacing = 8.dp) {
                    app.ember.tv.data.DetailedBadgeCategories.groups.forEach { (id, label) ->
                        val on = id !in f.detailedBadgeCategoriesOff
                        Chip(label, selected = on, maxLines = 1, modifier = Modifier.semantics {
                            stateDescription = if (on) "On" else "Off"
                        }) { f.toggleDetailedBadgeCategory(id) }
                    }
                }
            }
            if (f.badgeMode == app.ember.tv.data.Badges.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(url, { url = it }, "https://.../badges.json", width = 560)
                    Chip(stringResource(R.string.ui_badges_load)) { if (url.isNotBlank()) f.importBadges(url) }
                }
            }
            (f.badgeNote ?: app.ember.tv.data.Badges.message)?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        }
    }
    preview?.let { (mode, label) -> BadgePreview(mode, label, hiddenCategories = f.detailedBadgeCategoriesOff) { preview = null } }
}

/** The addon's logo from its manifest, or its initial when it has none or it does not load. */
@Composable
private fun AddonLogo(logo: String?, name: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var failed by remember(logo) { mutableStateOf(logo == null) }
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(WashHi), contentAlignment = Alignment.Center) {
        if (failed) Text(name.trim().take(1).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Fg)
        else coil.compose.AsyncImage(
            model = remember(logo) {
                coil.request.ImageRequest.Builder(context).data(logo).size(168)
                    .decoderFactory(coil.decode.SvgDecoder.Factory()).build()
            },
            contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            onError = { failed = true }, modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}
