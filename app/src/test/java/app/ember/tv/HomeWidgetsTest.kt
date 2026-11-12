package app.ember.tv

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.graphics.luminance
import app.ember.tv.data.*
import app.ember.tv.discover.*
import app.ember.tv.phone.*
import app.ember.tv.state.WidgetsState
import app.ember.tv.ui.WidgetLook
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Home widgets for the Ember interface (#161). */
class HomeWidgetsTest {
    private val trakt = CollectionSource(provider = "trakt", title = "Top", traktListId = 42, mediaType = "MOVIE")
    private fun widget(id: String = "w1", title: String = "Top 10", style: String = HomeWidgets.NUMBERED, source: CollectionSource = trakt) =
        HomeWidget(id, title, style, "poster", source, 10)

    @Test fun widgetsSurviveSavingAndUnknownValuesFallBack() {
        val filters = CollectionDrafts.discover("Comedies", ExploreQuery(kind = ExploreKind.MOVIE, genres = setOf("comedy"), fromYear = 2020, minRating = 7.0, sort = ExploreSort.RATING), "main")
        val list = listOf(widget(), widget("w2", "Comedies", HomeWidgets.CAROUSEL, filters), widget("w3", "Later", HomeWidgets.ROW, CollectionSource(provider = HomeWidgets.LIST, catalogId = "watchlist")))
        assertEquals(list, HomeWidgets.decode(HomeWidgets.encode(list)))
        val odd = HomeWidgets.normalize(HomeWidget("x", "  Odd  ", "hologram", "round", trakt, 900))
        assertEquals("Odd", odd.title); assertEquals(HomeWidgets.ROW, odd.style); assertEquals("default", odd.shape); assertEquals(HomeWidgets.LIMITS.last, odd.limit)
        assertEquals(emptyList<HomeWidget>(), HomeWidgets.decode("not json")); assertEquals(emptyList<HomeWidget>(), HomeWidgets.decode(null))
        // A widget that can't work is dropped on load rather than drawn empty forever.
        assertEquals(listOf("w1"), HomeWidgets.decode(HomeWidgets.encode(listOf(widget(), widget("bad", "No list", source = CollectionSource(provider = "trakt"))))).map { it.id })
        assertEquals("widget:w1", HomeWidgets.section("w1")); assertTrue(HomeWidgets.isSection("widget:w1")); assertFalse(HomeWidgets.isSection("explore:w1"))
    }

    @Test fun eachSourceKindIsCheckedAndDescribed() {
        assertNull(HomeWidgets.error(widget()))
        assertNotNull(HomeWidgets.error(widget(title = " ")))
        assertNotNull(HomeWidgets.error(widget(source = CollectionSource(provider = "tmdb", tmdbSourceType = "LIST"))))
        assertNotNull(HomeWidgets.error(widget(source = CollectionSource(provider = HomeWidgets.LIST, catalogId = "nope"))))
        assertNotNull(HomeWidgets.error(widget(source = CollectionSource(provider = "addon"))))
        assertNotNull("years are checked", HomeWidgets.error(widget(source = CollectionDrafts.discover("x", ExploreQuery(fromYear = 1200), "main"))))
        // Private catalog keys stay in Collections, which encrypt them.
        val secret = CollectionSource(addonId = "a", type = "movie", catalogId = "c", extraArgs = mapOf("token" to "fixture-private"))
        assertTrue(CollectionSecrets.sensitive("token")); assertTrue(HomeWidgets.error(widget(source = secret))!!.contains("collection"))
        assertNull(HomeWidgets.error(widget(source = secret.copy(extraArgs = mapOf("genre" to "Action")))))

        assertEquals("Trakt list 42", HomeWidgets.sourceLabel(trakt, emptyList()))
        assertEquals("TMDB list 7", HomeWidgets.sourceLabel(CollectionSource(provider = "tmdb", tmdbId = 7), emptyList()))
        assertEquals("TMDB company 3", HomeWidgets.sourceLabel(CollectionSource(provider = "tmdb", tmdbSourceType = "COMPANY", tmdbId = 3), emptyList()))
        assertEquals("In progress", HomeWidgets.sourceLabel(CollectionSource(provider = HomeWidgets.LIST, catalogId = "continue"), emptyList()))
        val q = ExploreQuery(kind = ExploreKind.MOVIE, genres = setOf("comedy"), fromYear = 2020, toYear = 2026, minRating = 7.0, sort = ExploreSort.RATING)
        assertEquals("Movies · Comedy · 2020–2026 · 7+ · Highest rated", HomeWidgets.summary(q))
        assertEquals("Filters: " + HomeWidgets.summary(q), HomeWidgets.sourceLabel(CollectionDrafts.discover("x", q, "main"), emptyList()))
    }

    @Test fun aWidgetDrawsInItsOwnStyleAndBuiltInRowsFollowHome() {
        assertEquals(WidgetLook(wide = true, numbered = false), WidgetLook.of(null, homeWide = true))
        assertEquals(WidgetLook(wide = false, numbered = false), WidgetLook.of(null, homeWide = false))
        assertEquals(WidgetLook(wide = false, numbered = true), WidgetLook.of(widget(), homeWide = true))
        assertEquals(WidgetLook(wide = true, numbered = false), WidgetLook.of(widget(style = HomeWidgets.ROW).copy(shape = "default"), homeWide = true))
        // #167: the cinematic carousel is one giant panel, a title at a time, not a row of cards.
        // #198: bigger; nearly the whole screen under its title.
        assertEquals(1f, WidgetLook.CAROUSEL_HEIGHT, 0f)
        assertEquals(1, WidgetLook.carouselStep(0, 3, forward = true)); assertEquals(1, WidgetLook.carouselStep(2, 3, forward = false))
        assertNull("left on the first title leaves the carousel", WidgetLook.carouselStep(0, 3, forward = false))
        assertNull("right on the last title leaves the carousel", WidgetLook.carouselStep(2, 3, forward = true))
        assertNull(WidgetLook.carouselStep(0, 1, forward = true))
        val home = java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText()
        // #201: the whole page, with its name on the picture instead of a heading above it.
        assertTrue(home.contains("b.widget?.style == app.ember.tv.data.HomeWidgets.CAROUSEL) {"))
        assertTrue(home.contains("title = b.row.displayTitle, bleed = (if(isEmberX)EmberXLayout.Gutter else TvTokens.Space.Page).dp)"))
        assertTrue(home.contains("Modifier.bleed(bleed).fillMaxWidth().height(height)"))
        assertTrue(home.contains("WidgetCarousel(b.key, b.row.items, maxHeight * WidgetLook.CAROUSEL_HEIGHT"))
        assertFalse("no row of large cards any more", home.contains("carouselWidth"))
    }

    @Test fun theEditorsShowStylesAsPicturesAndTheTvWorksInSteps() {
        // #172: every style has a line saying what it is, on the TV tiles and the phone's.
        for ((style, _) in HomeWidgets.styles) assertTrue(style, HomeWidgets.about(style).length in 20..70)
        assertEquals(HomeWidgets.styles.size, HomeWidgets.styles.map { HomeWidgets.about(it.first) }.toSet().size)
        val tv = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertTrue(tv.contains("WidgetStyleTile(value, name, draft.style == value)")); assertTrue(tv.contains("HomeWidgets.about(style)"))
        val phone = java.io.File("src/main/java/app/ember/tv/phone/ContentPhone.kt").readText()
        assertTrue(phone.contains("shapes styles")); assertTrue(phone.contains("HomeWidgets.about(value)"))
        val css = java.io.File("src/main/java/app/ember/tv/phone/PhoneSetup.kt").readText()
        assertTrue("a chosen style keeps its picture", css.contains(".shapes input:checked+span i:not([class^=w-])"))
        // #173: Style, Source, Details. New widgets start with the look; edits open on the details.
        assertEquals(listOf("Style", "Source", "Details"), app.ember.tv.ui.WidgetSteps.all.map { it.second })
        assertEquals(app.ember.tv.ui.WidgetSteps.STYLE, app.ember.tv.ui.WidgetSteps.first(existing = false))
        assertEquals(app.ember.tv.ui.WidgetSteps.DETAILS, app.ember.tv.ui.WidgetSteps.first(existing = true))
        assertEquals(app.ember.tv.ui.WidgetSteps.SOURCE, app.ember.tv.ui.WidgetSteps.next(app.ember.tv.ui.WidgetSteps.STYLE))
        assertNull(app.ember.tv.ui.WidgetSteps.next(app.ember.tv.ui.WidgetSteps.DETAILS))
        assertEquals(3, app.ember.tv.ui.WidgetSteps.all.map { app.ember.tv.ui.WidgetSteps.heading(it.first) }.toSet().size)
    }

    @Test fun theCarouselSlidesTheWayYouGoAndNumberedRowsMakeRoomForTheRank() {
        // #174
        assertTrue(WidgetLook.carouselForward(0, 1, 5)); assertFalse(WidgetLook.carouselForward(3, 2, 5))
        assertTrue("turning from the last title to the first goes forward", WidgetLook.carouselForward(4, 0, 5))
        assertFalse(WidgetLook.carouselForward(0, 4, 5))
        assertTrue(WidgetLook.carouselForward(0, 1, 2)); assertFalse(WidgetLook.carouselForward(1, 0, 2))
        assertEquals("Movie  ·  2024  ·  Drama, Crime", WidgetLook.carouselLine(Meta("tt1", "movie", "Film", releaseInfo = "2024", genres = listOf("Drama", "Crime", "Mystery"))))
        assertEquals("", WidgetLook.carouselLine(Meta("tt1", "", "Film")))
        val home = java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText()
        assertTrue(home.contains("HeroTitle(shown, centered = true)")); assertFalse("no slivers of the neighbours on a full page", home.contains("CarouselPeek"))
        // #175: "10" needs twice the room of "9"; part of the numeral always sits behind the card.
        // #202: the rank is on the picture's corner, sized to the card and never tiny or huge.
        assertTrue(home.contains("if (numbered) RankCorner(index + 1, w, if (wide) w * 9f / 16f else w * m.cardRatio)"))
        for (width in listOf(60f, 130f, 220f, 400f)) for (wide in listOf(true, false)) assertTrue(app.ember.tv.ui.RankNumerals.size(width, wide) in 26f..52f)
        assertTrue(app.ember.tv.ui.RankNumerals.size(130f, false) > app.ember.tv.ui.RankNumerals.size(100f, false))
        assertFalse(home.contains("RankBadge"))
    }

    @Test fun homeActionsFiltersPreviewAndPlaceholders() {
        // #183: the title's own options lead the widget row's menu; long names are cut.
        assertEquals("Options for Heat", app.ember.tv.ui.WidgetDrafts.titleOption(" Heat "))
        assertEquals("Options for this title", app.ember.tv.ui.WidgetDrafts.titleOption(""))
        assertTrue(app.ember.tv.ui.WidgetDrafts.titleOption("The Lord of the Rings: The Fellowship of the Ring").length <= 40)
        val home = java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText()
        assertTrue(home.contains("is HomeViewModel.HomeBlock.Row -> WidgetActions(vm, b.widget) {"))
        assertTrue(home.contains("add(MenuOption(\"Edit widget\") { vm.editWidget(widget.id) })"))
        // #181: filters built in the editor become a source owned by the profile, named after what they ask for.
        val query = app.ember.tv.discover.ExploreQuery(kind = app.ember.tv.discover.ExploreKind.MOVIE, genres = setOf("crime"), fromYear = 1990, toYear = 1999)
        val built = app.ember.tv.ui.WidgetDrafts.filters(query, "me")
        assertEquals("ember", built.provider); assertEquals(query, built.exploreQuery); assertEquals("me", built.ownerProfile)
        assertTrue(built.title.orEmpty().contains("Crime")); assertTrue(built.title.orEmpty().contains("1990"))
        val tv = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertTrue(tv.contains("else if (filters) ExploreFilters(")); assertFalse(tv.contains("save a search in Discover, then pick it"))
        // #182
        assertEquals(1, app.ember.tv.ui.WidgetDrafts.previewCount(HomeWidgets.CAROUSEL, wide = false)); assertEquals(1, app.ember.tv.ui.WidgetDrafts.previewCount(HomeWidgets.HERO, true))
        assertTrue(app.ember.tv.ui.WidgetDrafts.previewCount(HomeWidgets.ROW, false) > app.ember.tv.ui.WidgetDrafts.previewCount(HomeWidgets.ROW, true))
        assertTrue(tv.contains("WidgetPreview(vm, draft)"))
        // #184: a loading banner or carousel holds its own height; rows pulse.
        assertTrue(home.contains("app.ember.tv.data.HomeWidgets.CAROUSEL -> LoadingPanel(maxHeight * WidgetLook.CAROUSEL_HEIGHT)"))
        val loading = java.io.File("src/main/java/app/ember/tv/ui/RecoveryUi.kt").readText()
        assertTrue(loading.contains("if (LocalReduceMotion.current || LocalRenderOptions.current.lowPower) return remember"))
        // #187
        assertTrue(home.contains("label = \"widgetHeroArt\", transitionSpec"))
        // #185
        assertEquals(listOf("Look", "Sources", "Artwork"), app.ember.tv.ui.FolderSteps.all.map { it.second })
        for ((shape, _) in app.ember.tv.ui.FolderSteps.shapes) { val (w, h) = app.ember.tv.ui.FolderSteps.size(shape); assertTrue(shape, w in 30..90 && h in 30..56) }
        assertTrue(app.ember.tv.ui.FolderSteps.size("LANDSCAPE").let { it.first > it.second }); assertTrue(app.ember.tv.ui.FolderSteps.size("POSTER").let { it.first < it.second })
    }

    @Test fun thePhoneReordersWidgetsInOneApproval() {
        // #186: only the sections named move, into the places they hold between them.
        val layout = listOf("hero" to true, "widget:a" to true, "apps" to true, "widget:b" to false, "widget:c" to true)
        assertEquals(listOf("hero" to true, "widget:c" to true, "apps" to true, "widget:a" to true, "widget:b" to false),
            app.ember.tv.state.HomeSectionsState.arranged(layout, listOf("widget:c", "widget:a", "widget:b")))
        assertEquals(layout, app.ember.tv.state.HomeSectionsState.arranged(layout, listOf("widget:c", "widget:c")))
        assertEquals(layout, app.ember.tv.state.HomeSectionsState.arranged(layout, listOf("widget:c", "widget:gone")))
        assertEquals(layout, app.ember.tv.state.HomeSectionsState.arranged(layout, emptyList()))
        val two = PhoneContent(emptyList(), emptyList(), emptyList(), widgets = listOf(
            PhoneWidget("w-1", "Top", HomeWidgets.NUMBERED, "poster", 10, "Trakt list 42", "10 titles", canMoveUp = false),
            PhoneWidget("w-2", "Comedies", HomeWidgets.CAROUSEL, "default", 20, "Filters: Movies", "Loading", canMoveDown = false)))
        val page = ContentPhone.render(two, "lease")
        assertTrue(page.contains("<div id=worder><div class=row data-id=\"w-1\">")); assertTrue(page.contains("<span class=\"thumb wt\"><i class=\"w-numbered\"></i></span>"))
        assertTrue(page.contains("<form id=wsend class=flat method=post action=\"/content?k=lease\" hidden><input type=hidden name=action value=order-widgets>"))
        assertTrue(page.contains(ContentPhone.REORDER)); assertFalse("no secrets or links in the script", ContentPhone.REORDER.contains("http"))
        assertEquals(2, Regex("<noscript>").findAll(page).count())
        assertEquals("widgets", ContentPhone.partOf("order-widgets"))
        // #191: the page's script puts the class "js" on the whole document, so a rule hiding ".js" blanks every page.
        val sheet = java.io.File("src/main/java/app/ember/tv/phone/PhoneSetup.kt").readText()
        assertTrue(sheet.contains("document.documentElement.classList.add('js')"))
        assertFalse(Regex("(^|[},\\s])\\.js\\{[^}]*display:none").containsMatchIn(sheet))
        assertTrue(sheet.contains(".wj{display:none}#worder.live .wj{display:grid}")); assertTrue(page.contains("class=\"ib grip wj\""))
        val one = ContentPhone.render(two.copy(widgets = two.widgets.take(1)), "lease")
        assertFalse("one widget has nothing to reorder", one.contains("id=wsend"))
        val controller = java.io.File("src/main/java/app/ember/tv/phone/PhoneSetupController.kt").readText()
        assertTrue(controller.contains("\"order-widgets\" -> {")); assertTrue(controller.contains("sections.arrange(ordered.map"))
    }

    @Test fun everythingOnHomeIsAWidgetAndAppsLiveWithApps() {
        // #189: one list for the widgets that come with Ember and your own; only your own can be edited or deleted.
        val ui = app.ember.tv.ui.WidgetDrafts
        assertEquals(listOf("Edit", "Hide from Home", "Delete"), ui.actions(own = true, editable = false, on = true))
        // #195: a built-in widget is never deleted, but is edited where a setting applies to it.
        assertEquals(listOf("Edit", "Show on Home"), ui.actions(own = false, editable = true, on = false))
        assertEquals(listOf("Hide from Home"), ui.actions(own = false, editable = false, on = true))
        for (section in listOf("hero", "apps", "continue", "recent", "watchlist", "upnext", "servers", "smart:recommended")) assertTrue(section, ui.editable(section))
        assertFalse(ui.editable("pinned-shortcuts")); assertFalse("your own widgets have their own editor", ui.editable("widget:w1"))
        assertEquals("watchlist", ui.personalList("watchlist")); assertEquals("continue", ui.personalList("continue")); assertNull(ui.personalList("hero"))
        assertEquals(HomeWidgets.HERO, ui.picture(app.ember.tv.HomeViewModel.HOME_HERO, null)); assertEquals(ui.APPS, ui.picture(app.ember.tv.HomeViewModel.HOME_APPS, null))
        assertEquals(HomeWidgets.ROW, ui.picture("continue", null)); assertEquals(HomeWidgets.CAROUSEL, ui.picture("widget:w1", HomeWidgets.CAROUSEL))
        val tv = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertTrue(tv.contains("Section(\"Home widgets\"")); assertTrue(tv.contains("vm.sections.layout.forEach { (section, on) ->"))
        assertTrue(tv.contains("vm.sections.resetLayout()"))
        val general = java.io.File("src/main/java/app/ember/tv/ui/SettingsGeneral.kt").readText()
        assertFalse("the order is no longer kept in two places", general.contains("vm.sections.moveSection"))
        assertFalse(general.contains("vm.toggleHidden"))
        assertEquals("Banner & lists", app.ember.tv.ui.SettingsTab.HOME_ROWS.label)
        val index = app.ember.tv.ui.SETTINGS_INDEX.single { it.first == app.ember.tv.ui.SettingsTab.WIDGETS }.second.lowercase()
        for (word in listOf("home layout", "order", "banner", "your apps")) assertTrue(word, word in index)
        // #190: Apps on Home and the Apps page are on Apps & folders, each choosing its apps in a dialog.
        val apps = java.io.File("src/main/java/app/ember/tv/ui/HomeToolsSettings.kt").readText()
        assertTrue(apps.contains("Section(\"Apps on Home\"")); assertTrue(apps.contains("Section(\"Apps page\""))
        assertTrue(apps.contains("vm.sections.toggleSection(HomeViewModel.HOME_APPS)")); assertTrue(apps.contains("vm.updateHomeAppRows(2)"))
        assertEquals("All 12 apps", app.ember.tv.ui.AppChoices.count(12, 12)); assertEquals("5 of 12 apps", app.ember.tv.ui.AppChoices.count(5, 12)); assertEquals("No apps", app.ember.tv.ui.AppChoices.count(0, 0))
        assertEquals("Netflix, hidden", app.ember.tv.ui.AppChoices.spoken("Netflix", false))
        assertTrue("app_icons" in app.ember.tv.ui.lookKeys(app.ember.tv.ui.SettingsTab.HOME_TOOLS, InterfaceStyles.MODERN))
        assertFalse("app_icons" in app.ember.tv.ui.lookKeys(app.ember.tv.ui.SettingsTab.HOME_ROWS, InterfaceStyles.MODERN))
        val appsIndex = app.ember.tv.ui.SETTINGS_INDEX.single { it.first == app.ember.tv.ui.SettingsTab.HOME_TOOLS }.second.lowercase()
        for (word in listOf("apps on home", "rows of apps", "app icons")) assertTrue(word, word in appsIndex)
        // #188: no line across a marked button; the eye fills instead.
        val actions = java.io.File("src/main/java/app/ember/tv/ui/DetailsActions.kt").readText()
        assertFalse(actions.contains("selectionCue")); assertTrue(actions.contains("if(watched)Icons.Filled.Visibility else Icons.Outlined.Visibility"))
    }

    @Test fun singleBadgesSwitchOffWithoutComingBackAsText() {
        // #194
        val hdr = BadgeFilter(id = "hdr10", name = "HDR10", groupId = "gv"); val atmos = BadgeFilter(name = "Atmos", groupId = "ga")
        assertEquals("modern|hdr10", BadgeChoices.key(Badges.MODERN, hdr)); assertEquals("custom|Atmos", BadgeChoices.key(Badges.CUSTOM, atmos))
        val off = BadgeChoices.toggle(emptySet(), BadgeChoices.key(Badges.MODERN, hdr))
        val marked = BadgeChoices.mark(Badges.MODERN, off, listOf(hdr, atmos))
        assertEquals(listOf(false, true), marked.map { it.isEnabled })
        assertEquals("a custom pack keeps its own choices", listOf(true, true), BadgeChoices.mark(Badges.CUSTOM, off, listOf(hdr, atmos)).map { it.isEnabled })
        assertTrue(BadgeChoices.toggle(off, "modern|hdr10").isEmpty())
        assertEquals(1, BadgeChoices.count(Badges.MODERN, off + "custom|x")); assertEquals(setOf("custom|x"), BadgeChoices.clear(Badges.MODERN, off + "custom|x"))
        // The badge that is off still covers its category: no plain "HDR10" tag in its place.
        val source = StreamOption("Addon", "Source", "Film", "https://example.test/a", null, null, "", "HDR10", "", "", "", "", null, null, true)
        assertFalse(DetailedBadgeCategories.textTags(source, emptySet(), badges = marked).contains("HDR10"))
        assertTrue(DetailedBadgeCategories.textTags(source, emptySet(), badges = emptyList()).contains("HDR10"))
        val rows = java.io.File("src/main/java/app/ember/tv/ui/DetailsScreen.kt").readText()
        assertEquals(2, Regex("allBadges\\.filter *\\{ *it\\.isEnabled *\\}").findAll(rows).count())
        assertEquals("set", PreferenceKeys.expectedType("badgesOff")); assertTrue(PreferenceKeys.BADGES_OFF.portable)
    }

    @Test fun catalogsAreListedUnderTheirAddonByType() {
        // #193
        fun addon(url: String, name: String) = AddonInfo(url, url, name, emptyList(), null, emptyList(), emptyList())
        fun catalog(a: AddonInfo, type: String, id: String) = CatalogDef(a, type, id, id, emptyList(), false, false, false, false)
        val one = addon("https://one.test/manifest.json", "One"); val two = addon("https://two.test/manifest.json", "Two")
        val list = listOf(catalog(one, "movie", "top"), catalog(two, "series", "new"), catalog(one, "series", "hot"), catalog(one, "movie", "old"))
        val browse = app.ember.tv.ui.CatalogBrowse
        val home = setOf("top", "new")
        val all = browse.groups(list, browse.ALL, browse.ALL, { it.id in home }, { false }, { it.id == "hot" })
        assertEquals(listOf("One", "Two"), all.map { it.name })
        assertEquals(listOf("movie" to listOf(0, 3), "series" to listOf(2)), all[0].byType.map { (t, rows) -> t to rows.map { it.index } })
        assertEquals("3 catalogs · 1 on Home · 1 in Discover", all[0].line()); assertEquals("1 catalog · 1 on Home", all[1].line())
        val onHome = browse.groups(list, browse.HOME, browse.ALL, { it.id in home }, { false }, { it.id == "hot" })
        assertEquals(listOf(1, 1), onHome.map { it.shown }); assertEquals("1 of 3 catalogs · 1 on Home · 1 in Discover", onHome[0].line())
        val unused = browse.groups(list, browse.OFF, browse.ALL, { it.id in home }, { false }, { it.id == "hot" })
        assertEquals(listOf("old"), unused.single().byType.single().second.map { it.catalog.id })
        assertEquals(listOf("Two"), browse.groups(list, browse.ALL, "series", { false }, { false }, { false }).filter { it.id == two.url }.map { it.name })
        assertEquals(listOf("movie", "series"), browse.types(list)); assertEquals("Movies", browse.typeName("movie")); assertEquals("Live TV", browse.typeName("tv")); assertEquals("Anime", browse.typeName("anime"))
        assertTrue("a short list is simply open", browse.isOpen("x", browse.SMALL)); assertFalse(browse.isOpen("never-opened-addon", 200))
        assertEquals("4 catalogs from 2 addons", browse.summary(4, 4, 2)); assertEquals("1 of 4 catalogs from 1 addon", browse.summary(1, 4, 1))
    }

    @Test fun rowsChooseTheirShapeAndPanelsTheirArtwork() {
        // #195: a built-in row's own shape wins over the default; your widgets keep their own setting.
        assertEquals(WidgetLook(wide = false, numbered = false), WidgetLook.of(null, homeWide = true, shape = "poster"))
        assertEquals(WidgetLook(wide = true, numbered = false), WidgetLook.of(null, homeWide = false, shape = "wide"))
        assertEquals(WidgetLook(wide = true, numbered = false), WidgetLook.of(null, homeWide = true, shape = null))
        val sections = app.ember.tv.state.HomeSectionsState
        for (id in listOf("continue", "recent", "watchlist", "upnext", "servers", "smart:x", "explore:x")) assertTrue(id, sections.hasShape(id))
        for (id in listOf("hero", "apps", "widget:w1", "pinned-shortcuts")) assertFalse(id, sections.hasShape(id))
        val prefs = memoryPrefs()
        prefs.homeRowShapes = mapOf("continue" to "poster", "catalog:https://a.test/manifest.json:movie:top" to "wide")
        assertEquals(mapOf("continue" to "poster", "catalog:https://a.test/manifest.json:movie:top" to "wide"), prefs.homeRowShapes)
        prefs.homeRowShapes = emptyMap(); assertTrue(prefs.homeRowShapes.isEmpty())
        assertEquals("s", PreferenceKeys.expectedType("homeRowShapes"))
        val model = java.io.File("src/main/java/app/ember/tv/HomeViewModel.kt").readText()
        assertTrue(model.contains("sections.shape(id)?.let { shape -> for (i in before until size)"))
        // #196: banner and carousel widgets carry their own artwork choice; anything else reads as Standard.
        assertEquals(HeroArtworkMode.STANDARD, HomeWidgets.normalize(widget().copy(art = "nonsense")).art)
        assertEquals(HeroArtworkMode.FANART, HomeWidgets.normalize(widget().copy(art = HeroArtworkMode.FANART)).art)
        assertEquals(HeroArtworkMode.FANART, HomeWidgets.decode(HomeWidgets.encode(listOf(widget().copy(art = HeroArtworkMode.FANART)))).single().art)
        assertEquals("a widget saved before this keeps Standard", HeroArtworkMode.STANDARD, widget().art)
        assertTrue(HomeWidgets.isPanel(HomeWidgets.HERO)); assertTrue(HomeWidgets.isPanel(HomeWidgets.CAROUSEL)); assertFalse(HomeWidgets.isPanel(HomeWidgets.NUMBERED))
        val home = java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText()
        assertTrue(home.contains("LocalHeroArtworkMode provides app.ember.tv.data.HeroArtworkMode.normalize(widget.art)"))
        assertEquals(2, Regex("WidgetArt\\((shown|items\\.getOrNull\\(at\\) \\?: meta), Modifier\\.fillMaxSize\\(\\)\\)").findAll(home).count())
        val phone = ContentPhone.widgetEditor(PhoneContent(emptyList(), emptyList(), emptyList()), "lease", "new")
        assertTrue(phone.contains("<select id=art name=art>")); assertTrue(phone.contains("<option value=fanart>Fanart</option>"))
        // #197: the banner and the lists are edited on their widgets; the old page folds into Widgets & collections.
        assertEquals(app.ember.tv.ui.SettingsTab.WIDGETS, app.ember.tv.ui.SettingsTab.HOME_ROWS.host)
        assertFalse(java.io.File("src/main/java/app/ember/tv/ui/HeroBannerSettings.kt").exists())
        val tv = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertTrue(tv.contains("CatalogSourcePicker(vm, \"Banner catalog\", vm.look.heroCatalog, vm.look::updateHeroCatalog)"))
        assertTrue(tv.contains("vm.sections.toggleSource(vm.profiles.profileId, list, source)"))
        // #199
        assertTrue(tv.contains("SwitchChip(\"On Home\", on, modifier"))
    }

    @Test fun settingsShowPicturesAndDialogsUseTheScreen() {
        // #203: every option of the picture settings has a diagram of its own.
        val pictures = app.ember.tv.ui.SettingPictures
        val groups = mapOf("ui" to InterfaceStyles.options.map { it.first } + InterfaceStyles.EMBER_X, "nav" to NavbarStyles.options.map { it.first },
            "page" to MediaPageLayouts.options.map { it.first }, "theme" to listOf("night", "day", "auto"), "menu" to listOf("top", "side"),
            "apps" to listOf("banner", "circle"), "density" to app.ember.tv.ui.SettingDefinitions.density.options.map { it.first })
        for ((group, options) in groups) for (option in options) assertTrue("$group-$option", pictures.of(group, option) in pictures.known)
        val drawn = java.io.File("src/main/java/app/ember/tv/ui/SettingPictures.kt").readText()
        // Size pictures (#215) are drawn from the size's own scale, not named one by one.
        for (picture in pictures.known.filterNot { it.startsWith("uisize-") || it.startsWith("textsize-") }) assertTrue(picture, drawn.contains("\"$picture\""))
        assertTrue(pictures.density("comfortable").first < pictures.density("standard").first && pictures.density("standard").first < pictures.density("dense").first)
        assertTrue(pictures.density("comfortable").second > pictures.density("dense").second)
        val general = java.io.File("src/main/java/app/ember/tv/ui/SettingsGeneral.kt").readText()
        assertEquals(7, Regex("PictureChoice\\(\"(ui|page|nav|theme|menu|apps|density)\"").findAll(general).count())
        // #204: editors and pickers take the TV's width; a catalog is one line.
        assertTrue(app.ember.tv.ui.MenuSizing.WIDE_DIALOG.value > 680f); assertTrue(app.ember.tv.ui.MenuSizing.WIDE_DIALOG_HEIGHT.value > 540f)
        val editor = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertEquals(3, Regex("wide = true\\) \\{").findAll(editor).count())
        val catalogs = java.io.File("src/main/java/app/ember/tv/ui/SettingsWatching.kt").readText()
        assertTrue(catalogs.contains("Text(c.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))"))
    }

    @Test fun theQuickPanelHeaderHasNoProfileButton() {
        // #206: Ember settings and TV settings only; profiles switch in the panel's Profile section.
        val panel = java.io.File("src/main/java/app/ember/tv/ui/QuickPanel.kt").readText()
        assertTrue(panel.contains("HeaderButton(stringResource(R.string.ui_ember_settings)")); assertTrue(panel.contains("HeaderButton(stringResource(R.string.ui_qs_tv_settings)"))
        assertEquals(2, Regex("HeaderButton\\(").findAll(panel.substringBefore("fun tv(action: String)")).count())
        assertFalse(panel.contains("· switch"))
    }

    @Test fun playerPicturesSquareIconsWallpapersAndTheAppPicker() {
        val pictures = app.ember.tv.ui.SettingPictures
        // #207
        for ((id, _) in app.ember.tv.player.PlayerInterfaces.options) assertTrue(id, pictures.of("player", id) in pictures.known)
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/SettingsWatching.kt").readText().contains("PictureChoice(\"player\", SettingDefinitions.playerInterface.options"))
        // #211
        val shapes = app.ember.tv.ui.AppShapes
        assertEquals(listOf("banner", "circle", "square"), shapes.options.map { it.first }); assertTrue(pictures.of("apps", "square") in pictures.known)
        assertEquals("square", shapes.normalize("square")); assertEquals("circle", shapes.normalize(null)); assertEquals("circle", shapes.normalize("hexagon"))
        assertTrue(shapes.squareRadius(112f) in 8..28); assertTrue(shapes.squareRadius(400f) == 28)
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText().contains("square = shape == \"square\")"))
        // #209
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/LightUi.kt").readText().contains("background(Brush.linearGradient(lightWallpaperColors(id)))"))
        // #210
        assertEquals(6, app.ember.tv.ui.AppChoices.PER_ROW); assertTrue(app.ember.tv.ui.AppChoices.ICON in 48..72)
        val picker = java.io.File("src/main/java/app/ember/tv/ui/HomeToolsSettings.kt").readText()
        assertTrue(picker.contains("chunked(AppChoices.PER_ROW)")); assertTrue(picker.contains("AppChoiceTile(a,shown(a.pkg),Modifier.weight(1f))"))
    }

    @Test fun slidersAndTheApiKeyGrid() {
        // #213
        val sliders = app.ember.tv.ui.StepSliders
        assertEquals(1, sliders.step(0, 5, forward = true)); assertEquals(3, sliders.step(4, 5, forward = false))
        assertNull("left at the start moves focus on", sliders.step(0, 5, forward = false)); assertNull(sliders.step(4, 5, forward = true))
        assertEquals(2, sliders.nearest(listOf(0, 30, 60, 90), 55)); assertEquals(0, sliders.nearest(listOf(0, 30), -5))
        val panel = java.io.File("src/main/java/app/ember/tv/ui/QuickPanel.kt").readText()
        assertTrue(panel.contains("StepSlider(stringResource(R.string.ui_sleep_timer)")); assertFalse(panel.contains("listOf(0, 30, 60, 90, 120).forEach"))
        for (file in listOf("LightUi.kt", "HomeToolsSettings.kt", "WidgetEditor.kt", "LiveTvScreen.kt")) assertTrue(file, java.io.File("src/main/java/app/ember/tv/ui/$file").readText().contains("StepSlider("))
        // #214: every service has a tag and a line on its limits; the status follows the key.
        assertEquals(setOf("TMDB"), ApiKeys.SERVICES.filter(ApiKeys::required).map { it.name }.toSet())
        for (s in ApiKeys.SERVICES) assertTrue(s.name, !ApiKeys.LIMITS[s.name].isNullOrBlank())
        assertEquals(ApiKeys.Status.SET, ApiKeys.status(ready = true, required = true))
        assertEquals(ApiKeys.Status.MISSING_REQUIRED, ApiKeys.status(ready = false, required = true))
        assertEquals(ApiKeys.Status.MISSING_OPTIONAL, ApiKeys.status(ready = false, required = false))
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/SettingsScreen.kt").readText().contains("item(key = \"keysgrid\") { ApiKeysGrid(vm) }"))
    }

    @Test fun sizesMarginsNextEpisodeAndPlaybackInfo() {
        val pictures = app.ember.tv.ui.SettingPictures
        // #215
        for ((id, _) in app.ember.tv.ui.UiSizes.LABELS) { assertTrue(pictures.of("uisize", id) in pictures.known); assertTrue(pictures.of("textsize", id) in pictures.known) }
        val margins = app.ember.tv.ui.MarginSteps
        assertEquals(0, margins.values.first()); assertEquals(50, margins.values.last())
        assertEquals(25, margins.of("2.5")); assertEquals(50, margins.of("5")); assertEquals(0, margins.of("NaN"))
        assertEquals("2.5", margins.text(25)); assertEquals("5", margins.text(50)); assertEquals("0.5%", margins.label(5))
        for (v in margins.values) assertTrue(v.toString(), app.ember.tv.data.ScreenMargins.valid(margins.text(v)))
        // #217
        val next = app.ember.tv.ui.NextEpisodeSteps
        assertEquals(0, next.of(autoplay = false, countdown = 15)); assertEquals(15, next.of(true, 15)); assertEquals(10, next.of(true, 11))
        assertEquals("Off", next.label(0)); assertEquals("After 10s", next.label(10))
        // #218
        assertTrue(pictures.of("info", "start") in pictures.known && pictures.of("info", "ask") in pictures.known)
        // #216
        val panel = java.io.File("src/main/java/app/ember/tv/ui/QuickPanel.kt").readText()
        assertTrue(panel.contains("ReturnTile(title, Modifier.focusRequester(first))")); assertTrue(panel.contains("screenHeightDp / 3"))
    }

    @Test fun subtitleLooksAndFonts() {
        // #219
        val fonts = SubtitleFonts
        assertEquals(listOf("default", "sans", "serif", "mono", "condensed", "custom"), fonts.options.map { it.first })
        assertEquals("default", fonts.normalize("comic")); assertEquals("serif", fonts.normalize("serif")); assertEquals("default", SubtitleConfig().font)
        assertNull(fonts.systemFamily("default")); assertEquals("monospace", fonts.systemFamily("mono"))
        assertTrue(fonts.looksLikeFont(byteArrayOf(0, 1, 0, 0, 9))); assertTrue(fonts.looksLikeFont("OTTO".toByteArray()))
        assertFalse(fonts.looksLikeFont("<!DOCTYPE html>".toByteArray())); assertFalse(fonts.looksLikeFont(byteArrayOf(0, 1)))
        // A minimal font with a name table: family name 1 as UTF-16 on the Windows platform.
        val family = "Ember Sans".toByteArray(Charsets.UTF_16BE)
        val buf = java.nio.ByteBuffer.allocate(12 + 16 + 6 + 12 + family.size)
        buf.putInt(0x00010000); buf.putShort(1); buf.putShort(0); buf.putShort(0); buf.putShort(0)
        buf.put("name".toByteArray()); buf.putInt(0); buf.putInt(28); buf.putInt(18 + family.size)
        buf.putShort(0); buf.putShort(1); buf.putShort(18)
        buf.putShort(3); buf.putShort(1); buf.putShort(0x409); buf.putShort(1); buf.putShort(family.size.toShort()); buf.putShort(0)
        buf.put(family)
        assertEquals("Ember Sans", fonts.familyName(buf.array()))
        assertNull(fonts.familyName(ByteArray(8)))
        val looks = app.ember.tv.ui.SubtitleLooks
        assertTrue(looks.scale("small") < looks.scale("normal") && looks.scale("normal") < looks.scale("xlarge"))
        assertTrue(looks.lift("low") < looks.lift("normal") && looks.lift("normal") < looks.lift("high"))
        val engine = java.io.File("src/main/java/app/ember/tv/player/SubtitleEngine.kt").readText()
        assertTrue(engine.contains("SubtitleFonts.typeface(view.context, c.font)")); assertFalse(engine.contains("Color.BLACK, Typeface.DEFAULT_BOLD)"))
    }

    @Test fun serviceGridsForTrackersServersAndProfiles() {
        // #220
        val tiles = app.ember.tv.ui.MediaServerTiles.of(plex = true, plexSignedOut = false, plexUser = null,
            servers = listOf(app.ember.tv.ui.MediaServerTiles.Server("k1", "Home", "jellyfin", "ann", signedOut = false, failing = false),
                app.ember.tv.ui.MediaServerTiles.Server("k2", "Den", "emby", "", signedOut = true, failing = false)))
        assertEquals(listOf("plex", "k1", "k2", "add-server"), tiles.map { it.id })
        assertEquals(listOf(app.ember.tv.ui.ServiceMark.OK, app.ember.tv.ui.ServiceMark.OK, app.ember.tv.ui.ServiceMark.PROBLEM, app.ember.tv.ui.ServiceMark.ADD), tiles.map { it.mark })
        assertEquals("Account owner", tiles[0].line); assertEquals("Emby · signed out", tiles[2].tag)
        assertEquals(app.ember.tv.ui.ServiceMark.OFF, app.ember.tv.ui.MediaServerTiles.of(false, false, null, emptyList()).first().mark)
        // #221
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/SettingsScreen.kt").readText().contains("item(key = \"trackergrid\") { TrackersGrid(vm, openKeys) }"))
        assertEquals("Home, Jellyfin, ann", app.ember.tv.ui.ServiceTiles.spoken(tiles[1]))
        // #222: colours only ever added on the end; a name for each; dark text reads on all of them.
        assertEquals(14, Profile.COLORS.size); assertEquals(0xFFFFB547, Profile.COLORS[0]); assertEquals(0xFFFF8A65, Profile.COLORS[5])
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertEquals(Profile.COLORS.size, Regex("<item>").findAll(strings.substringAfter("profile_color_names").substringBefore("</string-array>")).count())
        for (c in Profile.COLORS) assertTrue(java.lang.Long.toHexString(c), androidx.compose.ui.graphics.Color(c).luminance() > .2f)
        assertEquals("content://media/1", Profile.usablePhoto(" content://media/1 ")); assertEquals("https://a.test/p.jpg", Profile.usablePhoto("https://a.test/p.jpg"))
        assertNull(Profile.usablePhoto("file:///sdcard/p.jpg")); assertNull(Profile.usablePhoto("http://a.test/p.jpg")); assertNull(Profile(id = "a", name = "A").photo)
    }

    @Test fun performanceStartupAndScreensaverPictures() {
        val pictures = app.ember.tv.ui.SettingPictures
        // #223
        val groups = mapOf("perf" to DevicePerformance.options.map { it.first }, "glass" to GlassStyle.choices.map { it.first },
            "focusbg" to FocusBackground.choices.map { it.first }, "cardfocus" to CardFocusStyle.choices.map { it.first },
            // #224
            "start" to app.ember.tv.ui.StartupChoices.options.map { it.first }, "saver" to app.ember.tv.screensaver.ScreensaverFeed.ALL.map { it.first })
        for ((group, options) in groups) for (option in options) {
            assertTrue("$group-$option", pictures.of(group, option) in pictures.known)
            assertTrue(group in app.ember.tv.ui.EffectPictures.groups)
        }
        assertEquals(listOf(1, 2, 3, 4), listOf("low", "medium", "high", "ultra").map(app.ember.tv.ui.EffectPictures::bars))
        assertEquals("home", app.ember.tv.ui.StartupChoices.normalize("satellite")); assertEquals("input", app.ember.tv.ui.StartupChoices.normalize("input"))
        val general = java.io.File("src/main/java/app/ember/tv/ui/SettingsGeneral.kt").readText()
        for (g in listOf("perf", "glass", "cardfocus")) assertTrue(g, general.contains("PictureChoice(\"$g\""))
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/FocusBackdrop.kt").readText().contains("PictureChoice(\"focusbg\""))
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/SettingsSystem.kt").readText().contains("PictureToggles(\"saver\""))
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/HomeToolsSettings.kt").readText().contains("PictureChoice(\"start\""))
    }

    @Test fun upNextCardReadsLikeAStreamingApp() {
        // #225
        assertEquals("S1:E4 Malware", app.ember.tv.player.UpNextUi.line(1, 4, " Malware "))
        assertEquals("S2:E10", app.ember.tv.player.UpNextUi.line(2, 10, ""))
        assertEquals("UP NEXT IN 6", app.ember.tv.player.UpNextUi.heading(6, finding = false))
        assertEquals("UP NEXT IN 0", app.ember.tv.player.UpNextUi.heading(-1, false))
        assertEquals("FINDING A SOURCE…", app.ember.tv.player.UpNextUi.heading(3, finding = true))
        val layout = java.io.File("src/main/java/app/ember/tv/player/PlayerLayout.kt").readText()
        assertTrue(layout.contains("setContent { UpNextCard(upNextUi) { playNext() } }"))
        val next = java.io.File("src/main/java/app/ember/tv/player/PlayerNextUp.kt").readText()
        assertTrue(next.contains("thumbnail = next.thumbnail; rating = intent.getStringExtra(PlayRequest.EXTRA_AGE_RATING)"))
    }

    @Test fun theTraktNoticeUsesTheRequiredWording() {
        assertEquals("Ember uses Trakt but is not endorsed or certified by Trakt", Legal.TRAKT_NOTICE)
        assertTrue(java.io.File("src/main/java/app/ember/tv/data/Legal.kt").readText().contains("Item(\"Trakt\", \"\$TRAKT_NOTICE."))
    }

    @Test fun widgetCardsTheHomeNoticeAndTraktCards() {
        // #176: one card per widget; its last line is what is wrong, or whether it is on Home.
        assertEquals("On Home", app.ember.tv.ui.WidgetDrafts.state(true, null)); assertEquals("Hidden from Home", app.ember.tv.ui.WidgetDrafts.state(false, null))
        assertEquals("Could not load: gone", app.ember.tv.ui.WidgetDrafts.state(true, "Could not load: gone"))
        assertEquals(listOf("Move up" to -1, "Move down" to 1, "Move to the bottom" to 3), app.ember.tv.ui.WidgetDrafts.moves(1, 4))
        assertTrue(app.ember.tv.ui.WidgetDrafts.moves(-1, 4).isEmpty())
        val tv = java.io.File("src/main/java/app/ember/tv/ui/WidgetEditor.kt").readText()
        assertTrue(tv.contains("testTag(\"widget_card\")")); assertTrue(tv.contains("OptionMenu(w?.title"))
        // #177
        val model = java.io.File("src/main/java/app/ember/tv/HomeViewModel.kt").readText()
        assertTrue(model.contains("widgets.errors[w.id]?.let { add(HomeBlock.Notice(id, w.title, it)) }"))
        assertTrue(java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText().contains("is HomeViewModel.HomeBlock.Notice ->"))
        // #178
        val list = CollectionRequests.ListRef(1, "Best of", "ann", 1, 12, "")
        assertEquals("@ann · 1 title · 12 likes", app.ember.tv.ui.WidgetDrafts.traktLine(list))
        assertEquals("40 titles", app.ember.tv.ui.WidgetDrafts.traktLine(list.copy(by = "", items = 40, likes = 0)))
        assertTrue(tv.contains("testTag(\"trakt_list\")"))
    }

    @Test fun aWidgetSavedWhileOthersLoadIsLoadedWhenTheyFinish() {
        // #168: it used to be skipped and stay empty until Home was opened again.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>(); val asked = mutableListOf<Long?>()
        try {
            val state = WidgetsState(memoryPrefs(), scope, { "me" }, { true }, page = { source ->
                asked += source.traktListId; if (source.traktListId == 42L) gate.await()
                Result.success(CollectionPage(listOf(Meta("tt${source.traktListId}", "movie", "Title")), 1, true))
            }, onChanged = {}, clock = { 0L })
            assertNull(state.save(widget())); state.refresh { true }
            assertEquals(setOf("w1"), state.loading)
            assertNull(state.save(widget("late", "Late", source = trakt.copy(traktListId = 7)))); state.refresh { true }
            assertEquals("the first load is not restarted", listOf<Long?>(42L), asked)
            gate.complete(Unit)
            assertEquals(listOf<Long?>(42L, 7L), asked); assertEquals("tt7", state.items["late"]?.single()?.id)
            assertTrue(state.loading.isEmpty()); assertTrue(state.errors.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun widgetsLoadSaveAndForgetPerProfile() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = memoryPrefs(); var profile = "one"; var now = 0L; var changed = 0
        val asked = mutableListOf<CollectionSource>()
        val titles = (1..30).map { Meta("tt$it", "movie", "Title $it") }
        fun state() = WidgetsState(prefs, scope, { profile }, { true }, page = { source ->
            asked += source
            if (source.traktListId == 13L) Result.failure(IllegalStateException("gone")) else Result.success(CollectionPage(titles, 30, true))
        }, onChanged = { changed++ }, clock = { now })
        try {
            val state = state()
            assertEquals("Enter a widget title.", state.save(widget(title = "")))
            assertNull(state.save(widget())); assertEquals(1, changed)
            assertNull(state.save(widget("mine", "Later", HomeWidgets.ROW, CollectionSource(provider = HomeWidgets.LIST, catalogId = "watchlist"))))
            assertNull(state.save(widget("broken", "Broken", source = trakt.copy(traktListId = 13))))
            state.refresh { true }
            assertEquals("the size limit is applied", 10, state.items["w1"]!!.size)
            assertEquals("your own lists need no request", listOf(42L, 13L), asked.map { it.traktListId })
            assertEquals(setOf("broken"), state.failed); assertTrue(state.loading.isEmpty())
            // #168: the TV list says why, in Ember's own words; anything else reads as the connection.
            assertEquals(mapOf("broken" to "gone"), state.errors)
            assertTrue(WidgetsState.reason(java.io.IOException("socket https://secret.example")).startsWith("The source could not be reached"))
            assertEquals("Could not load: gone", app.ember.tv.ui.WidgetDrafts.status(on = true, loading = false, error = "gone", titles = 0))
            assertEquals("Loading…", app.ember.tv.ui.WidgetDrafts.status(true, true, "gone", 0)); assertEquals("Nothing to show yet", app.ember.tv.ui.WidgetDrafts.status(true, false, null, 0))
            assertNull(app.ember.tv.ui.WidgetDrafts.status(true, false, null, 4)); assertNull(app.ember.tv.ui.WidgetDrafts.status(false, false, "gone", 0))
            // Nothing is asked again while fresh; a changed source or an old load is.
            state.refresh { true }; assertEquals(3, asked.size) // the failed one is retried
            assertNull(state.save(widget(title = "Top ten"))); state.refresh { it == HomeWidgets.section("w1") }; assertEquals(3, asked.size)
            assertNull(state.save(widget(source = trakt.copy(traktListId = 99)))); state.refresh { it == HomeWidgets.section("w1") }; assertEquals(99L, asked.last().traktListId)
            now += WidgetsState.FRESH_MILLIS + 1; state.refresh { it == HomeWidgets.section("w1") }; assertEquals(5, asked.size)
            // Switched off on Home: not loaded.
            val before = asked.size; now += WidgetsState.FRESH_MILLIS + 1; state.refresh { false }; assertEquals(before, asked.size)
            // Another profile has its own widgets; the first profile's come back.
            profile = "two"; state.refresh { true }
            assertTrue(state.list.isEmpty()); assertTrue(state.items.isEmpty())
            profile = "one"; assertEquals(listOf("w1", "mine", "broken"), state().list.map { it.id })
            val again = state(); assertTrue(again.remove("w1")); assertFalse(again.remove("w1")); assertEquals(listOf("mine", "broken"), state().list.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun thePhoneListsWidgetsAndTheEditorCoversEverySource() {
        val content = PhoneContent(listOf("opaque" to "Popular"), emptyList(), emptyList(), widgets = listOf(
            PhoneWidget("w-1", "Top <10>", HomeWidgets.NUMBERED, "poster", 10, "Trakt list 42", "10 titles", canMoveUp = false),
            PhoneWidget("w-2", "Comedies", HomeWidgets.CAROUSEL, "default", 20, "Filters: Movies", "Loading", canMoveDown = false)))
        val page = ContentPhone.render(content, "lease")
        val list = page.substringAfter("<h2>Home widgets</h2>").substringBefore("<h2>Collections</h2>")
        assertTrue(page.indexOf("<h2>Home widgets</h2>") < page.indexOf("<h2>Collections</h2>"))
        assertTrue(list.contains("Top &lt;10&gt;")); assertFalse(list.contains("<10>"))
        assertTrue(list.contains("<span class=chip>Numbered row</span>Trakt list 42 · 10 titles")); assertTrue(list.contains("<span class=chip>Cinematic carousel</span>"))
        assertEquals(4, Regex("name=action value=move-widget").findAll(list).count()); assertEquals(2, Regex("name=action value=delete-widget").findAll(list).count())
        assertEquals(2, Regex("on Home\" title=\"[^\"]+\" disabled").findAll(list).count())
        assertTrue(list.contains("/content?k=lease&amp;widget=w-1")); assertTrue(list.contains("/content?k=lease&amp;widget=new"))
        assertEquals(Regex("<div").findAll(page).count(), Regex("</div>").findAll(page).count())

        val edit = ContentPhone.widgetEditor(content, "lease", "w-1")
        assertTrue(edit.contains("name=action value=save-widget")); assertTrue(edit.contains("name=target value=\"w-1\""))
        assertTrue(edit.contains("value=\"Top &lt;10&gt;\"")); assertTrue(edit.contains("value=numbered checked")); assertTrue(edit.contains("<option value=poster selected>"))
        assertTrue(edit.contains("value=10>")); assertTrue(edit.contains("<option value=keep>")); assertTrue(edit.contains("Now: <b>Trakt list 42</b>"))
        for (kind in listOf("catalog", "filters", "trakt", "tmdb", "list")) { assertTrue(kind, edit.contains("<option value=$kind>")); assertTrue(kind, edit.contains("data-kind=$kind")) }
        for (genre in ExploreGenres.all) assertTrue(genre.key, edit.contains("name=\"genre:${genre.key}\""))
        assertTrue(edit.contains("bind('kind','data-kind')"))
        val fresh = ContentPhone.widgetEditor(content, "lease", "new")
        assertTrue(fresh.contains("New widget")); assertFalse("a new widget must choose a source", fresh.contains("value=keep")); assertTrue(fresh.contains("value=row checked"))
        assertTrue(ContentPhone.widgetEditor(content, "lease", "w-gone").contains("no longer exists"))
    }

    @Test fun heroBannersTrackerListsAndMdblistAreWidgetSources() {
        // The follow-ups of #161.
        assertTrue(HomeWidgets.styles.any { it.first == HomeWidgets.HERO }); assertEquals("Hero banner", HomeWidgets.styleName(HomeWidgets.HERO))
        assertEquals(HomeWidgets.HERO, HomeWidgets.normalize(widget(style = HomeWidgets.HERO)).style)
        // A tracker row is one of your lists, by its Home key.
        val upNext = CollectionSource(provider = HomeWidgets.LIST, catalogId = "smart:upnext", title = "Up next · WeTrakr")
        assertNull(HomeWidgets.error(widget(source = upNext))); assertEquals("Up next · WeTrakr", HomeWidgets.sourceLabel(upNext, emptyList()))
        assertNotNull(HomeWidgets.error(widget(source = upNext.copy(catalogId = "smart:"))))
        assertNotNull(HomeWidgets.error(widget(source = CollectionSource(provider = "nowhere"))))
        // MDBList: a link, user/list-name or number.
        assertEquals("garycrawfordgc/latest-tv-shows", HomeWidgets.mdblistPath("https://mdblist.com/lists/garycrawfordgc/latest-tv-shows/?sort=rank"))
        assertEquals("garycrawfordgc/latest-tv-shows", HomeWidgets.mdblistPath(" garycrawfordgc/latest-tv-shows "))
        assertEquals("2194", HomeWidgets.mdblistPath("2194"))
        for (bad in listOf("", "https://mdblist.com/", "a/b/c", "user/list name", "../x")) assertNull(bad, HomeWidgets.mdblistPath(bad))
        val mdb = CollectionSource(provider = HomeWidgets.MDBLIST, catalogId = "2194")
        assertNull(HomeWidgets.error(widget(source = mdb))); assertEquals("MDBList 2194", HomeWidgets.sourceLabel(mdb, emptyList()))
        assertNotNull(HomeWidgets.error(widget(source = mdb.copy(catalogId = "not a list"))))
        assertEquals(listOf(widget(source = mdb)), HomeWidgets.decode(HomeWidgets.encode(listOf(widget(source = mdb)))))
    }

    @Test fun mdblistPagesAndTraktListSearchesAreRead() {
        val page = kotlinx.serialization.json.Json.parseToJsonElement("""{"movies":[{"id":1,"rank":1,"title":"Film","imdb_id":"tt0000001","release_year":2024,"mediatype":"movie"},{"title":"No id"}],
            "shows":[{"id":2,"rank":2,"title":"Show","imdb_id":"tt0000002","release_year":null,"mediatype":"show"}]}""")
        val (all, raw) = CollectionRequests.parseMdblist(page, null)
        // #170: a mixed list keeps the list's own order, by rank.
        val mixed = kotlinx.serialization.json.Json.parseToJsonElement("""{"movies":[{"rank":2,"title":"B","imdb_id":"tt2"},{"rank":4,"title":"D","imdb_id":"tt4"}],"shows":[{"rank":1,"title":"A","imdb_id":"tt1"},{"rank":3,"title":"C","imdb_id":"tt3"},{"title":"Unranked","imdb_id":"tt9"}]}""")
        assertEquals(listOf("tt1", "tt2", "tt3", "tt4", "tt9"), CollectionRequests.parseMdblist(mixed, null).first.map { it.id })
        // #171: the TV editor takes the same list text as the phone.
        assertEquals("user/top-list", app.ember.tv.ui.WidgetDrafts.mdblist("https://mdblist.com/lists/user/top-list", true).second?.catalogId)
        assertNull(app.ember.tv.ui.WidgetDrafts.mdblist("2194", false).second); assertTrue(app.ember.tv.ui.WidgetDrafts.mdblist("2194", false).first!!.contains("API key"))
        assertNotNull(app.ember.tv.ui.WidgetDrafts.mdblist("not a list", true).first)
        // #169: a banner or carousel comes back on the title that was opened; long ones count instead of dots.
        val shown = (1..20).map { Meta("tt$it", "movie", "Title $it") }
        assertEquals(0, app.ember.tv.ui.WidgetPlace.index("widget:test169", shown))
        app.ember.tv.ui.WidgetPlace.keep("widget:test169", shown[7]); assertEquals(7, app.ember.tv.ui.WidgetPlace.index("widget:test169", shown))
        assertEquals("gone from the list: start again", 0, app.ember.tv.ui.WidgetPlace.index("widget:test169", shown.take(3)))
        assertNull(app.ember.tv.ui.WidgetPlace.counter(3, 12)); assertEquals("13 / 30", app.ember.tv.ui.WidgetPlace.counter(12, 30))
        assertEquals(listOf("tt0000001" to "movie", "tt0000002" to "series"), all.map { it.id to it.type }); assertEquals(3, raw)
        assertEquals("2024", all[0].releaseInfo); assertNull(all[1].releaseInfo); assertTrue(all[0].poster!!.contains("tt0000001"))
        assertEquals(listOf("tt0000002"), CollectionRequests.parseMdblist(page, "TV").first.map { it.id })
        assertEquals(listOf("tt0000001"), CollectionRequests.parseMdblist(page, "MOVIE").first.map { it.id })
        assertTrue(runCatching { CollectionRequests.parseMdblist(kotlinx.serialization.json.JsonArray(emptyList()), null) }.isFailure)

        val lists = CollectionRequests.parseTraktLists(kotlinx.serialization.json.Json.parseToJsonElement("""[
            {"like_count":5,"list":{"name":"Top  Films","description":"The\nbest","item_count":250,"likes":6282,"ids":{"trakt":42,"slug":"top"},"user":{"username":"trakt"}}},
            {"type":"list","score":1,"list":{"name":"Searched","item_count":3,"likes":0,"ids":{"trakt":7}}},
            {"list":{"name":"","ids":{"trakt":8}}},{"list":{"name":"No id","ids":{}}},{"list":{"name":"Again","ids":{"trakt":42}}}]"""))
        assertEquals(listOf(42L, 7L), lists.map { it.id })
        assertEquals(CollectionRequests.ListRef(42, "Top  Films", "trakt", 250, 6282, "The best"), lists[0]); assertEquals("", lists[1].by)
        val source = app.ember.tv.ui.WidgetDrafts.trakt(lists[0], "TV")
        assertEquals(42L, source.traktListId); assertEquals("TV", source.mediaType); assertNull(HomeWidgets.error(widget(source = source)))
    }

    @Test fun theTvEditorNamesAnUntitledWidgetAfterItsSource() {
        val fresh = app.ember.tv.ui.WidgetDrafts.new()
        assertFalse(app.ember.tv.ui.WidgetDrafts.hasSource(fresh)); assertNotNull(HomeWidgets.error(fresh.copy(title = "x")))
        val named = app.ember.tv.ui.WidgetDrafts.withSource(fresh, trakt)
        assertEquals("Top", named.title); assertTrue(app.ember.tv.ui.WidgetDrafts.hasSource(named)); assertNull(HomeWidgets.error(named))
        assertEquals("Mine", app.ember.tv.ui.WidgetDrafts.withSource(fresh.copy(title = "Mine"), trakt).title)
        assertEquals("Numbered row · Trakt list 42 · up to 10 titles", app.ember.tv.ui.WidgetDrafts.summary(widget(), emptyList()))
        assertNotEquals(fresh.id, app.ember.tv.ui.WidgetDrafts.new().id)
    }

    @Test fun thePhoneEditorSearchesPeopleServicesAndTraktLists() {
        val content = PhoneContent(listOf("opaque" to "Popular"), emptyList(), emptyList(), lists = HomeWidgets.lists + ("smart:upnext" to "Up next · WeTrakr"))
        val edit = ContentPhone.widgetEditor(content, "lease", "new")
        assertTrue(edit.contains("value=hero")); assertTrue(edit.contains("Hero banner"))
        assertTrue(edit.contains("<option value=mdblist>")); assertTrue(edit.contains("data-kind=mdblist")); assertTrue(edit.contains("name=mdblist "))
        assertTrue(edit.contains("<option value=\"smart:upnext\">Up next · WeTrakr</option>"))
        for ((ref, lookup) in listOf("actor" to "person", "director" to "person", "company" to "company", "provider" to "provider")) assertTrue(ref, edit.contains("data-ref=$ref data-lookup=$lookup"))
        assertTrue(edit.contains("id=trakt-find")); assertTrue(edit.contains("data-tab=popular")); assertTrue(edit.contains("data-tab=trending"))
        assertTrue(edit.contains("/lookup?k=")); assertFalse("search boxes are not submitted", Regex("<input type=search[^>]* name=").containsMatchIn(edit))
        val server = java.io.File("src/main/java/app/ember/tv/phone/PhoneSetup.kt").readText()
        assertTrue(server.contains("path == \"/lookup\""))
    }

    @Test fun widgetsExportAndImportBetweenTvsAndProfiles() {
        // #163
        val filters = widget("f", "Comedies", HomeWidgets.CAROUSEL, CollectionDrafts.discover("Comedies", ExploreQuery(genres = setOf("comedy")), "someone-else"))
        val pack = HomeWidgets.pack(listOf(widget(), filters, widget("mine", "Later", HomeWidgets.ROW, CollectionSource(provider = HomeWidgets.LIST, catalogId = "watchlist"))))
        assertTrue(pack.contains("\"format\":\"ember-widgets\""))
        val opened = HomeWidgets.unpack(pack, "me").getOrThrow()
        assertEquals(listOf("w1", "f", "mine"), opened.map { it.id })
        assertEquals("filter widgets become the importing profile's", "me", opened[1].source.ownerProfile)
        assertEquals(widget(), opened[0])
        // A bare list is accepted; other files and empty packs are refused with a reason.
        assertEquals(listOf("w1"), HomeWidgets.unpack(HomeWidgets.encode(listOf(widget())), "me").getOrThrow().map { it.id })
        assertTrue(HomeWidgets.unpack("{\"format\":\"ember-collections\"}", "me").isFailure)
        assertTrue(HomeWidgets.unpack("not json", "me").exceptionOrNull()!!.message!!.contains("widget pack"))
        assertTrue(HomeWidgets.unpack(HomeWidgets.pack(listOf(widget(title = " "))), "me").exceptionOrNull()!!.message!!.contains("no widgets"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val state = WidgetsState(memoryPrefs(), scope, { "me" }, { true }, page = { Result.success(CollectionPage(emptyList(), 0, true)) }, onChanged = {}, clock = { 0L })
            assertNull(state.save(widget(title = "Old name")))
            assertEquals("Imported: 2 added, 1 updated.", state.importAll(opened))
            assertEquals(listOf("w1" to "Top 10", "f" to "Comedies", "mine" to "Later"), state.list.map { it.id to it.title })
            assertEquals("Imported: 0 added, 3 updated.", state.importAll(opened))
            assertTrue(state.importAll(emptyList()).contains("no widgets"))
        } finally { scope.cancel() }
    }

    @Test fun widgetsAndCollectionsShareOneSettingsPageAndThePhoneMovesPacks() {
        // #163
        assertEquals("Home", app.ember.tv.ui.SettingsTab.WIDGETS.group)
        assertEquals("Widgets & collections", app.ember.tv.ui.SettingsTab.WIDGETS.label)
        val index = app.ember.tv.ui.SETTINGS_INDEX.single { it.first == app.ember.tv.ui.SettingsTab.WIDGETS }.second.lowercase()
        for (word in listOf("widgets", "import", "export", "collections", "trakt", "carousel")) assertTrue(word, word in index)
        assertFalse(java.io.File("src/main/java/app/ember/tv/ui/SettingsWatching.kt").readText().contains("CollectionsSettings(vm)"))
        val screen = java.io.File("src/main/java/app/ember/tv/ui/SettingsScreen.kt").readText()
        assertTrue(screen.contains("WidgetsSettings(vm)")); assertTrue(screen.contains("CollectionsSettings(vm)"))

        val some = PhoneContent(emptyList(), emptyList(), emptyList(), widgets = listOf(PhoneWidget("w-1", "Top", HomeWidgets.ROW, "default", 20, "Trakt list 42", "20 titles")))
        val page = ContentPhone.render(some, "lease")
        assertTrue(page.contains("action=\"/widgets-export?k=lease\"")); assertTrue(page.contains("action=\"/widgets?k=lease\" enctype=multipart/form-data"))
        assertFalse("nothing to download yet", ContentPhone.render(PhoneContent(emptyList(), emptyList(), emptyList()), "lease").contains("/widgets-export"))
        assertTrue(ContentPhone.render(PhoneContent(emptyList(), emptyList(), emptyList()), "lease").contains("action=\"/widgets?k=lease\""))
        val server = java.io.File("src/main/java/app/ember/tv/phone/PhoneSetup.kt").readText()
        assertTrue(server.contains("path == \"/widgets-export\"")); assertTrue(server.contains("path == \"/widgets\""))
    }

    private fun memoryPrefs(): Prefs {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args -> when {
            method.name.startsWith("put") -> { values[args[0] as String] = args[1]; proxy }
            method.name == "remove" -> { values.remove(args[0]); proxy }; method.name == "clear" -> { values.clear(); proxy }; method.name == "commit" -> true; else -> null
        } } as SharedPreferences.Editor
        val sp = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args -> when {
            method.name == "edit" -> editor; method.name == "contains" -> values.containsKey(args[0]); method.name == "getAll" -> values.toMap()
            method.name.startsWith("get") -> values[args[0]] ?: args[1]; else -> null
        } } as SharedPreferences
        return Prefs(object : ContextWrapper(null) { override fun getSharedPreferences(name: String, mode: Int) = sp })
    }
}
