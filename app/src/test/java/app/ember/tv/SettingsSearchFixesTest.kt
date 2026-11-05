package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.*
import app.ember.tv.ui.SETTINGS_INDEX
import app.ember.tv.ui.SettingsTab
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchFixesTest {
    private fun catalog(id: String) = CatalogDef(
        AddonInfo("https://$id.example", "https://$id.example", id, listOf("movie"), null, emptyList(), emptyList()),
        "movie", id, id, emptyList(), false, true, true, false,
    )
    @Test fun audioDisplaysExactValuesAndAdjustsFromTheSavedValue() {
        assertEquals("Sound 50 ms later", AudioTiming.label(50))
        assertEquals("Sound 800 ms later", AudioTiming.label(800))
        assertEquals("Sound 50 ms earlier", AudioTiming.label(-50))
        assertEquals(100, AudioTiming.adjust(50, 1))
        assertEquals(750, AudioTiming.adjust(800, -1))
        assertEquals(-1000, AudioTiming.adjust(-990, -1))
        assertEquals(1000, AudioTiming.adjust(990, 1))
    }
    @Test fun appearanceIndexIncludesTheNewVisibleControls() {
        val look = setOf(SettingsTab.APPEARANCE, SettingsTab.SIZE, SettingsTab.PERFORMANCE, SettingsTab.REMOTE)
        val appearance = SETTINGS_INDEX.filter { it.first in look }.joinToString(" ") { it.second }.lowercase()
        listOf("frosted", "glass", "density", "economy", "balanced", "artwork quality").forEach { assertTrue(it, it in appearance) }
    }
    @Test fun everyCatalogIsSearchedWithAtMostFourConcurrentRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var active = 0; var peak = 0; var calls = 0
        try {
            val defs = List(13) { catalog("provider$it") }
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { defs }
            state.browse = { def, _ ->
                active++; peak = maxOf(peak, active); calls++
                try { gate.await(); listOf(Meta(id = def.id)) } finally { active-- }
            }
            state.setQuery("film", immediate = true); yield()
            assertEquals(4, active)
            gate.complete(Unit); yield()
            assertEquals(13, calls)
            assertEquals(13, state.searchRows.size)
            assertEquals(4, peak)
        } finally { gate.complete(Unit); scope.cancel() }
    }
    @Test fun fastManifestCanSearchWhileSlowDiscoveryWaits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val slow = CompletableDeferred<Unit>()
        val first = catalog("first"); val second = catalog("second")
        try {
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { emptyList() }
            state.providers = { listOf(
                SearchProvider("manifest:${first.addon.url}", "First") { slow.await(); listOf(first) },
                SearchProvider("manifest:${second.addon.url}", "Second") { listOf(second) },
            ) }
            state.browse = { def, _ -> listOf(Meta(id = def.id)) }
            state.setQuery("film", immediate = true); yield()
            assertEquals(listOf(second.key), state.searchRows.map { it.catalogKey })
            assertTrue(state.searching)
            slow.complete(Unit); yield()
            assertEquals(listOf(first.key, second.key), state.searchRows.map { it.catalogKey })
        } finally { slow.complete(Unit); scope.cancel() }
    }
    @Test fun failedDiscoveryHasTargetedRetryWithoutRefetchingWorkingSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = true; var workingCalls = 0
        val a = catalog("a"); val b = catalog("b")
        try {
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { emptyList() }
            val failedId = "manifest:${a.addon.url}"
            state.providers = { listOf(
                SearchProvider(failedId, "A") { if (fail) error("Unavailable"); listOf(a) },
                SearchProvider("manifest:${b.addon.url}", "B") { workingCalls++; listOf(b) },
            ) }
            state.browse = { def, _ -> listOf(Meta(id = def.id)) }
            state.setQuery("film", immediate = true); yield()
            assertTrue(state.health.entries.getValue(failedId).failed)
            assertEquals(listOf(b.key), state.searchRows.map { it.catalogKey })
            fail = false; state.retry(failedId); yield()
            assertFalse(state.health.entries.getValue(failedId).failed)
            assertEquals(listOf(a.key, b.key), state.searchRows.map { it.catalogKey })
            assertEquals(1, workingCalls)
        } finally { scope.cancel() }
    }
}
