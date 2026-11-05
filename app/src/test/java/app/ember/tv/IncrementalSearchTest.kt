package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.SearchState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IncrementalSearchTest {
    private fun catalog(id: String) = CatalogDef(
        AddonInfo("https://$id.example", "https://$id.example", id, listOf("movie"), null, emptyList(), emptyList()),
        "movie", id, id, emptyList(), false, true, true, false,
    )

    @Test fun fastProviderAppearsBeforeSlowProviderAndFinalOrderIsStable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val slow = CompletableDeferred<Unit>()
        val first = catalog("first"); val second = catalog("second")
        try {
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { listOf(first, second) }
            state.browse = { def, _ -> if (def == first) slow.await(); listOf(Meta(id = def.id, name = def.name)) }
            state.setQuery("film", immediate = true)
            yield()
            assertTrue(state.searching)
            assertEquals(listOf(second.key), state.searchRows.map { it.catalogKey })
            slow.complete(Unit); yield()
            assertEquals(listOf(first.key, second.key), state.searchRows.map { it.catalogKey })
            assertFalse(state.searching)
        } finally { slow.complete(Unit); scope.cancel() }
    }

    @Test fun delayedOldQueryCannotPublishIntoNewQuery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = CompletableDeferred<Unit>()
        try {
            val def = catalog("one")
            val state = SearchState(scope, AddonClient(), { emptyList() }, { emptyList() }) { listOf(def) }
            state.browse = { _, term ->
                if (term == "old") withContext(NonCancellable) { old.await() }
                listOf(Meta(id = term, name = term))
            }
            state.setQuery("old", immediate = true); yield()
            state.setQuery("new", immediate = true); yield()
            old.complete(Unit); yield()
            assertEquals(listOf("new"), state.searchRows.flatMap { it.items }.map { it.id })
        } finally { old.complete(Unit); scope.cancel() }
    }
}
