package app.ember.tv

import android.content.ContextWrapper
import android.content.SharedPreferences
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
        assertTrue(WidgetLook.CAROUSEL_HEIGHT >= .6f && WidgetLook.CAROUSEL_HEIGHT <= .8f)
        assertEquals(1, WidgetLook.carouselStep(0, 3, forward = true)); assertEquals(1, WidgetLook.carouselStep(2, 3, forward = false))
        assertNull("left on the first title leaves the carousel", WidgetLook.carouselStep(0, 3, forward = false))
        assertNull("right on the last title leaves the carousel", WidgetLook.carouselStep(2, 3, forward = true))
        assertNull(WidgetLook.carouselStep(0, 1, forward = true))
        val home = java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText()
        assertTrue(home.contains("b.widget?.style == app.ember.tv.data.HomeWidgets.CAROUSEL) ModernSection(b.row.displayTitle) {"))
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
        assertTrue(WidgetLook.CAROUSEL_PEEK in 16..48)
        val home = java.io.File("src/main/java/app/ember/tv/ui/ModernUi.kt").readText()
        assertTrue(home.contains("HeroTitle(shown, centered = true)")); assertEquals(2, Regex("CarouselPeek\\(items.getOrNull").findAll(home).count())
        // #175: "10" needs twice the room of "9"; part of the numeral always sits behind the card.
        val one = app.ember.tv.ui.RankNumerals.inset(9, 130f, wide = false); val two = app.ember.tv.ui.RankNumerals.inset(10, 130f, wide = false)
        assertEquals(one * 2, two, .01f); assertTrue(one > 30f && one < 130f * .6f)
        assertTrue(app.ember.tv.ui.RankNumerals.size(130f, false) < 130f * 1.5f); assertTrue(app.ember.tv.ui.RankNumerals.size(220f, true) < 220f * 9f / 16f)
        assertTrue(app.ember.tv.ui.RankNumerals.TUCK > 0f && app.ember.tv.ui.RankNumerals.TUCK < .5f)
        assertFalse(home.contains("RankBadge"))
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
