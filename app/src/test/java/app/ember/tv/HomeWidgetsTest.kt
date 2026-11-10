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
        assertEquals("In progress", HomeWidgets.sourceLabel(CollectionSource(provider = HomeWidgets.LIST, catalogId = "continue"), emptyList()))
        val q = ExploreQuery(kind = ExploreKind.MOVIE, genres = setOf("comedy"), fromYear = 2020, toYear = 2026, minRating = 7.0, sort = ExploreSort.RATING)
        assertEquals("Movies · Comedy · 2020–2026 · 7+ · Highest rated", HomeWidgets.summary(q))
        assertEquals("Filters: " + HomeWidgets.summary(q), HomeWidgets.sourceLabel(CollectionDrafts.discover("x", q, "main"), emptyList()))
    }

    @Test fun aWidgetDrawsInItsOwnStyleAndBuiltInRowsFollowHome() {
        assertEquals(WidgetLook(wide = true, numbered = false, large = false), WidgetLook.of(null, homeWide = true))
        assertEquals(WidgetLook(wide = false, numbered = false, large = false), WidgetLook.of(null, homeWide = false))
        assertEquals(WidgetLook(wide = false, numbered = true, large = false), WidgetLook.of(widget(), homeWide = true))
        assertEquals(WidgetLook(wide = true, numbered = false, large = false), WidgetLook.of(widget(style = HomeWidgets.ROW).copy(shape = "default"), homeWide = true))
        // The carousel is always large wide cards, whatever shape was saved.
        assertEquals(WidgetLook(wide = true, numbered = false, large = true), WidgetLook.of(widget(style = HomeWidgets.CAROUSEL), homeWide = false))
        assertTrue(WidgetLook.CAROUSEL_FRACTION > 1f / 3f && WidgetLook.CAROUSEL_FRACTION < .5f)
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
