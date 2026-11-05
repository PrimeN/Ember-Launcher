package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.discover.*
import app.ember.tv.phone.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CollectionExploreEnhancementsTest {
    private class Source(private val count: Int = 5, private val sparse: Boolean = false): ExploreSource {
        override val configured = true
        val calls = AtomicInteger()
        override suspend fun page(query: ExploreQuery, kind: ExploreKind, region: String, page: Int): ExplorePage {
            calls.incrementAndGet(); delay(5)
            val offset = if (kind == ExploreKind.MOVIE) 0 else 10000
            return ExplorePage((1..20).map { index -> val id = offset + (page - 1) * 20 + index
                ExploreTitle(Meta("tmdb:$id", if (kind == ExploreKind.MOVIE) "movie" else "series", "$id", tmdbId = id.toLong()), date = "2020-01-01", adult = sparse && page < count)
            }, count)
        }
        override suspend fun people(query: ExploreQuery, kind: ExploreKind): List<ExploreTitle>? = null
        override suspend fun matchesExtras(title: ExploreTitle, query: ExploreQuery, kind: ExploreKind, region: String) = true
        override suspend fun awards(ids: List<Long>, query: ExploreQuery) = ids.toSet()
    }
    @Test fun mixedTopHundredHasStablePagesAndReusesTheSession() = runBlocking {
        val source = Source(); val collection = CollectionDrafts.discover("Mix", ExploreQuery(), "p")
        val loader = CollectionExplore(source, { "p" }, { true }, { "CA" })
        val pages = (0..80 step 20).map { loader.page(collection, it) }
        val items = pages.flatMap { it.items }
        assertEquals(100, items.size); assertEquals(100, items.map { it.mediaKey() }.toSet().size)
        assertEquals(50, items.count { it.type == "movie" }); assertEquals(50, items.count { it.type == "series" })
        assertEquals(6, source.calls.get()); assertTrue(pages.last().end)
        assertEquals(pages.first(), loader.page(collection, 0)); assertEquals(6, source.calls.get())
    }
    @Test fun sparseSelectionIsBoundedResumableAndNeverMistakesEmptyWorkForAnEnd() = runBlocking {
        val source = Source(count = 12, sparse = true)
        val collection = CollectionDrafts.discover("Narrow", ExploreQuery(kind = ExploreKind.MOVIE), "p")
        val loader = CollectionExplore(source, { "p" }, { true }, { "CA" })
        val first = loader.page(collection, 0)
        assertTrue(first.items.isEmpty()); assertFalse(first.end); assertNull(first.fingerprint); assertEquals(8, source.calls.get())
        val next = loader.page(collection, 0)
        assertEquals(20, next.items.size); assertTrue(next.end); assertEquals(12, source.calls.get())
    }
    @Test fun concurrentQueriesShareWorkAndProfileAndRegionChangesCannotReuseIt() = runBlocking {
        val source = Source(); var owner = "p"; var region = "CA"; var allowed = true
        val collection = CollectionDrafts.discover("Movies", ExploreQuery(kind = ExploreKind.MOVIE), "p")
        val loader = CollectionExplore(source, { owner }, { allowed }, { region })
        val pages = awaitAll(async { loader.page(collection, 0) }, async { loader.page(collection, 0) })
        assertEquals(pages[0], pages[1]); assertEquals(5, source.calls.get())
        owner = "other"; assertTrue(runCatching { loader.page(collection, 0) }.isFailure)
        owner = "p"; allowed = false; assertTrue(runCatching { loader.page(collection, 0) }.isFailure)
        allowed = true; region = "US"; loader.page(collection, 0); assertEquals(10, source.calls.get())
    }
    @Test fun phoneApprovalsAreExplicitAtMostOnceAndRejectConcurrentProposals() = runBlocking {
        val approvals = ContentApproval(); var writes = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        approvals.stage("Change", { true }) { writes++; entered.complete(Unit); release.await(); null }
        assertEquals(0, writes)
        val saving = async { approvals.approve() }; entered.await()
        assertTrue(approvals.busy); assertTrue(approvals.approve().contains("already"))
        assertTrue(approvals.stage("Another", { true }) { writes++; null }.contains("pending"))
        release.complete(Unit); saving.await(); approvals.approve()
        assertEquals(1, writes); assertNull(approvals.pending); assertFalse(approvals.busy)
    }
    @Test fun phoneExpiryProfileChangeAndRejectNeverWriteContent() = runBlocking {
        var now = 0L; var owner = "p"; var writes = 0
        val lease = PhoneSetupAccess(nowMillis = { now }); val approvals = ContentApproval()
        fun stage() = approvals.stage("Change", { lease.active && owner == "p" }) { writes++; null }
        stage(); now = PhoneSetupAccess.DURATION_MILLIS; approvals.approve(); assertEquals(0, writes)
        now = 0L; stage(); owner = "other"; approvals.approve(); assertEquals(0, writes)
        owner = "p"; stage(); approvals.clear(); approvals.approve(); assertEquals(0, writes)
        assertNull(approvals.pending)
    }
}
