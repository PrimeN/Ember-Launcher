package app.ember.tv.ui

import app.ember.tv.toggleBackgroundRefresh
import app.ember.tv.toggleFeature

import app.ember.tv.toggleHidden
import app.ember.tv.updateHomeAppRows

import androidx.compose.ui.res.stringResource
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

// Settings pages, split out of SettingsScreen.kt. General: menu, Home layout, look, posters, ratings, metadata, features.

// ---------------------------------------------------------------- General

/** Screen margins as slider steps (#215), in tenths of a percent of the screen. */
internal object MarginSteps {
    val values = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50)
    fun of(saved: String): Int = (app.ember.tv.data.ScreenMargins.normalize(saved).toFloat() * 10).let { kotlin.math.round(it).toInt() }
    fun text(tenths: Int): String = app.ember.tv.data.ScreenMargins.normalize((tenths / 10f).toString())
    fun label(tenths: Int): String = text(tenths) + "%"
}

/** One setting per line: name (and a note) on the left, On/Off on the right. */
@Composable
internal fun ToggleRow(label: String, note: String?, on: Boolean, onToggle: () -> Unit) {
    SettingToggle(label, note, on, onToggle)
}


/** The sections of one Look & feel page (or the Home pages that reuse these controls) for an interface. */
internal fun lookKeys(tab: SettingsTab, style: String): List<String> {
    val active = app.ember.tv.data.InterfaceStyles.home(style)
    return when (tab) {
        SettingsTab.APPEARANCE -> buildList {
            add("interface"); add("media-page-layout"); add("theme"); add("accent_colour")
            if (active != "light") {add("navbar-style");add("menu")}
            // #245: the tabs sit with the navbar they belong to.
            add("navbar-tabs")
            add("tile-labels"); add("row-addon-labels")
        }
        SettingsTab.SIZE -> listOf("interface-size", "text-size", "screen_margins", "density")
        SettingsTab.PERFORMANCE -> listOf("device-performance", "artwork", "focus-backgrounds", "card-focus")
        SettingsTab.REMOTE -> listOf("remote_s_settings_button", "remote-sounds", "auto-load")
        // #190: how app tiles look sits with the other app settings.
        SettingsTab.HOME_TOOLS -> buildList { if (active == "modern" || active == app.ember.tv.data.InterfaceStyles.EMBER_X) add("app_icons") }
        SettingsTab.LIGHT_HOME -> listOf("wallpaper")
        else -> emptyList()
    }
}

/** Every Look & feel section for an interface (Appearance, Size & spacing, Performance & effects, Remote). */
internal fun appearanceKeys(style: String): List<String> =
    listOf(SettingsTab.APPEARANCE, SettingsTab.SIZE, SettingsTab.PERFORMANCE, SettingsTab.REMOTE).flatMap { lookKeys(it, style) }

internal fun androidx.compose.foundation.lazy.LazyListScope.appearancePage(vm: HomeViewModel, tab: SettingsTab = SettingsTab.APPEARANCE) {
    lookKeys(tab, vm.look.uiStyle).forEach { key -> item(key = "look:$key") { AppearancePart(vm, key) } }
}

@Composable
private fun AppearancePart(vm: HomeViewModel, key: String) {
    when (key) {
        // #151: on Appearance, beside the other labels, instead of at the bottom of Home layout.
        "navbar-tabs" -> TopMenuSection(vm)
        "row-addon-labels" -> Section(SettingDefinitions.rowAddonLabels.title, stringResource(R.string.st_the_addon_s_name_after_each), id = "row-addon-labels") {
            ToggleRow(stringResource(R.string.st_show_addon_names), if (vm.look.rowAddonLabels) stringResource(R.string.st_shown_after_each_row_s_title) else stringResource(R.string.st_hidden_rows_show_only_the_catalog), vm.look.rowAddonLabels) {
                vm.look.updateRowAddonLabels(!vm.look.rowAddonLabels)
            }
        }
        "tile-labels" -> Section(stringResource(R.string.st_labels_under_tiles), stringResource(R.string.st_turn_off_a_kind_to_remove), id = "tile-labels") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                app.ember.tv.data.TileLabels.choices.forEach { (kind, label) ->
                    ToggleRow(label, when (kind) {
                        app.ember.tv.data.TileLabels.APPS -> stringResource(R.string.st_app_names_under_app_icons_and)
                        app.ember.tv.data.TileLabels.MEDIA -> stringResource(R.string.st_titles_and_progress_under_movie_and)
                        else -> stringResource(R.string.st_folder_names_under_collection_tiles)
                    }, kind !in vm.look.tileLabelsOff) { vm.look.toggleTileLabel(kind) }
                }
            }
        }
        "card-focus" -> Section(stringResource(R.string.st_card_focus_effects),stringResource(R.string.st_for_apps_and_media_cards_low),id="card-focus") {
            PictureChoice("cardfocus",app.ember.tv.data.CardFocusStyle.choices,app.ember.tv.data.CardFocusStyle.normalize(vm.look.cardFocusStyle),vm.look::updateCardFocusStyle)
        }
        "focus-backgrounds" -> Section(stringResource(R.string.st_focused_backgrounds), stringResource(R.string.st_tile_colors_use_already_loaded_images), id = "focus-backgrounds") { FocusBackgroundSettings(vm) }
        "interface" -> Section(
        stringResource(R.string.ui_interface),
        stringResource(R.string.st_ember_is_the_default_media_focused), id = "interface",
    ) {
        // #203: each choice as a small picture of the result.
        PictureChoice("ui",SettingDefinitions.interfaceStyle.options,vm.look.uiStyle,vm.look::updateUiStyle)
        if(isEmberX)Note(stringResource(R.string.st_ember_x_uses_a_dark_cinema))
    }
        "media-page-layout" -> Section(SettingDefinitions.mediaPageLayout.title,
            stringResource(R.string.st_choose_movie_and_show_details_independently), id = "media-page-layout") {
            PictureChoice("page", SettingDefinitions.mediaPageLayout.options, vm.look.mediaPageLayout, vm.look::updateMediaPageLayout)
        }
        "navbar-style" -> Section(SettingDefinitions.navbarStyle.title,
            stringResource(R.string.st_choose_navigation_for_ember_ember_light),id="navbar-style") {
            if(app.ember.tv.data.NavbarStyles.available(vm.look.uiStyle))
                PictureChoice("nav",SettingDefinitions.navbarStyle.options,vm.look.navbarStyle,vm.look::updateNavbarStyle)
            else Note(stringResource(R.string.st_light_uses_its_own_navigation))
        }
        "theme" -> Section(stringResource(R.string.ui_theme), stringResource(R.string.ui_night_dark_day_light_backgrounds_and), id = "theme") {
            PictureChoice("theme", listOf("night" to stringResource(R.string.ui_night), "day" to stringResource(R.string.ui_day), "auto" to stringResource(R.string.ui_auto)),
                vm.look.theme, vm.look::updateTheme)
            ToggleRow(stringResource(R.string.ui_oled_mode), stringResource(R.string.ui_true_black_backgrounds_at_night_deeper), vm.look.oled) { vm.look.updateOled(!vm.look.oled) }
        }
        "screen_margins" -> Section(stringResource(R.string.ui_screen_margins), stringResource(R.string.ui_keeps_menus_and_the_clock_clear)) {
        // #215: a slider in half-percent steps; values are tenths of a percent.
        val none = stringResource(R.string.ui_none)
        StepSlider(stringResource(R.string.ui_screen_margins), MarginSteps.values, MarginSteps.of(vm.look.screenMargin), { if (it == 0) none else MarginSteps.label(it) }) {
            vm.look.updateScreenMargin(MarginSteps.text(it))
        }
    }
        "interface-size" -> Section(stringResource(R.string.ui_interface_size), stringResource(R.string.ui_makes_everything_on_screen_smaller_or), id = "interface-size") {
        PictureChoice("uisize", UiSizes.LABELS, vm.look.uiSize, vm.look::updateUiSize)
    }
        "text-size" -> Section(stringResource(R.string.ui_text_size), stringResource(R.string.ui_text_only_on_top_of_the), id = "text-size") {
        PictureChoice("textsize", UiSizes.LABELS, vm.look.textSize, vm.look::updateTextSize)
    }
        "remote_s_settings_button" -> Section(stringResource(R.string.ui_remote_s_settings_button), stringResource(R.string.ui_ember_the_remote_s_settings_or)) {
        Choice(listOf("ember" to "Ember", "tv" to "TV or projector"), vm.look.remoteSettingsKey) { vm.look.updateRemoteSettingsKey(it) }
    }
        "auto-load" -> Section(stringResource(R.string.st_load_more_as_i_scroll), stringResource(R.string.st_discover_collections_media_libraries_and_epis), id = "auto-load") {
            SettingToggle(stringResource(R.string.st_load_more_as_i_scroll), null, vm.collections.autoPage) { vm.collections.updateAutoPage(!vm.collections.autoPage) }
        }
        "remote-sounds" -> Section(stringResource(R.string.st_remote_sounds), stringResource(R.string.st_a_soft_click_for_the_arrows), id = "remote-sounds") {
        val soundContext = androidx.compose.ui.platform.LocalContext.current
        SettingToggle(stringResource(R.string.st_remote_sounds), null, vm.look.remoteSounds) {
            vm.look.updateRemoteSounds(!vm.look.remoteSounds); RemoteSounds.warm(soundContext)
        }
    }
        "menu" -> if(vm.look.navbarStyle==app.ember.tv.data.NavbarStyles.EMBER)Section(stringResource(R.string.ui_menu), stringResource(R.string.ui_along_the_top_or_down_the)) {
            PictureChoice("menu", listOf("top" to stringResource(R.string.ui_top), "side" to stringResource(R.string.ui_side)),
                if (vm.look.menuPosition == "side") "side" else "top", vm.look::updateMenuPosition)
        }
        "wallpaper" -> LightHomeSettings(vm)
        "app_icons" -> Section(stringResource(R.string.ui_app_icons), stringResource(R.string.ui_how_your_apps_looks_on_home)) {
            PictureChoice("apps", listOf("banner" to stringResource(R.string.ui_banners), "circle" to stringResource(R.string.ui_circles), "square" to "Squares"),
                AppShapes.normalize(vm.look.appIcons), vm.look::updateAppIcons)
        }
        "home_cards" -> Section(stringResource(R.string.ui_home_cards), stringResource(R.string.ui_wide_uses_each_title_s_backdrop)) {
            ButtonRow(spacing = 10.dp) {
                Chip(stringResource(R.string.ui_wide), selected = vm.look.modernCards != "poster") { vm.look.updateModernCards("wide") }
                Chip(stringResource(R.string.ui_poster), selected = vm.look.modernCards == "poster") { vm.look.updateModernCards("poster") }
            }
        }
        "device-performance" -> Section(SettingDefinitions.performance.title,stringResource(R.string.st_one_choice_sets_image_quality_effects),id="device-performance") {
            // #223: each profile as a picture: a meter for Low to Ultra, Auto choosing, Custom as sliders.
            PictureChoice("perf",app.ember.tv.data.DevicePerformance.choices(Art.display4K),vm.look.devicePerformance,vm.look::updateDevicePerformance)
            val effective=LocalRenderOptions.current
            Text(when(effective.profile) {
                "low"->"Low: Economy images, no frosted blur, minimal motion. For low-memory TVs."
                "medium"->"Medium: Balanced images, prepared frosted glass, subtle motion and focus colours."
                "ultra"->"Ultra: everything in High, with 4K card artwork and hero pictures on this 4K display."
                "custom"->"Custom: choose image quality and each effect below."
                else->"High: High-quality images and gentle glass refraction on supported Android 13+ devices."
            },color=Fg,fontSize=TvTokens.Type.Compact.sp)
            val performanceContext=androidx.compose.ui.platform.LocalContext.current
            if(vm.look.devicePerformance=="auto") {
                Text((if(app.ember.tv.BuildConfig.DEBUG)"Auto observation is paused in debug builds." else if(RuntimeBudgets.slowAuto)"Auto observed sustained slow frames and is using lighter effects." else "Auto is using ${app.ember.tv.data.DevicePerformance.label(effective.profile)} for this TV.")+
                    " Auto never picks Ultra.",color=Mute,fontSize=13.sp)
                Chip(stringResource(R.string.st_reset_auto_observations)) {RuntimeBudgets.reset(performanceContext)}
            }
            Text(stringResource(R.string.tx_pictures_up_to_glass, effective.heroMaxWidth, effective.heroMaxHeight)+glassModeLabel(effective),color=Mute,fontSize=13.sp)
            if(!Art.display4K)Note(stringResource(R.string.st_ultra_appears_on_a_4k_display))
            if(vm.look.devicePerformance=="custom") {
                Text(stringResource(R.string.tx_image_quality),fontSize=15.sp)
                ButtonRow(spacing = 10.dp) { listOf("economy" to "Economy","balanced" to "Balanced","high" to "High").forEach { (value, label) ->
                    Chip(label, selected = vm.look.artworkQuality == value) { vm.look.updateArtworkQuality(value) }
                } }
                var customDetails by rememberSaveable {mutableStateOf(false)}
                Chip(if(customDetails)stringResource(R.string.st_hide_individual_effects) else stringResource(R.string.st_customize_individual_effects)) {customDetails=!customDetails}
                if(customDetails)CustomPerformanceControls(vm)
            }
        }
        "density" -> Section(SettingDefinitions.density.title, stringResource(R.string.st_card_and_text_spacing_independent_of), id = "density") {
        PictureChoice("density", SettingDefinitions.density.options, vm.look.contentDensity, vm.look::updateContentDensity)
    }
        "artwork" -> Section(SettingDefinitions.artwork.title, stringResource(R.string.st_navigation_dock_menus_and_media_buttons), id = "artwork") {
        ToggleRow(stringResource(R.string.st_glass), null, vm.look.navigationGlass) { vm.look.updateNavigationGlass(!vm.look.navigationGlass) }
        // Google TV works with Glass on or off; the see-through styles turn it on (#147).
        // #223: the same panel over a colourful page in each glass style.
        PictureChoice("glass",app.ember.tv.data.GlassStyle.choices,app.ember.tv.data.GlassStyle.normalize(vm.look.navigationGlassMethod)) {value->
            vm.look.updateNavigationGlassMethod(value)
            if(value!=app.ember.tv.data.GlassStyle.SOLID && !vm.look.navigationGlass)vm.look.updateNavigationGlass(true)
        }
        if(vm.look.navigationGlass || vm.look.navigationGlassMethod==app.ember.tv.data.GlassStyle.SOLID) {
            Note(stringResource(R.string.st_google_tv_draws_solid_panels_and))
            Note(stringResource(R.string.st_auto_follows_the_performance_profile_choose))
            Note(stringResource(R.string.st_now)+glassModeLabel(LocalRenderOptions.current)+stringResource(R.string.st_prepared_backgrounds_are_shared_and_reused))
        }
    }
        "accent_colour" -> Section(SettingDefinitions.colourTheme.title, stringResource(R.string.st_choose_colours_independently_of_the_interface), id="accent_colour") {
        ThemeColorPicker(vm.look.accent, vm.look::updateAccent)

    }
    }
}

@Composable
private fun glassModeLabel(options:RenderOptions):String = when(GlassPolicy.mode(options,android.os.Build.VERSION.SDK_INT,pressured=RuntimeBudgets.pressured)) {
    GlassMode.TINT->if(options.solidSurfaces)"solid surfaces, like Google TV" else "tint and highlights"
    GlassMode.FROSTED->"cached frosted glass"
    GlassMode.LIQUID->"refractive glass"
}

// ---------------------------------------------------------------- Posters

/** Well-known titles for the preview: a movie and a series. */
internal val PREVIEW_TITLES = listOf(
    Meta(id = "tt0111161", type = "movie", name = "The Shawshank Redemption", poster = "https://images.metahub.space/poster/medium/tt0111161/img"),
    Meta(id = "tt0903747", type = "series", name = "Breaking Bad", poster = "https://images.metahub.space/poster/medium/tt0903747/img"),
    Meta(id = "tt0816692", type = "movie", name = "Interstellar", poster = "https://images.metahub.space/poster/medium/tt0816692/img"),
)

@Composable
internal fun PostersPage(vm: HomeViewModel, openKeys: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev // redraw when the RPDB key changes
    val c = vm.metadata.posterConfig
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Section(stringResource(R.string.ui_poster_enhancement), stringResource(R.string.ui_replaces_the_posters_on_home_discover)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_off_addon_posters), selected = c.provider == PosterConfig.OFF) { vm.metadata.updatePosterConfig { it.copy(provider = PosterConfig.OFF) } }
                    Chip(stringResource(R.string.ui_rpdb), selected = c.provider == PosterConfig.RPDB) { vm.metadata.updatePosterConfig { it.copy(provider = PosterConfig.RPDB) } }
                    Chip(stringResource(R.string.ui_better_posters), selected = c.provider == PosterConfig.BETTER) { vm.metadata.updatePosterConfig { it.copy(provider = PosterConfig.BETTER) } }
                }
                Text(
                    when (c.provider) {
                        PosterConfig.RPDB -> "RPDB (ratingposterdb.com) adds ratings to posters. Works with IMDb and TMDB ids."
                        PosterConfig.BETTER -> "Better Posters (btttr.cc) adds ratings, genres and tags. Free, no key. Needs an IMDb id."
                        else -> "Posters come from your addons as they are."
                    } + if (c.provider != PosterConfig.OFF) " Titles it does not know keep their addon poster." else "",
                    color = Mute,
                    fontSize = 13.sp,
                    modifier = Modifier.width(760.dp),
                )
            }
        }

        Section(stringResource(R.string.ui_landscape_cards), stringResource(R.string.ui_landscape_cards_note)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val tier = Posters.rpdbTier()
                Choice(
                    listOf(
                        PosterConfig.LANDSCAPE_TITLE to stringResource(R.string.ui_landscape_title),
                        PosterConfig.LANDSCAPE_RPDB to stringResource(R.string.ui_landscape_rpdb),
                        PosterConfig.LANDSCAPE_PHOTO to stringResource(R.string.ui_landscape_photo),
                    ),
                    c.landscape,
                ) { v -> vm.metadata.updatePosterConfig { it.copy(landscape = v) } }
                Note(
                    when (c.landscape) {
                        PosterConfig.LANDSCAPE_RPDB -> stringResource(if (tier >= 2) R.string.ui_landscape_rpdb_note else R.string.ui_landscape_rpdb_tier)
                        PosterConfig.LANDSCAPE_PHOTO -> stringResource(R.string.ui_landscape_photo_note)
                        else -> stringResource(R.string.ui_landscape_title_note)
                    },
                )
            }
        }

        if (c.provider != PosterConfig.OFF) {
            Section(stringResource(R.string.ui_preview)) {
                ButtonRow(spacing = 14.dp) {
                    PREVIEW_TITLES.forEach { m ->
                        PosterImage(m, Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(10.dp)), c)
                    }
                }
            }
        }

        when (c.provider) {
            PosterConfig.RPDB -> RpdbOptions(vm, c, openKeys)
            PosterConfig.BETTER -> BetterPostersOptions(vm, c)
        }
    }
}

@Composable
internal fun RpdbOptions(vm: HomeViewModel, c: PosterConfig, openKeys: () -> Unit) {
    val entered = ApiKeys.isEntered("RPDB_API_KEY")
    val tier = Posters.rpdbTier()
    val paid = tier >= 1
    Section(stringResource(R.string.ui_key), if (entered) stringResource(R.string.st_your_key_tier, tier) else stringResource(R.string.st_using_the_free_key_it_gives)) {
        Chip(if (entered) stringResource(R.string.st_change_the_key_in_api_keys) else stringResource(R.string.st_add_your_key_in_api_keys)) { openKeys() }
    }
    Section(stringResource(R.string.ui_style), if (paid) stringResource(R.string.st_which_ratings_the_poster_shows) else stringResource(R.string.st_needs_a_paid_key_tier_1)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ButtonRow(spacing = 10.dp) {
                Posters.RPDB_STYLES.forEach { (id, label) ->
                    Chip(label, selected = c.rpdbStyle == id && paid) { if (paid) vm.metadata.updatePosterConfig { it.copy(rpdbStyle = id) } }
                }
            }
            ToggleRow(stringResource(R.string.ui_textless_artwork), null, c.rpdbTextless && paid) {
                if (paid) vm.metadata.updatePosterConfig { it.copy(rpdbTextless = !it.rpdbTextless) }
            }
        }
    }
    Section(stringResource(R.string.ui_language), if (paid) stringResource(R.string.st_poster_artwork_language_where_rpdb_has) else stringResource(R.string.st_needs_a_paid_key_tier_1)) {
        LanguageChips(selected = if (paid) c.rpdbLang else null) { lang -> if (paid) vm.metadata.updatePosterConfig { it.copy(rpdbLang = lang) } }
    }
}

@Composable
internal fun BetterPostersOptions(vm: HomeViewModel, c: PosterConfig) {
    Section(stringResource(R.string.ui_shown_on_the_poster)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(stringResource(R.string.ui_rating), null, c.bpRating) { vm.metadata.updatePosterConfig { it.copy(bpRating = !it.bpRating) } }
            ToggleRow(stringResource(R.string.ui_genre), null, c.bpGenre) { vm.metadata.updatePosterConfig { it.copy(bpGenre = !it.bpGenre) } }
            ToggleRow(stringResource(R.string.ui_quality_tags), stringResource(R.string.ui_4k_dolby_vision_atmos_badges), c.bpQuality) { vm.metadata.updatePosterConfig { it.copy(bpQuality = !it.bpQuality) } }
            ToggleRow(stringResource(R.string.ui_age_rating), stringResource(R.string.ui_pg_13_tv_ma_r), c.bpAgeRating) { vm.metadata.updatePosterConfig { it.copy(bpAgeRating = !it.bpAgeRating) } }
            ToggleRow(stringResource(R.string.ui_trending_tags), stringResource(R.string.ui_trending_new_imdb_3), c.bpTrending) { vm.metadata.updatePosterConfig { it.copy(bpTrending = !it.bpTrending) } }
        }
    }
    if (c.bpRating) {
        Section(stringResource(R.string.ui_rating_source)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                item(key = "avg") { Chip(stringResource(R.string.ui_average), selected = c.bpRatingSource == null) { vm.metadata.updatePosterConfig { it.copy(bpRatingSource = null) } } }
                items(Posters.RATING_SOURCES, key = { it.first }) { (code, label) ->
                    Chip(label, selected = c.bpRatingSource == code) { vm.metadata.updatePosterConfig { it.copy(bpRatingSource = code) } }
                }
            }
        }
    }
    Section(stringResource(R.string.ui_language), stringResource(R.string.ui_poster_artwork_language)) {
        LanguageChips(selected = c.bpLang) { lang -> vm.metadata.updatePosterConfig { it.copy(bpLang = lang) } }
    }
}

@Composable
internal fun LanguageChips(selected: String?, onPick: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
        item(key = "en") { Chip(stringResource(R.string.ui_english), selected = selected == null) { onPick(null) } }
        items(Posters.LANGUAGES, key = { it.first }) { (code, label) ->
            Chip(label, selected = selected == code) { onPick(code) }
        }
    }
}

// ---------------------------------------------------------------- Ratings

internal fun LazyListScope.ratingsPage(vm: HomeViewModel, openKeys: () -> Unit) {
    item(key = "ratingshead") {
        Section(stringResource(R.string.ui_ratings), stringResource(R.string.ui_shown_on_title_screens_under_the)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow(stringResource(R.string.ui_show_ratings), null, vm.metadata.ratingsOn) { vm.metadata.updateRatingsOn(!vm.metadata.ratingsOn) }
                Chip(stringResource(R.string.ui_api_keys)) { openKeys() }
                Text(
                    stringResource(R.string.ui_an_mdblist_key_gives_imdb_tmdb),
                    color = Mute,
                    fontSize = 13.sp,
                    modifier = Modifier.width(760.dp),
                )
            }
        }
    }
    if (vm.metadata.ratingsOn) {
        val list = vm.metadata.ratingSources
        itemsIndexed(list, key = { _, s -> "rs:${s.id}" }) { i, s ->
            @Suppress("UNUSED_VARIABLE")
            val rev = vm.keysRev
            val ready = when (s.id) {
                "trakt" -> ApiKeys.get("TRAKT_CLIENT_ID").isNotBlank() || ApiKeys.get("MDBLIST_API_KEY").isNotBlank()
                "metacritic", "tomatoes" -> ApiKeys.get("MDBLIST_API_KEY").isNotBlank() || ApiKeys.get("OMDB_API_KEY").isNotBlank()
                "popcorn" -> ApiKeys.get("MDBLIST_API_KEY").isNotBlank()
                "tmdb" -> ApiKeys.get("TMDB_API_KEY").isNotBlank() || ApiKeys.get("MDBLIST_API_KEY").isNotBlank()
                else -> true
            }
            val on = s.id !in vm.metadata.ratingsOff
            Row(
                modifier = Modifier.width(860.dp).background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { RatingIcon(s, height = 26) }
                Column(Modifier.weight(1f)) {
                    Text(s.label, fontSize = 16.sp)
                    Text(
                        (if (ready) "" else "Not available yet. ") + s.needs,
                        color = Mute,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SwitchChip("", on) { vm.metadata.toggleRatingSource(s.id) }
                MoveButton(s.label, i, list.lastIndex) { step -> vm.metadata.moveRatingSource(s.id, step) }
            }
        }
    }
}

// ---------------------------------------------------------------- Metadata (AniList, TVDB)

/** One On/Off setting in a section: the same full-width row as every other boolean setting (#36). */
@Composable
internal fun OnOff(label: String, on: Boolean, note: String? = null, onToggle: () -> Unit) =
    SettingToggle(label, note, on, onToggle)

@Composable
internal fun MetadataPage(vm: HomeViewModel, openKeys: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val rev = vm.keysRev
    val c = vm.metadata.metaConfig
    val tvdbReady = ApiKeys.get("TVDB_API_KEY").isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // #263: a catalog addon that serves its own details and artwork is used as it is.
        Section(stringResource(R.string.tx_addon_metadata), stringResource(R.string.tx_addon_metadata_about), id = "addon-metadata") {
            ToggleRow(stringResource(R.string.tx_addon_metadata_first), stringResource(R.string.tx_addon_metadata_first_note), c.addonFirst) {
                vm.metadata.updateMetaConfig { it.copy(addonFirst = !it.addonFirst) }
            }
        }
        Section(stringResource(R.string.ui_metadata), stringResource(R.string.ui_better_details_on_title_screens_anilist)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ButtonRow(spacing = 10.dp) {
                    Chip(stringResource(R.string.ui_fill_in_missing_details), selected = !c.replace) { vm.metadata.updateMetaConfig { it.copy(replace = false) } }
                    Chip(stringResource(R.string.ui_replace_the_addon_s_details), selected = c.replace) { vm.metadata.updateMetaConfig { it.copy(replace = true) } }
                }
                Text(
                    stringResource(R.string.ui_fill_in_keeps_what_your_addon),
                    color = Mute,
                    fontSize = 13.sp,
                    modifier = Modifier.width(760.dp),
                )
            }
        }

        Section(stringResource(R.string.ui_anilist_anime), stringResource(R.string.ui_free_no_key_found_by_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnOff(stringResource(R.string.st_use_anilist), c.anilistOn) { vm.metadata.updateMetaConfig { it.copy(anilistOn = !it.anilistOn) } }
                if (c.anilistOn) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleRow(stringResource(R.string.ui_description), null, c.anilistDescription) { vm.metadata.updateMetaConfig { it.copy(anilistDescription = !it.anilistDescription) } }
                        ToggleRow(stringResource(R.string.ui_artwork), null, c.anilistArtwork) { vm.metadata.updateMetaConfig { it.copy(anilistArtwork = !it.anilistArtwork) } }
                        ToggleRow(stringResource(R.string.ui_genres), null, c.anilistGenres) { vm.metadata.updateMetaConfig { it.copy(anilistGenres = !it.anilistGenres) } }
                        ToggleRow(stringResource(R.string.ui_studio_status_next_episode), null, c.anilistDetails) { vm.metadata.updateMetaConfig { it.copy(anilistDetails = !it.anilistDetails) } }
                    }
                    Text(stringResource(R.string.ui_title), fontSize = 15.sp)
                    ButtonRow(spacing = 10.dp) {
                        listOf("addon" to "Addon's title", "english" to "English", "romaji" to "Romaji").forEach { (id, label) ->
                            Chip(label, selected = c.anilistTitle == id) { vm.metadata.updateMetaConfig { it.copy(anilistTitle = id) } }
                        }
                    }
                }
            }
        }

        Section(stringResource(R.string.ui_tvdb_tv_shows), if (tvdbReady) stringResource(R.string.st_using_your_tvdb_key) else stringResource(R.string.st_needs_a_tvdb_api_key)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnOff(stringResource(R.string.st_use_tvdb), c.tvdbOn) { vm.metadata.updateMetaConfig { it.copy(tvdbOn = !it.tvdbOn) } }
                Chip(if (tvdbReady) stringResource(R.string.st_change_the_key_in_api_keys) else stringResource(R.string.ui_add_the_key_in_api_keys)) { openKeys() }
                if (c.tvdbOn) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleRow(stringResource(R.string.ui_description), null, c.tvdbDescription) { vm.metadata.updateMetaConfig { it.copy(tvdbDescription = !it.tvdbDescription) } }
                        ToggleRow(stringResource(R.string.ui_artwork), null, c.tvdbArtwork) { vm.metadata.updateMetaConfig { it.copy(tvdbArtwork = !it.tvdbArtwork) } }
                        ToggleRow(stringResource(R.string.ui_episode_names_and_thumbnails), null, c.tvdbEpisodes) { vm.metadata.updateMetaConfig { it.copy(tvdbEpisodes = !it.tvdbEpisodes) } }
                        ToggleRow(stringResource(R.string.ui_network_and_status), null, c.tvdbDetails) { vm.metadata.updateMetaConfig { it.copy(tvdbDetails = !it.tvdbDetails) } }
                        ToggleRow(stringResource(R.string.ui_also_for_anime), null, c.tvdbForAnime) { vm.metadata.updateMetaConfig { it.copy(tvdbForAnime = !it.tvdbForAnime) } }
                    }
                    Text(stringResource(R.string.ui_language), fontSize = 15.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(6.dp)) {
                        items(MetaEnhance.TVDB_LANGUAGES, key = { it.first }) { (code, label) ->
                            Chip(label, selected = c.tvdbLanguage == code) { vm.metadata.updateMetaConfig { it.copy(tvdbLanguage = code) } }
                        }
                    }
                    Text(
                        stringResource(R.string.ui_tvdb_is_found_by_the_title),
                        color = Mute,
                        fontSize = 12.sp,
                        modifier = Modifier.width(760.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Features

/** Whole features on or off. Off features load nothing at start, so Home opens faster. */
@Composable
internal fun FeaturesPage(vm: HomeViewModel) {
    val F = app.ember.tv.data.Features
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        SetupChecklist(vm)
        Section(stringResource(R.string.ui_features), stringResource(R.string.ui_switch_off_what_you_don_t)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow(stringResource(R.string.ui_live_tv), stringResource(R.string.ui_iptv_channels_the_guide_and_the), vm.featureOn(F.LIVE)) { vm.toggleFeature(F.LIVE) }
                ToggleRow(stringResource(R.string.ui_media_servers), stringResource(R.string.ui_plex_jellyfin_and_emby_rows_on), vm.featureOn(F.SERVERS)) { vm.toggleFeature(F.SERVERS) }
                ToggleRow(stringResource(R.string.ui_trackers), stringResource(R.string.ui_trakt_simkl_and_the_rest_continue), vm.featureOn(F.TRACKERS)) { vm.toggleFeature(F.TRACKERS) }
                ToggleRow(stringResource(R.string.ui_extra_home_rows), stringResource(R.string.ui_recommended_because_you_watched_hidden_gems), vm.featureOn(F.SMART_ROWS)) { vm.toggleFeature(F.SMART_ROWS) }
                ToggleRow(stringResource(R.string.ui_rotating_banner), stringResource(R.string.ui_the_large_picture_at_the_top), vm.heroOn) { vm.updateHeroOn(!vm.heroOn) }
                ToggleRow(stringResource(R.string.ui_cast_crew), stringResource(R.string.ui_directors_and_cast_on_title_screens), vm.featureOn(F.CAST)) { vm.toggleFeature(F.CAST) }
                ToggleRow(stringResource(R.string.ui_ratings), stringResource(R.string.ui_imdb_trakt_and_other_ratings_on), vm.metadata.ratingsOn) { vm.metadata.updateRatingsOn(!vm.metadata.ratingsOn) }
                ToggleRow(stringResource(R.string.ui_refresh_in_the_background), stringResource(R.string.ui_every_few_hours_on_the_network), vm.backgroundRefresh) { vm.toggleBackgroundRefresh() }
            }
        }
        Section(stringResource(R.string.ui_off_unless_you_set_them_up)) {
            Note(
                stringResource(R.string.ui_where_to_watch_metadata_anilist_tvdb),
            )
        }
    }
}
