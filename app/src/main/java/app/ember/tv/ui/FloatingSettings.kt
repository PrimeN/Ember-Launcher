package app.ember.tv.ui

import app.ember.tv.updateHomeAppRows

import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState

/*
 * Floating Settings (Settings > Appearance > Settings style: Floating):
 * a dark panel on the right with the settings you change most,
 * changed right there (OK switches; Left and Right step through choices), and shortcuts that
 * open the full Settings at the page you need. Opened by the Settings button or the Menu key.
 */

private val FocusBar:Color @Composable get()=PillColor
private val Soft:Color @Composable get()=if(isShadowEmber)ShadowEmberColors.Muted else if(isModernEmber)ModernEmberColors.Muted else Color(0xFFA9ADB4)
private val Bright = Color(0xFFECEDEF)

@Composable
internal fun FloatingSettings(vm: HomeViewModel, onClose: () -> Unit, onOpenFull: (String) -> Unit) {
    val accent = FloatingPanelStyle.accent
    val first = remember { FocusRequester() }
    var colourPicker by remember { mutableStateOf(false) }
    val open: (SettingsTab) -> Unit = { t -> onOpenFull(t.name) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompactPanelContent {
            DialogMotion()
            CompositionLocalProvider(LocalFloating provides true, LocalContentColor provides Bright) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().guardOpeningPress()) {
                    Column(
                        Modifier
                            // Full height against the right edge, like the TV's own settings panel.
                            .align(Alignment.CenterEnd)
                            .padding(vertical=12.dp,horizontal=12.dp)
                            .width(MenuSizing.panelWidth(maxWidth*MenuSizing.COMPACT_PANEL_SCALE, androidx.compose.ui.platform.LocalDensity.current.fontScale))
                            .fillMaxHeight()
                            .floatingPanelSurface()
                            // The on-screen keyboard: the panel's content moves above it (pages with a text field).
                            .imePadding(),
                    ) {
                        KeyboardResizesDialog()
                        Column(Modifier.fillMaxWidth().background(FloatingPanelStyle.header).padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Settings,null,tint=accent,modifier=Modifier.size(24.dp))
                                Text(stringResource(R.string.settings_quick_title),fontSize=TvTokens.Type.Dialog.sp,fontWeight=FontWeight.SemiBold,color=Bright)
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (vm.profiles.all.size > 1) vm.profiles.profile.name else "", fontSize = TvTokens.Type.Caption.sp, color = Soft,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(SimpleDateFormat("EEE d MMM yyyy", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date()), fontSize = TvTokens.Type.Caption.sp, color = Soft)
                            }
                            Text(stringResource(R.string.ui_ok_switches_change_back_closes), fontSize = TvTokens.Type.Caption.sp, color = Soft, modifier = Modifier.padding(top = 6.dp))
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberMenuScrollState()).padding(bottom = 24.dp)) {
                            Link(stringResource(R.string.ui_all_settings), "Every Ember setting",Modifier.focusRequester(first)) {onOpenFull(SettingsLocation.OVERVIEW)}
                            Link(stringResource(R.string.settings_search), "Find a setting") { open(SettingsTab.SEARCH) }

                            Heading(stringResource(R.string.ui_look), accent)
                            Pick(stringResource(R.string.ui_interface), SettingDefinitions.interfaceStyle.options, vm.look.uiStyle) { vm.look.updateUiStyle(it) }
                            Pick(stringResource(R.string.ui_theme), SettingDefinitions.theme.options, vm.look.theme) { vm.look.updateTheme(it) }
                            Line(Modifier, onClick = { colourPicker = true }) { focused ->
                                Text(SettingDefinitions.colourTheme.title, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                ThemePaletteSwatch(vm.look.accent)
                                Text(Accents.getOrElse(vm.look.accent) { Accents[0] }.first, fontSize = 15.sp,
                                    color = if (focused) OnPillColor else Soft, maxLines = 1, softWrap = false,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 150.dp))
                            }
                            Switch(stringResource(R.string.ui_oled_black), vm.look.oled) { vm.look.updateOled(!vm.look.oled) }
                            Pick(stringResource(R.string.ui_interface_size), SIZES, vm.look.uiSize) { vm.look.updateUiSize(it) }
                            Pick(stringResource(R.string.ui_text_size), SIZES, vm.look.textSize) { vm.look.updateTextSize(it) }
                            Pick(SettingDefinitions.performance.title,app.ember.tv.data.DevicePerformance.choices(Art.display4K),vm.look.devicePerformance) {vm.look.updateDevicePerformance(it)}
                            if(vm.look.devicePerformance=="custom")Link("Custom performance controls", "Analysis, motion, progressive images and prefetch") {open(SettingsTab.PERFORMANCE)}
                            Link(stringResource(R.string.settings_page_tv_device), stringResource(R.string.settings_summary_tv_device)) { open(SettingsTab.DEVICE_TOOLS) }
                            Link("Appearance", "Interface, theme, colour and menu") { open(SettingsTab.APPEARANCE) }
                            Link(stringResource(R.string.settings_page_performance), stringResource(R.string.settings_summary_performance)) { open(SettingsTab.PERFORMANCE) }

                            Heading(stringResource(R.string.ui_home), accent)
                            Switch(stringResource(R.string.ui_banner), vm.heroOn) { vm.updateHeroOn(!vm.heroOn) }
                            Pick(stringResource(R.string.ui_rows_of_apps), listOf("1" to "One", "2" to "Two"), vm.homeAppRows.toString()) { vm.updateHomeAppRows(it.toInt()) }
                            Link(stringResource(R.string.ui_home_layout), stringResource(R.string.ui_rows_order_and_apps_on_home)) { open(SettingsTab.HOME_ROWS) }
                            Link(stringResource(R.string.ui_catalogs), stringResource(R.string.ui_which_catalogs_show_and_in_what)) { open(SettingsTab.CATALOGS) }

                            Heading(stringResource(R.string.ui_watching), accent)
                            Switch(stringResource(R.string.ui_play_button), vm.playerSettings.playRules.enabled) { vm.playerSettings.updatePlayRules { it.copy(enabled = !it.enabled) } }
                            val c = vm.playerSettings.playbackConfig
                            Pick(SettingDefinitions.playerInterface.title, SettingDefinitions.playerInterface.options,
                                app.ember.tv.player.PlayerInterfaces.resolve(c, vm.look.uiStyle)) { v ->
                                vm.playerSettings.updatePlaybackConfig { it.copy(playerInterface = v) }
                            }
                            Pick(stringResource(R.string.ui_resume), SettingDefinitions.resume.options, c.resume) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(resume = v) } }
                            Pick(stringResource(R.string.ui_skip_intro), SettingDefinitions.skipIntro.options, c.skipIntro) { v -> vm.playerSettings.updatePlaybackConfig { it.copy(skipIntro = v) } }
                            Heading(stringResource(R.string.ui_sound_timing), accent)
                            AudioTimingControls(c.audioDelayMs, Modifier.padding(horizontal = 28.dp)) { v ->
                                vm.playerSettings.updatePlaybackConfig { it.copy(audioDelayMs = v) }
                            }
                            Pick(stringResource(R.string.ui_subtitles_at_start), SettingDefinitions.subtitles.options, vm.playerSettings.subtitleConfig.autoSelect) { v ->
                                vm.playerSettings.updateSubtitleConfig { it.copy(autoSelect = v) }
                            }
                            Link(stringResource(R.string.ui_player), "Player, resume, skip intro and next episode") { open(SettingsTab.PLAYER) }
                            Link(stringResource(R.string.settings_page_audio_video), stringResource(R.string.settings_summary_audio_video)) { open(SettingsTab.AUDIO_VIDEO) }

                            Heading("Sources and accounts", accent)
                            Link(stringResource(R.string.ui_addons), stringResource(R.string.ui_add_rename_or_remove_time_limit)) { open(SettingsTab.ADDONS) }
                            Link(stringResource(R.string.ui_profiles), stringResource(R.string.ui_who_s_watching_kids_profiles_and)) { open(SettingsTab.PROFILES) }

                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                runCatching { first.requestFocus() }
            }
        }
    }
    if (colourPicker) MediaDialog(SettingDefinitions.colourTheme.title, { colourPicker = false }) {
        ThemeColorPicker(vm.look.accent) { vm.look.updateAccent(it); colourPicker = false }
    }
}

private val SIZES = UiSizes.LABELS

@Composable
private fun Heading(text: String, accent: Color) {
    Text(text, color = accent, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
}

/** A full-width row; focused, a lighter bar. */
@Composable
private fun Line(modifier: Modifier, role: Role = Role.Button, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        role = role,
        // Inset from the panel's edges with round ends, so the bar sits inside the rounded panel.
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp).onFocusChanged { focused = it.isFocused },
        radius = TvTokens.Shape.Control,
        container = Color.Transparent,
        focusedContainer = FocusBar,
        focusedContent = OnPillColor,
        outline = false,
        focusedScale = TvTokens.Focus.None,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 14.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(focused) }
    }
}

/** A choice: the value on the right with ‹ ›; OK and Right go to the next value, Left to the previous. */
@Composable
private fun Pick(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
    onPick: (String) -> Unit,
) {
    val i = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    fun step(d: Int) {
        val j = if (wrap) (i + d + options.size) % options.size else (i + d).coerceIn(0, options.lastIndex)
        if (j != i) onPick(options[j].first)
    }
    Line(
        modifier.semantics {
            stateDescription = options.getOrNull(i)?.second.orEmpty()
            customActions = listOf(
                CustomAccessibilityAction("Previous value") { step(-1); true },
                CustomAccessibilityAction("Next value") { step(1); true },
            )
        }.onKeyEvent { e ->
            when (e.key) {
                Key.DirectionLeft, Key.DirectionRight -> {
                    if (e.type == KeyEventType.KeyDown) step(choiceStep(e.key==Key.DirectionRight,rtl))
                    true
                }
                else -> false
            }
        },
        onClick = { step(1) },
    ) { focused ->
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        // The arrows are always there (faint until focused), so the value does not shift when focused.
        val arrow = if (focused) OnPillColor else Color.Transparent
        Text(if(rtl)"›" else "‹", fontSize = 18.sp, color = arrow, modifier = Modifier.padding(end = 10.dp))
        Text(options.getOrNull(i)?.second.orEmpty(), modifier = Modifier.widthIn(max = 160.dp), fontSize = 15.sp, color = if (focused) OnPillColor else Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if(rtl)"‹" else "›", fontSize = 18.sp, color = arrow, modifier = Modifier.padding(start = 10.dp))
    }
}

/** An on/off setting with a switch in the accent colour. */
@Composable
private fun Switch(label: String, on: Boolean, onToggle: () -> Unit) = SettingToggle(label, null, on, onToggle)

/** Opens a page of the full Settings. */
@Composable
private fun Link(label: String, note: String?, modifier:Modifier=Modifier,onClick: () -> Unit) {
    Line(modifier, onClick = onClick) {focused->
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, maxLines = 2)
            note?.let { Text(it, fontSize = TvTokens.Type.Caption.sp, color = if(focused)OnPillColor.copy(alpha=.75f) else Soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,null,
            tint=if(focused)OnPillColor else Soft,modifier=Modifier.size(18.dp))
    }
}
