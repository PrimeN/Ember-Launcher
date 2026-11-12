package app.ember.tv.phone

import app.ember.tv.HomeViewModel
import app.ember.tv.data.CardFocusStyle
import app.ember.tv.data.DevicePerformance
import app.ember.tv.data.FocusBackground
import app.ember.tv.data.Features
import app.ember.tv.data.GlassStyle
import app.ember.tv.data.InterfaceStyles
import app.ember.tv.data.LightLayout
import app.ember.tv.data.Prefs
import app.ember.tv.data.TileLabels
import app.ember.tv.player.PlayerInterfaces
import app.ember.tv.ui.SettingDefinitions
import app.ember.tv.ui.UiSizes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

internal enum class PhoneSettingKind { TOGGLE, CHOICE, NUMBER }

/** The answer to one change from the phone: [setting] as it is now, when it was applied. */
data class PhoneSettingResult(val ok: Boolean, val message: String, val setting: PhoneSettingView? = null)

/** The Setup tab's checklist and the version shown under Diagnostics. */
data class PhoneStatus(val version: String, val steps: List<Pair<String, Boolean>>)

/** What the phone page shows for one setting (#49): read on the TV's main thread. */
data class PhoneSettingView(
    val id: String, val group: String, val label: String, val note: String?, val kind: String,
    val options: List<Pair<String, String>>, val value: String, val min: Int = 0, val max: Int = 0,
)

/**
 * Every TV setting the phone page can change (#49), grouped like the TV's Settings. Settings with
 * their own state call that state's update function, so the TV changes at once; the fields of
 * config objects (player, subtitles, metadata, posters, play rules, performance, Light Home) are
 * listed from the objects themselves, so a field added later appears on the phone too.
 */
internal class PhoneSettings(private val vm: HomeViewModel) {
    private class Def(
        val id: String, val group: String, val label: String, val note: String?, val kind: PhoneSettingKind,
        val options: List<Pair<String, String>> = emptyList(), val range: IntRange = 0..0,
        val read: () -> String, val write: (String) -> Unit,
    )
    private class Field(val label: String, val options: List<Pair<String, String>> = emptyList(), val range: IntRange? = null, val note: String? = null)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun all(): List<PhoneSettingView> = defs().map { d ->
        PhoneSettingView(d.id, d.group, d.label, d.note, d.kind.name.lowercase(), d.options, runCatching(d.read).getOrDefault(""), d.range.first, d.range.last)
    }

    /** Applies one change; returns the setting as it is now, or throws with a message for the phone. */
    fun change(id: String, value: String): PhoneSettingView {
        val d = defs().firstOrNull { it.id == id } ?: error("That setting is not available.")
        val v = value.trim()
        when (d.kind) {
            PhoneSettingKind.TOGGLE -> require(v == "true" || v == "false") { "Choose On or Off." }
            PhoneSettingKind.CHOICE -> require(d.options.any { it.first == v }) { "Choose one of the listed options." }
            PhoneSettingKind.NUMBER -> require(v.toIntOrNull()?.let { it in d.range } == true) { "Enter a number from ${d.range.first} to ${d.range.last}." }
        }
        if (d.read() != v) d.write(v)
        return all().first { it.id == id }
    }

    private fun defs(): List<Def> {
        val out = mutableListOf<Def>()
        fun add(d: Def) { out += d }
        val look = vm.look
        fun toggle(id: String, group: String, label: String, note: String? = null, read: () -> Boolean, write: (Boolean) -> Unit) =
            add(Def(id, group, label, note, PhoneSettingKind.TOGGLE, read = { read().toString() }, write = { write(it == "true") }))
        fun choice(id: String, group: String, label: String, options: List<Pair<String, String>>, note: String? = null, read: () -> String, write: (String) -> Unit) =
            add(Def(id, group, label, note, PhoneSettingKind.CHOICE, options, read = read, write = write))

        // ---- Look
        val LOOK = "Look"
        choice("look.interface", LOOK, "Interface", InterfaceStyles.options, read = { look.uiStyle }, write = look::updateUiStyle)
        if(app.ember.tv.data.NavbarStyles.available(look.uiStyle))choice("look.navbar",LOOK,"Navbar style",SettingDefinitions.navbarStyle.options,
            read={look.navbarStyle},write=look::updateNavbarStyle)
        choice("look.mediaPageLayout", LOOK, "Media page layout", SettingDefinitions.mediaPageLayout.options,
            read = { look.mediaPageLayout }, write = look::updateMediaPageLayout)
        choice("look.theme", LOOK, "Theme", SettingDefinitions.theme.options, read = { look.theme }, write = look::updateTheme)
        toggle("look.oled", LOOK, "OLED black at night", read = { look.oled }, write = look::updateOled)
        choice("look.accent", LOOK, "Colour theme", SettingDefinitions.colourTheme.options, read = { look.accent.toString() }, write = { look.updateAccent(it.toInt()) })
        choice("look.size", LOOK, "Interface size", UiSizes.LABELS, read = { look.uiSize }, write = look::updateUiSize)
        choice("look.text", LOOK, "Text size", UiSizes.LABELS, read = { look.textSize }, write = look::updateTextSize)
        // #232: the TV's margin steps (#209), so the phone slides through the same values.
        choice("look.margins", LOOK, "Screen margins", app.ember.tv.ui.MarginSteps.values.map { app.ember.tv.ui.MarginSteps.text(it) to if (it == 0) "None" else app.ember.tv.ui.MarginSteps.label(it) },
            read = { app.ember.tv.ui.MarginSteps.text(app.ember.tv.ui.MarginSteps.of(look.screenMargin)) }, write = look::updateScreenMargin)
        choice("look.density", LOOK, "Media card density", SettingDefinitions.density.options, read = { look.contentDensity }, write = look::updateContentDensity)
        if(app.ember.tv.data.NavbarStyles.available(look.uiStyle) && look.navbarStyle==app.ember.tv.data.NavbarStyles.EMBER)choice("look.menu", LOOK, "Menu position (Ember)", listOf("top" to "Top", "side" to "Side"), read = { look.menuPosition }, write = look::updateMenuPosition)
        choice("look.appIcons", LOOK, "App icons", app.ember.tv.ui.AppShapes.options, read = { look.appIcons }, write = look::updateAppIcons)
        choice("look.cards", LOOK, "Home cards (Ember)", listOf("wide" to "Wide", "poster" to "Poster"), read = { look.modernCards }, write = look::updateModernCards)
        choice("look.wallpaper", LOOK, "Light Home wallpaper", LightLayout.backgrounds, read = { look.lightWallpaper }, write = look::updateLightWallpaper)
        TileLabels.choices.forEach { (kind, label) ->
            toggle("look.labels.$kind", LOOK, "Labels under tiles: $label", read = { kind !in look.tileLabelsOff }, write = { on -> if (on == (kind in look.tileLabelsOff)) look.toggleTileLabel(kind) })
        }
        toggle("look.rowAddonLabels", LOOK, "Addon names on rows", read = { look.rowAddonLabels }, write = look::updateRowAddonLabels)
        choice("look.performance", LOOK, "Performance profile",
            DevicePerformance.choices(app.ember.tv.ui.Art.display4K || look.devicePerformance == "ultra"), read = { look.devicePerformance }, write = look::updateDevicePerformance)
        choice("look.quality", LOOK, "Image quality (Custom profile)", listOf("economy" to "Economy", "balanced" to "Balanced", "high" to "High"), read = { look.artworkQuality }, write = look::updateArtworkQuality)
        out.config(serializer<app.ember.tv.data.CustomPerformance>(), "look.custom", LOOK, "Custom profile: ", { look.customPerformance }, look::updateCustomPerformance, mapOf(
            "heroAnalysis" to Field("Hero colour analysis"), "colorAnalysis" to Field("Tile colour analysis"), "animations" to Field("Animations"),
            "progressiveArtwork" to Field("Progressive artwork"), "collectionMotion" to Field("Animated collections"), "prefetch" to Field("Rows loaded ahead", range = app.ember.tv.data.CustomPerformance.PREFETCH)))
        toggle("look.glass", LOOK, "Glass", read = { look.navigationGlass }, write = look::updateNavigationGlass)
        choice("look.glassStyle", LOOK, "Glass style", GlassStyle.choices, read = { look.navigationGlassMethod }, write = look::updateNavigationGlassMethod)
        choice("look.focusMedia", LOOK, "Focused background: media rows", FocusBackground.choices, read = { look.mediaFocusBackground }, write = look::updateMediaFocusBackground)
        choice("look.focusCollections", LOOK, "Focused background: collections", FocusBackground.choices, read = { look.collectionFocusBackground }, write = look::updateCollectionFocusBackground)
        toggle("look.appFocusColors", LOOK, "App tile colours", read = { look.appFocusColors }, write = look::updateAppFocusColors)
        choice("look.cardFocus", LOOK, "Card focus effects", CardFocusStyle.choices, read = { look.cardFocusStyle }, write = look::updateCardFocusStyle)
        toggle("look.remoteSounds", LOOK, "Remote sounds", read = { look.remoteSounds }, write = look::updateRemoteSounds)
        choice("look.settingsKey", LOOK, "Remote Settings button opens", listOf("ember" to "Ember", "tv" to "TV or projector"), read = { look.remoteSettingsKey }, write = look::updateRemoteSettingsKey)
        out.config(serializer<app.ember.tv.data.LightHomeOptions>(), "look.light", LOOK, "Light Home: ", { look.lightHome }, look::updateLightHome, mapOf(
            "watchNext" to Field("Watch next"), "recommendations" to Field("Recommendations"), "motion" to Field("Motion"),
            "idleSeconds" to Field("Fade controls after", options = listOf("0" to "Never", "15" to "15 seconds", "30" to "30 seconds", "60" to "1 minute", "120" to "2 minutes"))))

        // ---- Home
        val HOME = "Home"
        toggle("home.hero", HOME, "Hero banner", read = { vm.heroOn }, write = vm::updateHeroOn)
        toggle("home.live", HOME, "Live TV", read = { vm.featureOn(Features.LIVE) }, write = { if (it != vm.featureOn(Features.LIVE)) vm.toggleFeature(Features.LIVE) })
        toggle("home.servers", HOME, "Media servers", read = { vm.featureOn(Features.SERVERS) }, write = { if (it != vm.featureOn(Features.SERVERS)) vm.toggleFeature(Features.SERVERS) })
        toggle("home.trackers", HOME, "Trackers", read = { vm.featureOn(Features.TRACKERS) }, write = { if (it != vm.featureOn(Features.TRACKERS)) vm.toggleFeature(Features.TRACKERS) })
        toggle("home.smartRows", HOME, "Extra Home rows", "Recommended, because you watched, hidden gems", read = { vm.featureOn(Features.SMART_ROWS) }, write = { if (it != vm.featureOn(Features.SMART_ROWS)) vm.toggleFeature(Features.SMART_ROWS) })
        toggle("home.cast", HOME, "Cast & crew", read = { vm.featureOn(Features.CAST) }, write = { if (it != vm.featureOn(Features.CAST)) vm.toggleFeature(Features.CAST) })
        toggle("home.watchedBadges", HOME, "Watched indicators", read = { look.showWatchedBadges }, write = look::updateShowWatchedBadges)
        toggle("home.spoilers", HOME, "Blur unwatched episodes", read = { look.blurUnwatchedEpisodes }, write = look::updateBlurUnwatchedEpisodes)
        toggle("home.animatedCollections", HOME, "Animated collections", read = { vm.collections.heroPreviews }, write = vm.collections::enableHeroPreviews)
        toggle("home.autoPage", HOME, "Load more as I scroll", read = { vm.collections.autoPage }, write = vm.collections::updateAutoPage)
        toggle("home.ai", HOME, "AI-assisted discovery", read = { look.aiDiscoveryEnabled }, write = look::updateAiDiscoveryEnabled)
        toggle("home.checklist", HOME, "Setup checklist", read = { !look.setupChecklistHidden }, write = { look.hideSetupChecklist(!it) })

        // ---- Playback
        val PLAY = "Playback"
        val players = listOf(Prefs.PLAYER_BUILTIN to "Built-in", Prefs.PLAYER_ASK to "Ask every time") + vm.playing.players.map { it.pkg to it.label }
        choice("play.player", PLAY, "Player", players, read = { vm.playing.playerPref }, write = vm.playing::updatePlayerPref)
        choice("play.interface", PLAY, "Player interface", PlayerInterfaces.options,
            read = { PlayerInterfaces.resolve(vm.playerSettings.playbackConfig, look.uiStyle) },
            write = { v -> vm.playerSettings.updatePlaybackConfig { it.copy(playerInterface = v) } })
        // #232: one control as on the TV (#210): Off, or the countdown before the next episode plays.
        choice("play.next", PLAY, "Play the next episode automatically", app.ember.tv.ui.NextEpisodeSteps.values.map { "$it" to app.ember.tv.ui.NextEpisodeSteps.label(it) },
            read = { app.ember.tv.ui.NextEpisodeSteps.of(vm.playing.autoplay, vm.playing.countdown).toString() },
            write = { v -> val secs = v.toInt()
                if (secs == 0) { if (vm.playing.autoplay) vm.playing.updateAutoplay(false) }
                else { if (!vm.playing.autoplay) vm.playing.updateAutoplay(true); vm.playing.updateCountdown(secs) } })
        toggle("play.combined", PLAY, "Combine sources from all addons", read = { vm.playerSettings.sourcesCombined }, write = vm.playerSettings::updateSourcesCombined)
        out.config(serializer<app.ember.tv.player.PlaybackConfig>(), "play.config", PLAY, "", { vm.playerSettings.playbackConfig }, vm.playerSettings::updatePlaybackConfig, mapOf(
            "passthrough" to Field("Passthrough (bitstream)", note = "Off decodes everything to PCM"),
            "passthroughMode" to Field("Passthrough formats", listOf("auto" to "As HDMI reports", "manual" to "Choose myself")),
            "ffmpeg" to Field("Software audio decoding", listOf("on" to "When needed", "prefer" to "Prefer software", "off" to "TV decoders only")),
            "ffmpegVideo" to Field("Software video decoding", listOf("fallback" to "When the TV can't", "always" to "Always", "off" to "Off")),
            "tunneling" to Field("Tunneled playback"),
            "frameRate" to Field("Match frame rate", listOf("always" to "Match frame rate", "seamless" to "Only if seamless", "off" to "Off")),
            "matchResolution" to Field("Match resolution too"),
            "buffer" to Field("Buffer", listOf("normal" to "Normal", "large" to "Large (4K remux)", "huge" to "Huge")),
            "audioPick" to Field("Sound track", listOf("quality" to "Best quality", "language" to "My language, then quality", "default" to "File's default")),
            "infoOnStart" to Field("Show playback info at start"),
            "dolbyVision" to Field("Dolby Vision", listOf("auto" to "Auto", "always" to "Always use it", "hdr10" to "Always HDR10")),
            "audioDelayMs" to Field("Sound timing (ms)", range = app.ember.tv.data.AudioTiming.MIN..app.ember.tv.data.AudioTiming.MAX, note = "Positive plays the sound later"),
            "skipIntro" to Field("Skip intro", SettingDefinitions.skipIntro.options),
            "introDb" to Field("IntroDB community timings"),
            "resume" to Field("Resume", SettingDefinitions.resume.options),
            "rememberTracks" to Field("Remember sound and subtitle choices"),
            "pip" to Field("Home: keep playing in a small window"),
            "confirmExit" to Field("Back: ask before leaving"),
            "quickReturn" to Field("Back to the last video from Home"),
            "returnKey" to Field("Back to the last video with", listOf("play" to "Play button", "back" to "Press Back twice")),
            "trailersInApp" to Field("Play trailers in Ember (experimental)"),
            "zoom" to Field("Picture", listOf("fit" to "Fit", "crop" to "Fill (crop)", "stretch" to "Stretch")),
            "hud" to Field("Playback stats", listOf("off" to "Off", "simple" to "Simple", "full" to "Full")),
            "engine" to Field("Playback engine", listOf("auto" to "Automatic", "standard" to "Standard", "compatibility" to "Compatibility")),
            "deband" to Field("Deband (compatibility engine)"), "interpolation" to Field("Motion interpolation (compatibility engine)"),
            "deinterlacing" to Field("Deinterlacing", listOf("off" to "Off", "auto" to "Automatic", "always" to "Always")),
            "scaler" to Field("Upscaling", listOf("bilinear" to "Bilinear", "bicubic" to "Bicubic", "lanczos" to "Lanczos")),
        ), skip = setOf("playerLook", "playerInterface"))
        out.config(serializer<app.ember.tv.data.PlayRules>(), "play.rules", PLAY, "Source rules: ", { vm.playerSettings.playRules }, vm.playerSettings::updatePlayRules, mapOf(
            "enabled" to Field("Pick sources automatically"), "maxQuality" to Field("Highest quality", listOf("4K" to "4K", "FHD" to "1080p", "HD" to "720p")),
            "hdr" to Field("HDR", listOf("dv" to "Dolby Vision first", "hdr" to "HDR10 first", "sdr" to "SDR first", "any" to "No preference")),
            "bestAudio" to Field("Best audio first"), "readyOnly" to Field("Cached (ready) sources only"),
            "maxSizeGb" to Field("Largest file", app.ember.tv.data.PlayRules.SIZES.map { "$it" to if (it == 0) "No limit" else "$it GB" }), "serverFirst" to Field("Media servers first")))

        // ---- Subtitles
        val SUBS = "Subtitles"
        toggle("subs.on", SUBS, "Subtitles on by default", read = { vm.playing.subtitlesOn }, write = vm.playing::updateSubtitlesOn)
        out.config(serializer<app.ember.tv.data.SubtitleConfig>(), "subs.config", SUBS, "", { vm.playerSettings.subtitleConfig }, vm.playerSettings::updateSubtitleConfig, mapOf(
            "autoSelect" to Field("Turn on at start", SettingDefinitions.subtitles.options),
            "stremioOpenSubs" to Field("OpenSubtitles (free, no key)"), "openSubsCom" to Field("OpenSubtitles.com"), "subdl" to Field("SubDL"),
            "addonSubs" to Field("Your Stremio addons"), "hashMatch" to Field("Match to the exact file"),
            "hearingImpaired" to Field("Hearing impaired (SDH)", listOf("avoid" to "Avoid", "include" to "No preference", "prefer" to "Prefer", "exclude" to "Never")),
            "skipMachine" to Field("Skip machine-translated"), "perLanguage" to Field("Results per language", range = 1..20),
            "size" to Field("Size", listOf("small" to "Small", "normal" to "Normal", "large" to "Large", "xlarge" to "Extra large")),
            "background" to Field("Background", listOf("outline" to "Outline", "shadow" to "Shadow", "box" to "Dark box")),
            "color" to Field("Colour", listOf("white" to "White", "yellow" to "Yellow")),
            "position" to Field("Position", listOf("low" to "Low", "normal" to "Normal", "high" to "Higher")),
            "authoredStyles" to Field("Preserve authored subtitle styles"), "systemStyle" to Field("Use system caption style")))

        // ---- Sources
        val SRC = "Sources"
        choice("src.timeout", SRC, "Addon time limit", listOf(5, 10, 15, 30).map { "$it" to "$it seconds" }, read = { vm.addonTimeout.toString() }, write = { vm.updateAddonTimeout(it.toInt()) })
        toggle("src.format", SRC, "Custom stream format", read = { vm.sourceFormat.streamFormatOn }, write = vm.sourceFormat::updateStreamFormatOn)
        choice("src.badges", SRC, "Source badges", listOf(app.ember.tv.data.Badges.LEGACY to "Legacy", app.ember.tv.data.Badges.MODERN to "Modern", app.ember.tv.data.Badges.CUSTOM to "Custom"),
            read = { vm.sourceFormat.badgeMode }, write = vm.sourceFormat::updateBadgeMode)
        toggle("src.ratings", SRC, "Show ratings", read = { vm.metadata.ratingsOn }, write = vm.metadata::updateRatingsOn)
        out.config(serializer<app.ember.tv.data.PosterConfig>(), "src.posters", SRC, "Posters: ", { vm.metadata.posterConfig }, vm.metadata::updatePosterConfig, mapOf(
            "provider" to Field("Poster source", listOf(app.ember.tv.data.PosterConfig.OFF to "Addon posters", app.ember.tv.data.PosterConfig.RPDB to "RPDB", app.ember.tv.data.PosterConfig.BETTER to "Better Posters")),
            "rpdbStyle" to Field("RPDB style (paid keys)", listOf("default" to "Default", "certs" to "Age ratings", "mc" to "Metacritic", "rt" to "Rotten Tomatoes")),
            "rpdbTextless" to Field("Textless artwork (RPDB)"),
            "bpGenre" to Field("Genre (Better Posters)"), "bpRating" to Field("Rating (Better Posters)"), "bpQuality" to Field("Quality (Better Posters)"),
            "bpAgeRating" to Field("Age rating (Better Posters)"), "bpTrending" to Field("Trending (Better Posters)"),
            "landscape" to Field("Landscape cards", listOf(app.ember.tv.data.PosterConfig.LANDSCAPE_TITLE to "Title art", app.ember.tv.data.PosterConfig.LANDSCAPE_PHOTO to "Photo", app.ember.tv.data.PosterConfig.LANDSCAPE_RPDB to "RPDB backdrop"))))
        out.config(serializer<app.ember.tv.data.MetaConfig>(), "src.meta", SRC, "Metadata: ", { vm.metadata.metaConfig }, vm.metadata::updateMetaConfig, mapOf(
            "anilistOn" to Field("Use AniList"), "anilistDescription" to Field("AniList description"), "anilistArtwork" to Field("AniList artwork"),
            "anilistGenres" to Field("AniList genres"), "anilistDetails" to Field("AniList details"),
            "anilistTitle" to Field("AniList title", listOf("addon" to "Addon's title", "english" to "English", "romaji" to "Romaji")),
            "tvdbOn" to Field("Use TVDB"), "tvdbDescription" to Field("TVDB description"), "tvdbArtwork" to Field("TVDB artwork"),
            "tvdbEpisodes" to Field("TVDB episodes"), "tvdbDetails" to Field("TVDB details"), "tvdbForAnime" to Field("TVDB for anime"),
            "tvdbLanguage" to Field("TVDB language", listOf("eng" to "English", "fra" to "French", "deu" to "German", "spa" to "Spanish", "ita" to "Italian", "por" to "Portuguese", "jpn" to "Japanese", "kor" to "Korean", "zho" to "Chinese")),
            "replace" to Field("Replace the addon's metadata")))

        // ---- System
        val SYS = "System"
        toggle("sys.updateAuto", SYS, "Check for updates automatically", read = { vm.updates.updateAutoCheck }, write = { if (it != vm.updates.updateAutoCheck) vm.updates.toggleUpdateAutoCheck() })
        toggle("sys.updateBeta", SYS, "Beta updates", read = { vm.updates.updateBeta }, write = { if (it != vm.updates.updateBeta) vm.updates.toggleUpdateBeta() })
        choice("sys.betaSource", SYS, "Beta source", listOf("releases" to "Releases", "actions" to "Actions APK"), read = { vm.updates.updateBetaSource }, write = { vm.updates.updateBetaSource(it) })
        toggle("sys.refresh", SYS, "Refresh Home in the background", read = { vm.backgroundRefresh }, write = { if (it != vm.backgroundRefresh) vm.toggleBackgroundRefresh() })
        toggle("sys.fahrenheit", SYS, "Fahrenheit", read = { vm.weather.fahrenheit }, write = vm.weather::updateFahrenheit)
        choice("sys.weather", SYS, "Weather provider", app.ember.tv.weather.WeatherProviders.choices, read = { vm.weather.provider }, write = vm.weather::updateProvider)
        toggle("sys.weatherFallback", SYS, "Weather fallback provider", read = { vm.weather.fallback }, write = vm.weather::updateFallback)

        // ---- Catalogs: Home, Discover and Live TV per addon catalog (Addons & content tab)
        vm.catalogs.allCatalogs.forEach { c ->
            val key = app.ember.tv.data.AddonAccess.key(c.key)
            val name = c.label
            val from = c.addon.name
            if (c.homeable) toggle("${CATALOGS_PREFIX}$key.home", CATALOGS, "$name: Home", from, read = { vm.isOnHome(c) }, write = { if (it != vm.isOnHome(c)) vm.toggleCatalogHome(c) })
            if (c.browsable) toggle("${CATALOGS_PREFIX}$key.discover", CATALOGS, "$name: Discover", from, read = { vm.isInDiscover(c) }, write = { if (it != vm.isInDiscover(c)) vm.toggleCatalogDiscover(c) })
            toggle("${CATALOGS_PREFIX}$key.live", CATALOGS, "$name: Live TV", from, read = { vm.isOnLive(c) }, write = { if (it != vm.isOnLive(c)) vm.toggleCatalogLive(c) })
        }
        return out
    }

    /** One setting per primitive field of a config object; string fields need their options listed. */
    private fun <T> MutableList<Def>.config(
        type: KSerializer<T>, prefix: String, group: String, labelPrefix: String, current: () -> T,
        update: ((T) -> T) -> Unit, fields: Map<String, Field>, skip: Set<String> = emptySet(),
    ) {
        val fieldsNow = json.encodeToJsonElement(type, current()).jsonObject
        for ((name, element) in fieldsNow) {
            if (name in skip || element is JsonNull) continue
            val p = element as? JsonPrimitive ?: continue
            val spec = fields[name]
            val numeric = !p.isString && p.booleanOrNull == null && p.intOrNull != null
            val kind = when {
                !p.isString && p.booleanOrNull != null -> PhoneSettingKind.TOGGLE
                spec?.options?.isNotEmpty() == true -> PhoneSettingKind.CHOICE
                numeric && spec?.range != null -> PhoneSettingKind.NUMBER
                else -> continue // free text, lists and unbounded numbers stay on the TV
            }
            val read: () -> String = { (json.encodeToJsonElement(type, current()).jsonObject[name] as? JsonPrimitive)?.content.orEmpty() }
            add(Def("$prefix.$name", group, labelPrefix + (spec?.label ?: humanize(name)), spec?.note, kind, spec?.options.orEmpty(), spec?.range ?: 0..0,
                read = read,
                write = { v ->
                    update { old ->
                        val o = json.encodeToJsonElement(type, old).jsonObject
                        val next = when {
                            kind == PhoneSettingKind.TOGGLE -> JsonPrimitive(v == "true")
                            numeric -> JsonPrimitive(v.toInt())
                            else -> JsonPrimitive(v)
                        }
                        json.decodeFromJsonElement(type, JsonObject(o + (name to next)))
                    }
                }))
        }
    }

    companion object {
        const val CATALOGS = "Catalogs"
        const val CATALOGS_PREFIX = "catalog."
        /** Settings tab groups, in TV order; Catalogs show on the Addons & content tab. */
        val SETTINGS_GROUPS = listOf("Look", "Home", "Playback", "Subtitles", "Sources", "System")
        internal fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: "Addon"
        internal fun humanize(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }
    }
}

/** Where each setting lives on the phone and tablet page, and how long lists are folded (#165). */
internal object PhonePages {
    private val interfaceIds = setOf("look.interface", "look.navbar", "look.mediaPageLayout", "look.theme", "look.oled", "look.accent", "look.menu")
    private val sizeIds = setOf("look.size", "look.text", "look.margins", "look.density")
    private val remoteIds = setOf("look.remoteSounds", "look.settingsKey")
    private val effectPrefixes = listOf("look.performance", "look.quality", "look.custom", "look.glass", "look.focus", "look.appFocusColors", "look.cardFocus")

    /** The section and sub-page a setting is shown on. Catalog switches have their own page under Addons. */
    fun place(s: PhoneSettingView): Pair<PhoneSetup.Tab, String> = when (s.group) {
        PhoneSettings.CATALOGS -> PhoneSetup.Tab.ADDONS to "catalogs"
        "Look" -> when {
            s.id in interfaceIds -> PhoneSetup.Tab.UI to "interface"
            s.id in sizeIds -> PhoneSetup.Tab.UI to "size"
            s.id in remoteIds -> PhoneSetup.Tab.SETTINGS to "system"
            effectPrefixes.any { s.id.startsWith(it) } -> PhoneSetup.Tab.UI to "effects"
            else -> PhoneSetup.Tab.UI to "tiles"
        }
        "Playback" -> PhoneSetup.Tab.PLAYER to "playback"
        "Subtitles" -> PhoneSetup.Tab.PLAYER to "subtitles"
        "Home" -> PhoneSetup.Tab.SETTINGS to "home"
        "Sources" -> PhoneSetup.Tab.SETTINGS to "sources"
        else -> PhoneSetup.Tab.SETTINGS to "system"
    }

    /**
     * Rows that share a "Name: " label prefix with at least two others become one group; the rest
     * stay as they are. Both keep their order.
     */
    fun fold(rows: List<PhoneSettingView>, least: Int = 3): Pair<List<PhoneSettingView>, List<Pair<String, List<PhoneSettingView>>>> {
        fun prefix(s: PhoneSettingView) = s.label.substringBefore(": ", "").trim()
        val grouped = rows.filter { prefix(it).isNotEmpty() }.groupBy(::prefix).filterValues { it.size >= least }
        return rows.filter { prefix(it) !in grouped.keys } to grouped.map { it.key to it.value }
    }
}
