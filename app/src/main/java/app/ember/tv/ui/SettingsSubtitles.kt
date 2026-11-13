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

        Section(stringResource(R.string.ui_languages), if (c.languages.isEmpty()) stringResource(R.string.st_using_the_tv_s_language, (if (androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language != "en") " and English" else "")) else stringResource(R.string.st_first_is_the_main_one_up)) {
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
                OnOff(stringResource(R.string.st_your_stremio_addons), c.addonSubs) { vm.playerSettings.updateSubtitleConfig { it.copy(addonSubs = !it.addonSubs) } }
                OnOff(stringResource(R.string.st_opensubtitles_free_no_key), c.stremioOpenSubs) { vm.playerSettings.updateSubtitleConfig { it.copy(stremioOpenSubs = !it.stremioOpenSubs) } }
                OnOff(stringResource(R.string.st_opensubtitles_com), c.openSubsCom, note = if (osReady) "Key set" else "Needs an API key") { vm.playerSettings.updateSubtitleConfig { it.copy(openSubsCom = !it.openSubsCom) } }
                OnOff(stringResource(R.string.st_subdl), c.subdl, note = if (subdlReady) "Key set" else "Needs an API key") { vm.playerSettings.updateSubtitleConfig { it.copy(subdl = !it.subdl) } }
                Chip(stringResource(R.string.ui_api_keys)) { openKeys() }
                Note(
                    stringResource(R.string.ui_opensubtitles_free_is_the_same_service) +
                        stringResource(R.string.st_opensubtitles_com_with_a_key_adds) +
                        stringResource(R.string.st_subdl_adds_a_second_large_library),
                )
            }
        }

        Section(stringResource(R.string.ui_matching)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnOff(stringResource(R.string.st_match_to_the_exact_file), c.hashMatch) { vm.playerSettings.updateSubtitleConfig { it.copy(hashMatch = !it.hashMatch) } }
                OnOff(stringResource(R.string.st_skip_machine_translated), c.skipMachine) { vm.playerSettings.updateSubtitleConfig { it.copy(skipMachine = !it.skipMachine) } }
                Choice(
                    listOf("avoid" to "Avoid", "include" to "No preference", "prefer" to "Prefer", "exclude" to "Never"),
                    c.hearingImpaired, stringResource(R.string.ui_hearing_impaired_sdh),
                ) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(hearingImpaired = m) } }
                Note(
                    stringResource(R.string.ui_match_to_the_exact_file_reads) +
                        stringResource(R.string.st_the_rest_are_ranked_by_how),
                )
            }
        }

        Section(stringResource(R.string.ui_look)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnOff(stringResource(R.string.st_preserve_authored_subtitle_styles),c.authoredStyles) {vm.playerSettings.updateSubtitleConfig {it.copy(authoredStyles=!it.authoredStyles)}}
                OnOff(stringResource(R.string.st_use_system_caption_style),c.systemStyle) {vm.playerSettings.updateSubtitleConfig {it.copy(systemStyle=!it.systemStyle)}}
                // #219: a live preview, then every choice as a sample of itself.
                if (c.systemStyle) Note(stringResource(R.string.st_using_the_tv_s_caption_style))
                SubtitlePreview(c)
                SubtitleChoice("Size", SubtitleLooks.sizes, c.size, c, { x, v -> x.copy(size = v) }) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(size = m) } }
                SubtitleChoice("Outline", SubtitleLooks.backgrounds, c.background, c, { x, v -> x.copy(background = v) }) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(background = m) } }
                SubtitleChoice("Colour", SubtitleLooks.colors, c.color, c, { x, v -> x.copy(color = v) }) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(color = m) } }
                SubtitleChoice(stringResource(R.string.ui_position), SubtitleLooks.positions, c.position, c, { x, v -> x.copy(position = v) }) { m -> vm.playerSettings.updateSubtitleConfig { it.copy(position = m) } }
                Note(stringResource(R.string.st_higher_keeps_subtitles_above_a_projector))
                SubtitleFontSettings(vm, c)
            }
        }
    }
}
/** Subtitle fonts (#219): Android's own, or a custom .ttf or .otf from a link. */
@Composable
private fun SubtitleFontSettings(vm: HomeViewModel, c: app.ember.tv.data.SubtitleConfig) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val hasCustom = app.ember.tv.data.SubtitleFonts.hasCustom(context)
    val fonts = app.ember.tv.data.SubtitleFonts.options.map { (id, label) -> id to if (id == app.ember.tv.data.SubtitleFonts.CUSTOM && hasCustom && c.customFontName.isNotBlank()) c.customFontName else label }
    SubtitleChoice("Font", fonts, app.ember.tv.data.SubtitleFonts.normalize(c.font), c, { x, v -> x.copy(font = v) }) { f ->
        if (f == app.ember.tv.data.SubtitleFonts.CUSTOM && !hasCustom) message = "Load a font from a link first."
        else vm.playerSettings.updateSubtitleConfig { it.copy(font = f) }
    }
    Text(stringResource(R.string.tx_custom_font), fontSize = 15.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(link, { link = it.take(500) }, "Link to a .ttf or .otf file")
        Chip(if (busy) stringResource(R.string.tx_loading) else if (hasCustom) stringResource(R.string.st_replace_font) else stringResource(R.string.st_load_font)) {
            if (busy) return@Chip
            busy = true; message = null
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.ember.tv.data.SubtitleFonts.save(context, link, subtitleFontClient) }
                busy = false
                result.onSuccess { name -> link = ""; message = "Loaded $name."; vm.playerSettings.updateSubtitleConfig { it.copy(font = app.ember.tv.data.SubtitleFonts.CUSTOM, customFontName = name) } }
                    .onFailure { message = it.message ?: "Couldn't load that font." }
            }
        }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
    Note(stringResource(R.string.st_use_a_direct_link_to_the))
}
private val subtitleFontClient by lazy { okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }

// ---------------------------------------------------------------- Stremio: stream result format
