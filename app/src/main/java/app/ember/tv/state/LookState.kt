package app.ember.tv.state

import androidx.compose.runtime.mutableIntStateOf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.data.InterfaceStyles
import app.ember.tv.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Settings > Appearance: look, theme colour, day/night/OLED, sizes and Settings style. Split out of HomeViewModel. */
class LookState(
    private val prefs: Prefs,
    scope: CoroutineScope,
) {
    var heroArtworkMode by mutableStateOf(prefs.heroArtworkMode); private set
    var appFocusColors by mutableStateOf(prefs.appFocusColors); private set
    var showWatchedBadges by mutableStateOf(prefs.showWatchedBadges);private set
    var blurUnwatchedEpisodes by mutableStateOf(prefs.blurUnwatchedEpisodes);private set
    var cardFocusStyle by mutableStateOf(prefs.cardFocusStyle);private set
    var tileLabelsOff by mutableStateOf(prefs.tileLabelsOff);private set
    fun toggleTileLabel(kind:String){tileLabelsOff=app.ember.tv.data.TileLabels.toggle(tileLabelsOff,kind);prefs.tileLabelsOff=tileLabelsOff}
    fun updateShowWatchedBadges(value:Boolean){showWatchedBadges=value;prefs.showWatchedBadges=value}
    fun updateBlurUnwatchedEpisodes(value:Boolean){blurUnwatchedEpisodes=value;prefs.blurUnwatchedEpisodes=value}
    fun updateCardFocusStyle(value:String){cardFocusStyle=app.ember.tv.data.CardFocusStyle.normalize(value);prefs.cardFocusStyle=cardFocusStyle}
    var mediaFocusBackground by mutableStateOf(prefs.mediaFocusBackground); private set
    var collectionFocusBackground by mutableStateOf(prefs.collectionFocusBackground); private set
    fun updateAppFocusColors(value: Boolean) { appFocusColors = value; prefs.appFocusColors = value }
    fun updateMediaFocusBackground(value: String) { mediaFocusBackground = app.ember.tv.data.FocusBackground.normalize(value); prefs.mediaFocusBackground = mediaFocusBackground }
    fun updateCollectionFocusBackground(value: String) { collectionFocusBackground = app.ember.tv.data.FocusBackground.normalize(value); prefs.collectionFocusBackground = collectionFocusBackground }
    var heroCatalog by mutableStateOf(prefs.heroCatalog); private set
    fun updateHeroArtworkMode(value: String) {
        heroArtworkMode = app.ember.tv.data.HeroArtworkMode.normalize(value); prefs.heroArtworkMode = heroArtworkMode
    }
    fun updateHeroCatalog(value: String?) { heroCatalog = value?.takeIf { it.isNotBlank() }; prefs.heroCatalog = heroCatalog }
    var discoverSections by mutableStateOf(prefs.discoverSections); private set
    var serverSectionsOff by mutableStateOf(prefs.serverSectionsOff); private set
    var serverHeroOn by mutableStateOf(prefs.serverHeroOn); private set
    var serverHeroSource by mutableStateOf(prefs.serverHeroSource); private set
    fun toggleServerSection(id:String) {serverSectionsOff=if(id in serverSectionsOff)serverSectionsOff-id else serverSectionsOff+id;prefs.serverSectionsOff=serverSectionsOff}
    fun updateServerHeroOn(on:Boolean) {serverHeroOn=on;prefs.serverHeroOn=on}
    fun updateServerHeroSource(id:String?) {serverHeroSource=id;prefs.serverHeroSource=id}
    var aiDiscoveryEnabled by mutableStateOf(prefs.aiDiscoveryEnabled); private set
    fun updateAiDiscoveryEnabled(on: Boolean) { aiDiscoveryEnabled = on; prefs.aiDiscoveryEnabled = on }
    fun updateDiscoverSection(name: String, on: Boolean) {
        if (name !in DiscoverSection.entries.map { it.name }) return
        discoverSections = discoverSections + (name to on)
        prefs.discoverSections = discoverSections
    }
    fun resetDiscoverSections() { discoverSections = emptyMap(); prefs.discoverSections = emptyMap() }
    var uiStyle by mutableStateOf(prefs.uiStyle.also { Ui.light = it == InterfaceStyles.LIGHT; Ui.emberX = false }); private set
    var mediaPageLayout by mutableStateOf(prefs.mediaPageLayout); private set
    var navbarStyle by mutableStateOf(prefs.navbarStyle); private set
    fun updateNavbarStyle(value:String) {
        if(!app.ember.tv.data.NavbarStyles.available(uiStyle))return
        navbarStyle=app.ember.tv.data.NavbarStyles.normalize(value)
        prefs.navbarStyle=navbarStyle
    }
    fun updateMediaPageLayout(value: String) {
        mediaPageLayout = app.ember.tv.data.MediaPageLayouts.normalize(value)
        prefs.mediaPageLayout = mediaPageLayout
    }
    var accent by mutableIntStateOf(app.ember.tv.data.ColorThemes.normalize(prefs.accent)); private set
    var contentDensity by mutableStateOf(prefs.contentDensity); private set
    var devicePerformance by mutableStateOf(prefs.devicePerformance); private set
    fun updateDevicePerformance(value:String) {devicePerformance=app.ember.tv.data.DevicePerformance.normalize(value);prefs.devicePerformance=devicePerformance}
    var customPerformance by mutableStateOf(prefs.customPerformance); private set
    fun updateCustomPerformance(change:(app.ember.tv.data.CustomPerformance)->app.ember.tv.data.CustomPerformance) {customPerformance=change(customPerformance).normalized();prefs.customPerformance=customPerformance}
    fun applyEffectsPreset(id:String) {
        val preset=app.ember.tv.data.EffectPresets.options.firstOrNull {it.id==id} ?: return
        updateCustomPerformance {preset.effects};updateNavigationGlass(preset.glass);updateDevicePerformance("custom")
    }
    var setupChecklistHidden by mutableStateOf(prefs.setupChecklistHidden);private set
    fun hideSetupChecklist(value:Boolean){setupChecklistHidden=value;prefs.setupChecklistHidden=value}
    var navigationGlassMethod by mutableStateOf(prefs.navigationGlassMethod);private set
    fun updateNavigationGlassMethod(value:String){prefs.navigationGlassMethod=value;navigationGlassMethod=prefs.navigationGlassMethod}
    var navigationGlass by mutableStateOf(prefs.navigationGlass); private set
    var artworkQuality by mutableStateOf(prefs.artworkQuality); private set
    fun updateContentDensity(value: String) { contentDensity = value; prefs.contentDensity = value }
    fun updateNavigationGlass(value: Boolean) { navigationGlass = value; prefs.navigationGlass = value }
    fun updateArtworkQuality(value: String) { artworkQuality = value; prefs.artworkQuality = value }
    var modernCards by mutableStateOf(prefs.modernCards); private set

    fun updateUiStyle(style: String) {
        val supported = InterfaceStyles.home(style)
        uiStyle = supported
        prefs.uiStyle = supported
        Ui.light = supported == InterfaceStyles.LIGHT
        Ui.emberX = false
    }

    var lightWallpaper by mutableStateOf(prefs.lightWallpaper); private set
    var lightHome by mutableStateOf(prefs.lightHome); private set
    fun updateLightHome(change: (app.ember.tv.data.LightHomeOptions) -> app.ember.tv.data.LightHomeOptions) {
        lightHome = change(lightHome).normalized(); prefs.lightHome = lightHome
    }
    fun reloadHome() { lightWallpaper=prefs.lightWallpaper;lightHome=prefs.lightHome;heroCatalog=prefs.heroCatalog }
    var appIcons by mutableStateOf(prefs.appIcons); private set
    var theme by mutableStateOf(prefs.theme); private set
    var oled by mutableStateOf(prefs.oled.also { Ui.oled = it }); private set
    var uiSize by mutableStateOf(prefs.uiSize); private set
    var textSize by mutableStateOf(prefs.textSize); private set

    /** Day or night now: fixed, or by the clock for Auto (checked every minute). */
    private fun applyTheme() {
        Ui.day = when (theme) {
            "day" -> true
            "auto" -> java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 7 until 19
            else -> false
        }
    }

    private val themeClock = scope.launch {
        while (true) {
            applyTheme()
            kotlinx.coroutines.delay(60_000)
        }
    }

    fun updateTheme(v: String) {
        theme = v
        prefs.theme = v
        applyTheme()
    }

    fun updateOled(on: Boolean) {
        oled = on
        prefs.oled = on
        Ui.oled = on
    }

    var screenMargin by mutableStateOf(prefs.screenMargin); private set

    fun updateScreenMargin(v: String) {
        screenMargin = app.ember.tv.data.ScreenMargins.normalize(v)
        prefs.screenMargin = screenMargin
    }

    fun updateUiSize(v: String) {
        uiSize = v
        prefs.uiSize = v
    }

    fun updateTextSize(v: String) {
        textSize = v
        prefs.textSize = v
    }

    var settingsStyle by mutableStateOf(prefs.settingsStyle); private set

    /** "ember" or "tv": who gets the remote's Settings and Menu buttons. */
    var remoteSettingsKey by mutableStateOf(prefs.remoteSettingsKey); private set

    fun updateRemoteSettingsKey(v: String) {
        remoteSettingsKey = v
        prefs.remoteSettingsKey = v
    }

    /** Clicks for the remote's arrows and OK in every interface (ui/RemoteSounds.kt). */
    var remoteSounds by mutableStateOf(prefs.remoteSounds.also { app.ember.tv.ui.RemoteSounds.setEnabled(it) }); private set
    fun updateRemoteSounds(on: Boolean) {
        remoteSounds = on; prefs.remoteSounds = on
        app.ember.tv.ui.RemoteSounds.setEnabled(on)
    }
    var menuPosition by mutableStateOf(prefs.menuPosition); private set

    /** The floating Settings panel is open (not saved). */
    var settingsPanel by mutableStateOf(false)
    /** A Settings page to open the full Settings at (SettingsTab name), e.g. from the floating panel. */
    var settingsJump by mutableStateOf<String?>(null)
    /** Guarded full-page request, consumed by the root rather than the compact panel. */
    var settingsDestination by mutableStateOf<String?>(null)

    fun updateSettingsStyle(v: String) {
        settingsStyle = v
        prefs.settingsStyle = v
    }

    fun updateMenuPosition(v: String) {
        menuPosition = v
        prefs.menuPosition = v
    }

    fun updateAppIcons(v: String) {
        appIcons = v
        prefs.appIcons = v
    }

    fun updateLightWallpaper(v: String) {
        lightWallpaper = v
        prefs.lightWallpaper = v
    }

    fun updateModernCards(shape: String) {
        modernCards = shape
        prefs.modernCards = shape
    }

    fun updateAccent(index: Int) {
        accent = app.ember.tv.data.ColorThemes.normalize(index)
        prefs.accent = accent
        app.ember.tv.data.AppIcon.sync(accent)
    }
}
