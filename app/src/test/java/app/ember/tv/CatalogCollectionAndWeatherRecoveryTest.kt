package app.ember.tv

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.unit.dp
import app.ember.tv.data.*
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.state.*
import app.ember.tv.ui.MenuSizing
import app.ember.tv.weather.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class CatalogCollectionAndWeatherRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun movie(id: String) = Meta(id = id, type = "movie", name = id)
    private fun catalog() = CatalogDef(AddonInfo("https://fixture.example", "https://fixture.example", "Fixture",
        listOf("movie"), null, emptyList(), emptyList()), "movie", "list", "List", emptyList(), false, false, false, false)
    private suspend fun ready(predicate: () -> Boolean) { withTimeout(5_000) { while (!predicate()) delay(5) } }
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun context() = object : ContextWrapper(null) {
        private val root = temp.newFolder()
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getFilesDir() = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) {
            val values = mutableMapOf<String, Any?>()
            val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { values[args[0] as String] = args[1]; proxy }
                    method.name == "remove" -> { values.remove(args[0]); proxy }
                    method.name == "clear" -> { values.clear(); proxy }
                    method.name == "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when {
                    method.name == "edit" -> editor
                    method.name == "contains" -> values.containsKey(args[0])
                    method.name == "getAll" -> values.toMap()
                    method.name.startsWith("get") -> values[args[0]] ?: args[1]
                    else -> null
                }
            } as SharedPreferences
        }
    }

    @Test fun mixedProviderIdsKeepMoviesShowsAndEpisodesSeparate() {
        val film = movie("tmdb:100")
        val show = film.copy(type = "series")
        val episode = show.copy(season = 1, episode = 1)
        val list = listOf(film, show, episode, episode.copy(episode = 2), film.copy(name = "Updated"))
        assertEquals(4, list.distinctBy { it.mediaKey() }.size)
        assertEquals(show.mediaKey(), show.copy(type = "anime").mediaKey())
        assertNotEquals(film.copy(id = "x|y").mediaKey(), film.copy(type = "movie|x", id = "y").mediaKey())
    }

    @Test fun catalogDefinitionFailureStopsLoadingAndCanRetry() = runBlocking {
        val scope = scope(); var fail = true
        val state = DiscoverState(scope, AddonClient()) { if (fail) error("offline") else listOf(catalog()) }
        state.browse = { _, _, _ -> listOf(movie("recovered")) }
        try {
            state.openDiscover(); assertFalse(state.discoverLoading); assertTrue(state.failed)
            fail = false; state.retry()
            assertFalse(state.failed); assertEquals("recovered", state.discoverItems.single().id)
        } finally { scope.cancel() }
    }

    @Test fun catalogPagingUsesRawOffsetsAndKeepsFailedPageForRetry() = runBlocking {
        val scope = scope(); val skips = mutableListOf<Int>(); var fail = true
        val state = DiscoverState(scope, AddonClient()) { listOf(catalog()) }
        state.browse = { _, _, skip ->
            skips += skip
            when (skip) {
                0 -> listOf(movie("a"), movie("a"), movie("b"))
                3 -> if (fail) error("offline") else listOf(movie("b"), movie("c"))
                else -> emptyList()
            }
        }
        try {
            state.openDiscover(); state.loadMoreDiscover()
            assertTrue(state.failed); assertEquals(listOf("a", "b"), state.discoverItems.map { it.id })
            fail = false; state.retry(); state.loadMoreDiscover()
            assertEquals(listOf(0, 3, 3, 5), skips)
            assertEquals(listOf("a", "b", "c"), state.discoverItems.map { it.id })
        } finally { scope.cancel() }
    }

    private fun collections(scope: CoroutineScope, loader: suspend () -> List<CatalogDef> = { emptyList() }): CollectionsState {
        val ctx = context()
        return CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), loader, {})
    }
    private fun folder(vararg sources: CollectionSource) = CollectionFolder(id = "folder", title = "Folder", sources = sources.toList())

    private fun collectionPage(source: CollectionSource, skip: Int, items: List<Meta>) =
        CollectionPage(items, Collections.nextSkip(source, skip, items.size), items.isEmpty(), CollectionPage.fingerprint(items.map { it.mediaKey() }))

    @Test fun failedCollectionPagePreservesResultsAndRetriesTheSameOffset() = runBlocking {
        val scope = scope(); val state = collections(scope); val calls = mutableListOf<Int>(); var fail = true
        state.loadPage = { source, _, skip ->
            synchronized(calls) { calls += skip }
            when (skip) {
                0 -> Result.success(listOf(movie("a"), movie("a"), movie("b")))
                else -> if (fail) Result.failure(IllegalStateException("offline")) else Result.success(listOf(movie("c")))
            }.map { collectionPage(source, skip, it) }
        }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource()))
            ready { state.tabs.singleOrNull()?.loading == false }
            state.loadMore(0); ready { state.tabs.single().failed }
            assertFalse(state.tabs.single().end); assertEquals(listOf("a", "b"), state.tabs.single().items.map { it.id })
            fail = false; state.loadMore(0); ready { !state.tabs.single().loading }
            assertEquals(listOf(0, 3, 3), calls)
            assertEquals(listOf("a", "b", "c"), state.tabs.single().items.map { it.id })
            assertFalse(state.tabs.single().failed)
        } finally { scope.cancel() }
    }

    @Test fun sparseTmdbCollectionPagesStillAdvanceToTheNextProviderPage() = runBlocking {
        val scope = scope(); val state = collections(scope); val calls = mutableListOf<Int>()
        state.loadPage = { source, _, skip -> synchronized(calls) { calls += skip }; Result.success(collectionPage(source, skip, listOf(movie("$skip")))) }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource(provider = "tmdb")))
            ready { state.tabs.singleOrNull()?.loading == false }
            state.loadMore(0); ready { !state.tabs.single().loading }
            assertEquals(listOf(0, 20), calls)
        } finally { scope.cancel() }
    }

    @Test fun collectionDefinitionFailuresAndEmptyFirstPageFailuresAreRecoverable() = runBlocking {
        val scope = scope(); var definitionsFail = true; var pageFail = true
        val state = collections(scope) { if (definitionsFail) error("offline") else emptyList() }
        state.loadPage = { source, _, skip -> if (pageFail) Result.failure(IllegalStateException("offline")) else Result.success(collectionPage(source, skip, listOf(movie("recovered")))) }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource()))
            ready { state.tabs.singleOrNull()?.failed == true }
            definitionsFail = false; state.loadMore(0)
            ready { state.tabs.singleOrNull()?.failed == true && !state.tabs.single().loading }
            pageFail = false; state.loadMore(0); ready { state.tabs.singleOrNull()?.items?.isNotEmpty() == true }
            assertEquals("recovered", state.tabs.single().items.single().id)
        } finally { scope.cancel() }
    }

    @Test fun collectionAllTabDoesNotDropAShowThatSharesAMoviesNumericId() = runBlocking {
        // Match viewModelScope's serialized UI updates after concurrent IO completions.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)); val state = collections(scope)
        state.loadPage = { source, _, skip -> Result.success(collectionPage(source, skip, listOf(movie("tmdb:100").copy(type = source.type ?: "movie")))) }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource(type = "movie"), CollectionSource(type = "series")))
            ready { state.tabs.size == 2 && state.tabs.none { it.loading } }
            assertEquals(2, state.allItems.size)
        } finally { scope.cancel() }
    }

    @Test fun collectionOverlappingPagesContinueAndExactRepeatsStop() = runBlocking {
        val scope = scope(); val state = collections(scope)
        state.loadPage = { _, _, skip -> Result.success(when (skip) {
            0 -> CollectionPage(listOf(movie("a")), 100, false, "first")
            100 -> CollectionPage(listOf(movie("a")), 200, false, "overlap")
            else -> CollectionPage(listOf(movie("b")), 300, false, "last")
        }) }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource(provider = "trakt")))
            ready { state.tabs.singleOrNull()?.loading == false }
            state.loadMore(0); ready { state.tabs.single().nextSkip == 200 }
            assertFalse(state.tabs.single().end)
            state.loadMore(0); ready { state.tabs.single().nextSkip == 300 }
            assertEquals(listOf("a", "b"), state.tabs.single().items.map { it.id })
            state.loadMore(0); ready { state.tabs.single().end }
            assertEquals(listOf("a", "b"), state.tabs.single().items.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun collectionAllTabMergesCrossProviderAliasesAndKeepsOtherTitles() = runBlocking {
        // Match viewModelScope's serialized UI updates after concurrent IO completions.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)); val state = collections(scope)
        state.loadPage = { source, _, _ -> Result.success(CollectionPage(
            if (source.provider == "tmdb") listOf(movie("tmdb:100"), movie("b"))
            else listOf(movie("tt1234567").copy(tmdbId = 100), movie("a")), 100, true)) }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource(provider = "trakt"), CollectionSource(provider = "tmdb")))
            ready { state.tabs.size == 2 && state.tabs.none { it.loading } }
            assertEquals(listOf("tt1234567", "a", "b"), state.allItems.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun collectionCanContinueAfterAPageWithNoRenderableTitles() = runBlocking {
        val scope = scope(); val state = collections(scope)
        state.loadPage = { _, _, skip -> Result.success(if (skip == 0) CollectionPage(emptyList(), 100, false, "invalid")
            else CollectionPage(listOf(movie("later")), 200, true, "valid")) }
        try {
            state.openFolder(MediaCollection(id = "c"), folder(CollectionSource(provider = "trakt")))
            ready { state.tabs.singleOrNull()?.loading == false }
            assertFalse(state.tabs.single().end)
            state.loadMore(0); ready { state.tabs.single().items.isNotEmpty() }
            assertEquals("later", state.tabs.single().items.single().id)
        } finally { scope.cancel() }
    }

    @Test fun switchingWeatherOffRejectsLateForecastAndLateLocation() = runBlocking {
        val scope = scope(); val ctx = context(); val prefs = Prefs(ctx); val feed = ScreensaverFeed(ctx)
        val state = WeatherState(Application(), prefs, scope, feed)
        val forecast = CompletableDeferred<WeatherNow>(); val location = CompletableDeferred<Place?>()
        state.forecast = { _, _ -> withContext(NonCancellable) { forecast.await() } }
        state.geocode = { withContext(NonCancellable) { location.await() } }
        try {
            prefs.weatherPlace = Place("Old", 1.0, 1.0).encode()
            state.refreshWeather(true); state.setWeatherCity("Late city"); state.weatherOff()
            forecast.complete(WeatherNow(10, 0, null, null, "C")); location.complete(Place("Late", 2.0, 2.0)); yield()
            assertNull(state.weatherText); assertNull(feed.weather); assertNull(prefs.weatherPlace)
        } finally { scope.cancel() }
    }

    @Test fun newerCityLookupWinsEvenIfCanceledLookupFinishesLast() = runBlocking {
        val scope = scope(); val ctx = context(); val prefs = Prefs(ctx); val state = WeatherState(Application(), prefs, scope, ScreensaverFeed(ctx))
        val old = CompletableDeferred<Place?>()
        state.geocode = { if (it == "old") withContext(NonCancellable) { old.await() } else Place("New", 2.0, 2.0) }
        state.forecast = { _, _ -> WeatherNow(20, 0, null, null, "C") }
        try {
            state.setWeatherCity("old"); state.setWeatherCity("new")
            old.complete(Place("Old", 1.0, 1.0)); yield()
            assertEquals("New", state.weatherPlaceName); assertEquals("New", Place.decode(prefs.weatherPlace)?.name)
        } finally { scope.cancel() }
    }

    @Test fun lateCelsiusForecastCannotOverwriteNewFahrenheitForecast() = runBlocking {
        val scope = scope(); val ctx = context(); val prefs = Prefs(ctx); val state = WeatherState(Application(), prefs, scope, ScreensaverFeed(ctx))
        prefs.weatherPlace = Place("City", 1.0, 1.0).encode(); val old = CompletableDeferred<WeatherNow>()
        state.forecast = { _, fahrenheit -> if (!fahrenheit) withContext(NonCancellable) { old.await() } else WeatherNow(68, 0, null, null, "F") }
        try {
            state.refreshWeather(true); state.updateFahrenheit(true)
            old.complete(WeatherNow(20, 0, null, null, "C")); yield()
            assertTrue(state.weatherText.orEmpty().contains("68°F"))
        } finally { scope.cancel() }
    }

    @Test fun compactMenusStillFitNarrowWindowsAndExpandForLargeText() {
        assertEquals(288.dp, MenuSizing.profileWidth(960.dp, 1f))
        assertEquals(384.dp, MenuSizing.panelWidth(960.dp, 1f))
        assertEquals(328.dp, MenuSizing.profileWidth(960.dp, 1.3f))
        assertEquals(440.dp, MenuSizing.panelWidth(960.dp, 1.3f))
        assertEquals(240.dp, MenuSizing.panelWidth(240.dp, 1.3f))
        assertEquals(240.dp, MenuSizing.profileWidth(240.dp, 1.3f))
    }
}
