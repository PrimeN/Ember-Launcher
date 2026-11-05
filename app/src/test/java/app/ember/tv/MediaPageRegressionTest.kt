package app.ember.tv

import app.ember.tv.state.MediaVisit
import app.ember.tv.ui.DetailsBringIntoView
import app.ember.tv.ui.sourceRowKeys
import app.ember.tv.ui.mediaSourcePresentation
import app.ember.tv.data.Meta
import app.ember.tv.data.StreamParser
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class MediaPageRegressionTest {
    @Test fun emptySourceMessageWaitsForEveryAvailabilityLookup() {
        val empty = DetailsUi(Meta(id = "tt1", type = "movie", name = "Film"))
        listOf(empty.copy(loadingStreams = true), empty.copy(loadingServer = true),
            empty.copy(loadingOffers = true), empty.copy(loadingEpisodes = true)).forEach { pending ->
            assertEquals("Checking streaming options…", mediaSourcePresentation(pending, 0).message)
        }
        assertEquals("No streaming option available for this title.", mediaSourcePresentation(empty, 0).message)
        assertEquals(mediaSourcePresentation(empty, 0).message,
            mediaSourcePresentation(empty.copy(note = "A provider did not respond"), 0).message)
    }

    @Test fun anyEnabledProviderResultSuppressesOtherProvidersEmptyOrLoadingMessages() {
        val empty = DetailsUi(Meta(id = "tt1", type = "movie", name = "Film"), note = "No addon results")
        val stream = source("a")
        listOf(empty.copy(streams = listOf(stream)), empty.copy(serverStreams = listOf(stream)),
            empty.copy(streams = listOf(stream), loadingServer = true, loadingOffers = true)).forEach { result ->
            assertNull(mediaSourcePresentation(result, 0).message)
        }
        assertNull(mediaSourcePresentation(empty.copy(loadingStreams = true), 1).message)
        assertEquals(3, mediaSourcePresentation(empty.copy(streams = listOf(stream), serverStreams = listOf(stream)), 1).total)
    }

    @Test fun visibleHeroButtonsDoNotScrollTheTitleOutOfView() {
        assertEquals(0f, DetailsBringIntoView.calculateScrollDistance(300f, 48f, 450f), 0f)
        assertEquals(0f, DetailsBringIntoView.calculateScrollDistance(0f, 48f, 450f), 0f)
        assertEquals(0f, DetailsBringIntoView.calculateScrollDistance(402f, 48f, 450f), 0f)
    }

    @Test fun offscreenControlsScrollOnlyEnoughToBecomeVisible() {
        assertEquals(-20f, DetailsBringIntoView.calculateScrollDistance(-20f, 48f, 450f), 0f)
        assertEquals(28f, DetailsBringIntoView.calculateScrollDistance(430f, 48f, 450f), 0f)
        assertEquals(0f, DetailsBringIntoView.calculateScrollDistance(-40f, 600f, 450f), 0f)
        assertEquals(30f, DetailsBringIntoView.calculateScrollDistance(30f, 600f, 450f), 0f)
    }

    @Test fun aClosedOrReopenedTitleCannotReceiveLateMetadataFromItsPreviousVisit() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val visit = MediaVisit(parent)
        val gate = CompletableDeferred<Unit>()
        var shown = "old title"
        try {
            val old = visit.scope.launch {
                withContext(NonCancellable) { gate.await() }
                ensureActive()
                shown = "stale metadata"
            }
            visit.cancel()
            visit.reset()
            visit.scope.launch { shown = "new visit of same title" }.join()
            gate.complete(Unit)
            old.join()
            assertEquals("new visit of same title", shown)
        } finally { gate.complete(Unit); parent.cancel() }
    }

    @Test fun disposingParentCancelsAllTitleMetadataJobs() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val visit = MediaVisit(parent)
        val jobs = List(3) { visit.scope.launch { awaitCancellation() } }
        parent.cancel()
        jobs.forEach { it.join(); assertTrue(it.isCancelled) }
    }

    private fun source(id: String) = StreamParser.parse("Provider", Json.parseToJsonElement(
        """{"name":"1080p","title":"$id","url":"https://example.com/$id?token=private"}""",
    ))!!

    @Test fun insertingAndReorderingStreamsPreservesEachExistingFocusKey() {
        val a = source("a"); val b = source("b"); val c = source("c")
        val before = sourceRowKeys(listOf(a, b))
        val after = sourceRowKeys(listOf(c, b, a))
        assertEquals(before[0], after[2])
        assertEquals(before[1], after[1])
        assertFalse(after.any { it.contains("private") || it.contains("example.com") })
    }

    @Test fun duplicateProviderStreamsHaveUniqueKeysAndVolatileStatsDoNotChangeIdentity() {
        val a = source("a")
        val keys = sourceRowKeys(listOf(a, a))
        assertEquals(2, keys.toSet().size)
        assertEquals(sourceRowKeys(listOf(a)), sourceRowKeys(listOf(a.copy(seeders = 99, cached = !a.cached))))
    }
}
