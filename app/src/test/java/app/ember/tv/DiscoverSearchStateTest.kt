package app.ember.tv

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import app.ember.tv.data.*
import app.ember.tv.state.*
import app.ember.tv.ui.SETTINGS_INDEX
import app.ember.tv.ui.SettingsLocation
import app.ember.tv.ui.SettingsTab
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class DiscoverSearchStateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun catalog(id: String) = CatalogDef(
        AddonInfo("https://$id.example", "https://$id.example", id, listOf("movie"), null, emptyList(), emptyList()),
        "movie", id, id, emptyList(), false, true, true, false)

    private suspend fun search(defs: List<CatalogDef>, saved: List<Meta> = emptyList(),
        apps: List<AppEntry> = emptyList(), test: suspend (SearchState) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { test(SearchState(scope, AddonClient(), { apps }, { saved }) { defs }) }
        finally { scope.cancel() }
    }

    @Test fun workingResultsHideFailuresFromOtherCatalogs() = runBlocking {
        val good = catalog("good"); val bad = catalog("bad")
        search(listOf(good, bad)) { state ->
            state.browse = { def, _ -> if (def == bad) throw IOException() else listOf(Meta(id = "tt1", name = "Film")) }
            state.setQuery("film", immediate = true); yield()
            assertTrue(state.health.entries.values.any { it.failed })
            assertEquals(1, state.searchRows.size)
            assertNull(state.searchNote); assertFalse(state.sourceUnavailable)
        }
    }

    @Test fun healthySourceWithNoMatchesDoesNotBecomeUnavailableBecauseAnotherFails() = runBlocking {
        val good = catalog("good"); val bad = catalog("bad")
        search(listOf(good, bad)) { state ->
            state.browse = { def, _ -> if (def == bad) throw IOException() else emptyList() }
            state.setQuery("film", immediate = true); yield()
            assertFalse(state.sourceUnavailable)
            assertTrue(state.searchNote!!.startsWith("No matches"))
        }
    }

    @Test fun localWatchlistResultsSuppressMissingAddonSetup() = runBlocking {
        search(emptyList(), saved = listOf(Meta(id = "tt1", name = "Film saved"))) { state ->
            state.setQuery("film", immediate = true); yield()
            assertEquals(1, state.searchSaved.size)
            assertNull(state.searchNote); assertFalse(state.sourceUnavailable)
        }
    }

    @Test fun localAppResultsSuppressAllRemoteFailures() = runBlocking {
        val def = catalog("bad")
        search(listOf(def), apps = listOf(AppEntry("test.pkg", "Film app", Intent(), ColorDrawable()))) { state ->
            state.browse = { _, _ -> throw IOException() }
            state.setQuery("film", immediate = true); yield()
            assertEquals(1, state.searchApps.size)
            assertNull(state.searchNote); assertFalse(state.sourceUnavailable)
        }
    }

    @Test fun allSourcesFailingWithNoResultsOfferRecovery() = runBlocking {
        search(listOf(catalog("bad"))) { state ->
            state.browse = { _, _ -> throw IOException() }
            state.setQuery("film", immediate = true); yield()
            assertTrue(state.sourceUnavailable)
            assertTrue(state.searchNote!!.contains("Retry"))
        }
    }

    @Test fun noConfiguredSourcesAndNoLocalMatchesOfferSetup() = runBlocking {
        search(emptyList()) { state ->
            state.setQuery("film", immediate = true); yield()
            assertTrue(state.sourceUnavailable)
            assertTrue(state.searchNote!!.contains("Add a searchable catalog"))
            state.setQuery("")
            assertFalse(state.sourceUnavailable); assertNull(state.searchNote)
        }
    }

    @Test fun cachedMatchesRemainQuietWhenTheirRetryFails() = runBlocking {
        val def = catalog("one")
        search(listOf(def)) { state ->
            state.browse = { _, _ -> listOf(Meta(id = "tt1", name = "Film")) }
            state.setQuery("film", immediate = true); yield()
            state.browse = { _, _ -> throw IOException() }
            state.retry(def.key); yield()
            assertEquals(1, state.searchRows.single().items.size)
            assertTrue(state.health.entries.getValue(def.key).failed)
            assertNull(state.searchNote); assertFalse(state.sourceUnavailable)
        }
    }

    @Test fun healthyEmptyCatalogSurvivesAnotherManifestsFailure() = runBlocking {
        val def = catalog("good")
        search(emptyList()) { state ->
            state.providers = { listOf(SearchProvider("manifest:${def.addon.url}", "Good") { listOf(def) },
                SearchProvider("manifest:bad", "Bad") { throw IOException() }) }
            state.browse = { _, _ -> emptyList() }
            state.setQuery("film", immediate = true); yield()
            assertFalse(state.sourceUnavailable)
            assertTrue(state.searchNote!!.startsWith("No matches"))
        }
    }

    @Test fun aNewQueryDoesNotReusePreviousHealthySourceStatusAfterFailure() = runBlocking {
        search(listOf(catalog("one"))) { state ->
            state.browse = { _, _ -> emptyList() }
            state.setQuery("first", immediate = true); yield()
            assertFalse(state.sourceUnavailable)
            state.browse = { _, _ -> throw IOException() }
            state.setQuery("second", immediate = true); yield()
            assertTrue(state.sourceUnavailable)
        }
    }

    @Test fun pendingSourceDoesNotPublishAnUnavailableBannerPrematurely() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        try {
            val good = catalog("good"); val bad = catalog("bad")
            search(listOf(good, bad)) { state ->
                state.browse = { def, _ -> if (def == bad) throw IOException() else { gate.await(); emptyList() } }
                state.setQuery("film", immediate = true); yield()
                assertTrue(state.searching); assertFalse(state.sourceUnavailable); assertNull(state.searchNote)
                gate.complete(Unit); yield()
                assertFalse(state.searching); assertFalse(state.sourceUnavailable)
            }
        } finally { gate.complete(Unit) }
    }

    @Test fun freshDiscoverShowsOnlyExploreAndContentEnablesAutomaticSections() {
        assertEquals(listOf(DiscoverSection.EXPLORE), DiscoverVisibility.visible(emptyMap(), false, false, false))
        assertEquals(DiscoverSection.entries.toList(), DiscoverVisibility.visible(emptyMap(), true, true, false))
    }

    @Test fun manualOffPersistsWhenContentArrivesAndManualOnAllowsEmptySetup() {
        val choices = mapOf("CATALOGS" to false, "COLLECTIONS" to true)
        assertEquals(listOf(DiscoverSection.EXPLORE, DiscoverSection.COLLECTIONS), DiscoverVisibility.visible(choices, true, false, false))
        assertEquals(listOf(DiscoverSection.EXPLORE), DiscoverVisibility.visible(emptyMap(), false, false, false))
    }

    @Test fun hiddenOrRemovedSectionFallsBackWithoutReEnablingAnOffSection() {
        assertEquals(DiscoverSection.EXPLORE, DiscoverVisibility.selected(DiscoverSection.COLLECTIONS, listOf(DiscoverSection.EXPLORE)))
        assertEquals(DiscoverSection.CATALOGS, DiscoverVisibility.selected(DiscoverSection.CATALOGS, DiscoverSection.entries.toList()))
        val allOff = DiscoverSection.entries.associate { it.name to false }
        assertNull(DiscoverVisibility.selected(DiscoverSection.EXPLORE, DiscoverVisibility.visible(allOff, true, true, false)))
    }

    @Test fun kidsNeverGainExploreOrCollectionsThroughVisibilityOverrides() {
        assertEquals(listOf(DiscoverSection.CATALOGS), DiscoverVisibility.visible(DiscoverSection.entries.associate { it.name to true }, true, true, true))
        assertTrue(DiscoverVisibility.visible(mapOf("CATALOGS" to false), true, true, true).isEmpty())
    }

    @Test fun discoverChoicesSurviveReloadAndBackupRestore() {
        val from = PersistenceContext(temp.newFolder()); val choices = mapOf("EXPLORE" to false, "CATALOGS" to false, "COLLECTIONS" to true)
        Prefs(from).discoverSections = choices
        assertEquals(choices, Prefs(from).discoverSections)
        val payload = BackupPersistence(from, FixtureSecrets()).snapshot()
        val to = PersistenceContext(temp.newFolder())
        BackupPersistence(to, FixtureSecrets()).restore(payload)
        assertEquals(choices, Prefs(to).discoverSections)
        assertEquals(listOf(DiscoverSection.COLLECTIONS), DiscoverVisibility.visible(Prefs(to).discoverSections, true, false, false))
    }

    @Test fun malformedDiscoverBackupIsRejectedBeforeChangingPreferences() {
        val from = PersistenceContext(temp.newFolder())
        val valid = BackupPersistence(from, FixtureSecrets()).snapshot()
        val payload = JsonObject(valid + ("prefs.ember" to JsonObject(valid.getValue("prefs.ember").jsonObject +
            ("discoverSections" to buildJsonObject { put("t", "s"); put("v", """{"EXPLORE":"yes"}""") }))))
        val to = PersistenceContext(temp.newFolder()); Prefs(to).discoverSections = mapOf("CATALOGS" to false)
        assertTrue(runCatching { BackupPersistence(to, FixtureSecrets()).restore(payload) }.isFailure)
        assertEquals(mapOf("CATALOGS" to false), Prefs(to).discoverSections)
    }

    @Test fun discoverDefinitionsCanLoadWithoutBrowsingAnUnusedCatalog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var browses = 0
            val state = DiscoverState(scope, AddonClient()) { listOf(catalog("one").copy(searchOnly = false)) }
            state.browse = { _, _, _ -> browses++; listOf(Meta(id = "tt1")) }
            state.openDiscover(loadItems = false); yield()
            assertEquals(1, state.discoverCatalogs.size); assertEquals(0, browses)
            state.openDiscover(); yield()
            assertEquals(1, browses); assertEquals(1, state.discoverItems.size)
            state.openDiscover(); assertEquals(1, browses)
        } finally { scope.cancel() }
    }

    @Test fun selectingCatalogsWhileDefinitionsLoadStillLoadsItsFirstPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val gate = CompletableDeferred<Unit>()
        try {
            var browses = 0
            val state = DiscoverState(scope, AddonClient()) { gate.await(); listOf(catalog("one").copy(searchOnly = false)) }
            state.browse = { _, _, _ -> browses++; emptyList() }
            state.openDiscover(loadItems = false); state.openDiscover(); gate.complete(Unit); yield()
            assertEquals(1, browses); assertTrue(state.discoverEnd)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun settingsSearchCanReachDiscoverPreferences() {
        assertEquals(SettingsTab.DISCOVER, SettingsLocation.jump("DISCOVER")!!.page)
        val words = SETTINGS_INDEX.single { it.first == SettingsTab.DISCOVER }.second.lowercase()
        listOf("explore", "catalogs", "collections", "hide", "automatic").forEach { assertTrue(it in words) }
    }

    @Test fun seeAllOpensItsCatalogEvenWhenTheDefaultDiscoverListIsEmpty() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var definitions = 0; var browses = 0
            val state = DiscoverState(scope, AddonClient()) { definitions++; emptyList() }
            state.browse = { _, _, _ -> browses++; listOf(Meta(id = "tt1")) }
            val wanted = catalog("specific").copy(searchOnly = false)
            state.show(wanted); yield()
            assertEquals(1, definitions); assertEquals(1, browses)
            assertEquals(wanted.key, state.discoverCatalog!!.key)
            assertEquals(1, state.discoverItems.size)
        } finally { scope.cancel() }
    }
}
