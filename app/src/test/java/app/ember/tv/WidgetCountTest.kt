package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.state.WidgetsState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WidgetCountTest {
    @get:Rule val temp = TemporaryFolder()
    private fun widget(source: Long = 42L, limit: Int = 10) = HomeWidget(id = "one", title = "Films", source = CollectionSource(provider = "trakt", traktListId = source), limit = limit)
    private val titles = (1..30).map { Meta("tt$it", "movie", "Film $it") }

    @Test fun countEditsAndImportedCountsImmediatelyUseTheCachedPage() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        try {
            val state = WidgetsState(Prefs(PersistenceContext(temp.newFolder())), scope, { "main" }, { true },
                page = { calls++; Result.success(CollectionPage(titles, 30, true)) }, onChanged = {}, clock = { 0L })
            assertNull(state.save(widget())); state.refresh { true }; assertEquals(10, state.items["one"]!!.size)
            assertNull(state.save(widget(limit = 20))); state.refresh { true }; assertEquals(20, state.items["one"]!!.size)
            assertNull(state.save(widget(limit = 5))); state.refresh { true }; assertEquals(5, state.items["one"]!!.size)
            state.importAll(listOf(widget(limit = 25))); state.refresh { true }; assertEquals(25, state.items["one"]!!.size)
            assertEquals("Changing a count needs no new network request", 1, calls)
        } finally { scope.cancel() }
    }

    @Test fun aCountChangedDuringLoadingUsesTheLatestValue() {
        for (limit in listOf(5, 20)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val gate = CompletableDeferred<Unit>()
            try {
                val state = WidgetsState(Prefs(PersistenceContext(temp.newFolder())), scope, { "main" }, { true },
                    page = { gate.await(); Result.success(CollectionPage(titles, 30, true)) }, onChanged = {}, clock = { 0L })
                assertNull(state.save(widget())); state.refresh { true }
                assertNull(state.save(widget(limit = limit))); state.refresh { true }; gate.complete(Unit)
                assertEquals(limit, state.items["one"]!!.size); assertTrue(state.loading.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun sourceEditsDuringLoadingDiscardOldResultsAndLoadTheNewSource() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<Long?>()
        try {
            val state = WidgetsState(Prefs(PersistenceContext(temp.newFolder())), scope, { "main" }, { true }, page = { s ->
                calls += s.traktListId; gate.await()
                Result.success(CollectionPage(listOf(Meta("tt${s.traktListId}", "movie", "Film")), 1, true))
            }, onChanged = {}, clock = { 0L })
            assertNull(state.save(widget())); state.refresh { true }
            assertNull(state.save(widget(source = 99))); state.refresh { true }; gate.complete(Unit)
            assertEquals(listOf<Long?>(42L, 99L), calls)
            assertEquals("tt99", state.items["one"]!!.single().id); assertTrue(state.loading.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun deletingAnInFlightWidgetDoesNotResurrectItsCache() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        try {
            val state = WidgetsState(Prefs(PersistenceContext(temp.newFolder())), scope, { "main" }, { true },
                page = { gate.await(); Result.success(CollectionPage(titles, 30, true)) }, onChanged = {}, clock = { 0L })
            assertNull(state.save(widget())); state.refresh { true }
            assertTrue(state.remove("one")); gate.complete(Unit)
            assertTrue(state.list.isEmpty()); assertTrue(state.items.isEmpty()); assertTrue(state.loading.isEmpty())
        } finally { scope.cancel() }
    }
}
