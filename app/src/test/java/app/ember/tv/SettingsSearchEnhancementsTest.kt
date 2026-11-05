package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.*
import app.ember.tv.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchEnhancementsTest {
    private fun catalog(id: String) = CatalogDef(
        AddonInfo("https://$id.example", "https://$id.example", id, listOf("movie"), null, emptyList(), emptyList()),
        "movie", id, id, emptyList(), false, true, true, false,
    )
    @Test fun exactSettingsMatchesResolveToExistingLazyDestinations() {
        mapOf("frosted glass" to "artwork", "density" to "density", "lip sync" to "audio-timing", "Atmos" to "audio-output").forEach { (query, id) ->
            val hit = SettingDefinitions.matching(query).single()
            assertEquals(id, hit.id)
            val keys = when (hit.tab) {
                SettingsTab.PLAYER -> PLAYER_KEYS
                SettingsTab.AUDIO_VIDEO -> AUDIO_VIDEO_KEYS
                else -> lookKeys(hit.tab, "modern")
            }
            assertTrue(query, hit.id in keys)
        }
        assertEquals(appearanceKeys("modern"), appearanceKeys("legacy"))
        assertTrue("theme" in appearanceKeys("light"))
        assertEquals(SettingDefinitions.all.size, SettingDefinitions.all.map { it.id }.distinct().size)
    }
    @Test fun aSectionSearchRequestIsClaimedOnceEvenWhenItsLazyItemIsRecreated() {
        val target = SettingsTarget("density")
        assertTrue(target.claim())
        assertFalse(target.claim())
        assertTrue(SettingsTarget("density").claim())
    }
    @Test fun localNameRankingPrefersExactThenPrefixMatches() {
        val names = listOf("The Film", "Film sequel", "Film")
        assertEquals(listOf("Film", "Film sequel", "The Film"), names.sortedBy { SearchPresentation.rank(it, " film ") })
    }
    @Test fun filtersKeepAppsSeparateAndIncludeAnimeInShows() {
        assertTrue(SearchPresentation.accepts(Meta(type = "anime"), SearchFilter.SHOWS))
        assertFalse(SearchPresentation.accepts(Meta(type = "movie"), SearchFilter.SHOWS))
        assertTrue(SearchPresentation.accepts(Meta(type = "movie"), SearchFilter.MOVIES))
        val rows = listOf(CatalogRow("A", "Movies", listOf(Meta(type = "movie"))))
        assertTrue(SearchPresentation.groups(rows, SearchFilter.APPS, false).isEmpty())
        assertTrue(SearchPresentation.groups(rows, SearchFilter.WATCHLIST, false).isEmpty())
    }
    @Test fun combiningKnownCopiesRetainsProviderNamesAndDoesNotMergeNamesAlone() {
        val movie = Meta(id = "tt123", type = "movie", name = "Same name")
        val unknown = Meta(id = "opaque", type = "movie", name = "Same name")
        val rows = listOf(CatalogRow("A", "A", listOf(movie, unknown), catalogKey = "a"),
            CatalogRow("B", "B", listOf(movie.copy(id = "other", imdbId = "tt123"), unknown.copy(id = "different")), catalogKey = "b"))
        assertEquals(4, SearchPresentation.groups(rows, SearchFilter.ALL, false).sumOf { it.items.size })
        val combined = SearchPresentation.groups(rows, SearchFilter.ALL, true)
        assertEquals(3, combined.sumOf { it.items.size })
        assertEquals("Sources: A, B", combined.first().items.first().badge)
        assertEquals(listOf("a", "b"), combined.map { it.catalogKey })
    }
    @Test fun duplicateIdentitySeparatesMoviesShowsAndEpisodesIncludingIdOnlyEpisodes() {
        val show = Meta(id = "tt123", type = "series")
        assertNotEquals(SearchPresentation.identity(show), SearchPresentation.identity(show.copy(type = "movie")))
        assertNotEquals(SearchPresentation.identity(show.copy(season = 1, episode = 1)), SearchPresentation.identity(show.copy(season = 1, episode = 2)))
        assertNotEquals(SearchPresentation.identity(show), SearchPresentation.identity(show.copy(id = "tt123:1:1")))
        assertNotEquals(SearchPresentation.identity(show.copy(id = "tt123:1:1")), SearchPresentation.identity(show.copy(id = "tt123:1:2")))
        assertNull(SearchPresentation.identity(Meta(id = "provider-only", name = "Same name")))
    }
    @Test fun recentQueriesAreBoundedCaseInsensitiveAndTrimmed() {
        val old = List(12) { "Query $it" }
        val updated = RecentSearches.updated(old, "  QUERY 5  ")
        assertEquals(10, updated.size)
        assertEquals("QUERY 5", updated.first())
        assertEquals(1, updated.count { it.equals("query 5", true) })
        assertEquals(old, RecentSearches.updated(old, "x"))
        assertEquals(200, RecentSearches.updated(emptyList(), "a".repeat(300)).single().length)
    }
    @Test fun retryWaitsForWorkingSearchInsteadOfCancelingItsResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val slow = CompletableDeferred<Unit>()
        val failed = catalog("failed"); val working = catalog("working")
        var failedCalls = 0
        try {
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { listOf(failed, working) }
            state.browse = { def, _ ->
                if (def == failed && failedCalls++ == 0) error("Unavailable")
                if (def == working) slow.await()
                listOf(Meta(id = def.id))
            }
            state.setQuery("film", immediate = true); yield()
            state.retry(failed.key); yield()
            assertEquals(1, failedCalls)
            slow.complete(Unit); yield()
            assertEquals(2, failedCalls)
            assertEquals(listOf(failed.key, working.key), state.searchRows.map { it.catalogKey })
        } finally { slow.complete(Unit); scope.cancel() }
    }
    @Test fun canceledManifestCannotPublishOldDefinitionsOrHealthIntoNewQuery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<Unit>()
        var calls = 0
        val stale = catalog("stale"); val fresh = catalog("fresh")
        try {
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { emptyList() }
            state.providers = { listOf(SearchProvider("manifest:one", "One") {
                if (++calls == 1) { withContext(NonCancellable) { old.await() }; listOf(stale) } else listOf(fresh)
            }) }
            state.browse = { def, _ -> listOf(Meta(id = def.id)) }
            state.setQuery("old", immediate = true); yield()
            state.setQuery("new", immediate = true); yield()
            old.complete(Unit); yield()
            assertEquals(listOf(fresh.key), state.searchRows.map { it.catalogKey })
            assertFalse(stale.key in state.health.entries)
        } finally { old.complete(Unit); scope.cancel() }
    }
}
