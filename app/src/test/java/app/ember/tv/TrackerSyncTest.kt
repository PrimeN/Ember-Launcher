package app.ember.tv

import app.ember.tv.tracker.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Two-way tracker sync (#90) and the offline queue (#89). */
class TrackerSyncTest {
    private fun movie(imdb: String? = null, tmdb: Long? = null) = SyncItem.of(true, imdb, tmdb)!!
    private fun ep(tmdb: Long, s: Int, e: Int, imdb: String? = null) = SyncItem.of(false, imdb, tmdb, s, e)!!
    private val a = movie("tt0000001", 1)
    private val b = movie("tt0000002")
    private val c = movie(tmdb = 3)

    @Test fun theSameTitleMatchesAcrossIdKinds() {
        assertTrue(movie(tmdb = 1) in SyncIndex(listOf(movie(imdb = "tt0000001", tmdb = 1))))
        assertFalse(movie(tmdb = 1) in SyncIndex(listOf(SyncItem.of(false, null, 1)!!)))
        assertEquals(1, listOf(movie(imdb = "tt0000001"), movie(imdb = "tt0000001", tmdb = 1)).distinctTitles().size)
        assertEquals(a, SyncItem.decode(a.encode()))
        assertEquals(ep(5, 1, 2), SyncItem.decode(ep(5, 1, 2).encode()))
    }

    @Test fun backFillCopiesEverythingEverywhereAndRemovesNothing() {
        val plan = SyncMerge.plan(mapOf("trakt" to listOf(a, b), "simkl" to listOf(c)), emptyMap())
        assertEquals(setOf(c), plan.adds["trakt"]!!.toSet())
        assertEquals(setOf(a, b), plan.adds["simkl"]!!.toSet())
        assertTrue(plan.removes.isEmpty())
    }

    @Test fun additionsAndRemovalsOnOneTrackerReachTheOthers() {
        val before = mapOf("trakt" to listOf(a, b), "simkl" to listOf(a, b), "wetrakr" to listOf(a, b))
        // On Trakt: b removed, c added.
        val plan = SyncMerge.plan(mapOf("trakt" to listOf(a, c), "simkl" to listOf(a, b), "wetrakr" to listOf(a, b)), before)
        assertEquals(listOf(c), plan.adds["simkl"]); assertEquals(listOf(c), plan.adds["wetrakr"])
        assertEquals(listOf(b), plan.removes["simkl"]); assertEquals(listOf(b), plan.removes["wetrakr"])
        assertNull(plan.adds["trakt"]); assertNull(plan.removes["trakt"])
    }

    @Test fun anAdditionBeatsARemovalOfTheSameTitle() {
        val before = mapOf("trakt" to listOf(a), "simkl" to emptyList())
        val plan = SyncMerge.plan(mapOf("trakt" to emptyList(), "simkl" to listOf(a)), before)
        assertEquals(listOf(a), plan.adds["trakt"])
        assertTrue(plan.removes.isEmpty())
    }

    @Test fun aSuddenLargeDropIsHeldBackUnlessAllowed() {
        val many = (1L..100L).map { movie(tmdb = it) }
        val before = mapOf("trakt" to many, "simkl" to many)
        val plan = SyncMerge.plan(mapOf("trakt" to emptyList(), "simkl" to many), before)
        assertEquals(mapOf("trakt" to 100), plan.blocked)
        assertTrue(plan.removes.isEmpty())
        assertTrue("a held drop must not copy the other side back either", plan.adds.isEmpty())
        val allowed = SyncMerge.plan(mapOf("trakt" to emptyList(), "simkl" to many), before, allowMassRemoval = true)
        assertEquals(100, allowed.removes["simkl"]!!.size)
    }

    @Test fun trackersThatCantRemoveAreLeftAlone() {
        val before = mapOf("trakt" to listOf(a), "simkl" to listOf(a))
        val plan = SyncMerge.plan(mapOf("trakt" to emptyList(), "simkl" to listOf(a)), before, acceptsRemovals = setOf("trakt"))
        assertTrue(plan.removes.isEmpty())
        assertTrue("Simkl keeping it is not an addition", plan.adds.isEmpty())
    }

    @Test fun ratingsCopyChangesClearRemovalsAndBackFillOnlyFillsGaps() {
        val fill = SyncMerge.ratings(mapOf("trakt" to mapOf(a to 8), "simkl" to mapOf(a to 6, b to 7)), emptyMap())
        assertEquals(mapOf(b to 7), fill.set["trakt"])
        assertNull("back-fill never overwrites", fill.set["simkl"])
        val before = mapOf("trakt" to mapOf(a to 8, b to 7), "simkl" to mapOf(a to 8, b to 7))
        val change = SyncMerge.ratings(mapOf("trakt" to mapOf(a to 9), "simkl" to mapOf(a to 8, b to 7)), before)
        assertEquals(mapOf(a to 9), change.set["simkl"])
        assertEquals(listOf(b), change.clear["simkl"])
    }

    @Test fun batchesGroupEpisodesUnderTheirShow() {
        val body = syncBatch(listOf(a, ep(10, 1, 1), ep(10, 1, 2), ep(10, 2, 1)),
            extra = { mapOf("status" to kotlinx.serialization.json.JsonPrimitive("watched")) })
        assertEquals("watched", body.arr("movies")!!.first().str("status"))
        val show = body.arr("shows")!!.single()
        val seasons = show.arr("seasons")!!
        assertEquals(listOf(1, 2), seasons.map { it.int("number") })
        assertEquals(listOf(1, 2), seasons.first().arr("episodes")!!.map { it.int("number") })
    }

    @Test fun snapshotsAndTheQueueSurviveARestart() {
        val dir = java.nio.file.Files.createTempDirectory("tracker-sync").toFile()
        try {
            val snaps = SyncSnapshots(dir)
            snaps.writeItems(SyncCategory.WATCHED, mapOf("trakt" to listOf(a, ep(10, 1, 2))))
            snaps.writeRatings(mapOf("simkl" to mapOf(b to 7)))
            assertEquals(listOf(a, ep(10, 1, 2)), SyncSnapshots(dir).readItems(SyncCategory.WATCHED)["trakt"])
            assertEquals(mapOf(b to 7), SyncSnapshots(dir).readRatings()["simkl"])
            snaps.forget("trakt")
            assertNull(snaps.readItems(SyncCategory.WATCHED)["trakt"])

            TrackerQueue.init(dir); TrackerQueue.clear()
            TrackerQueue.add(TrackerQueue.Op("watched", a, null, setOf("trakt", "simkl")))
            TrackerQueue.add(TrackerQueue.Op("unwatched", a, null, setOf("trakt")))
            val ops = TrackerQueue.all()
            assertEquals("the newer mark replaces the older one", listOf("unwatched"), ops.map { it.type })
            TrackerQueue.update(ops.single(), emptySet())
            assertTrue(TrackerQueue.all().isEmpty())
        } finally { dir.deleteRecursively() }
    }

    private class Fake(override val id: String, var watched: MutableList<SyncItem>, var list: MutableList<SyncItem> = mutableListOf(),
                       val canRemove: Boolean = true) : Tracker {
        override val name = id
        override val blurb = ""
        override val mode = LinkMode.USERNAME
        override val configured = true
        override val scrobbles = false
        override val syncs = true
        override val removesFromWatchlist get() = canRemove
        override fun isConnected() = true
        override fun accountLabel(): String? = null
        override fun disconnect() {}
        override suspend fun continueWatching() = emptyList<TrackedItem>()
        override suspend fun watchlist() = emptyList<TrackedItem>()
        override suspend fun syncWatched(): List<SyncItem> = watched.toList()
        override suspend fun syncWatchlist(): List<SyncItem> = list.toList()
        override suspend fun syncRatings(): Map<SyncItem, Int> = emptyMap()
        override suspend fun applySync(category: SyncCategory, add: List<SyncItem>, remove: List<SyncItem>) {
            val target = if (category == SyncCategory.WATCHED) watched else list
            target += add; target.removeAll { r -> remove.any { it.encode() == r.encode() } }
        }
    }

    @Test fun anEngineRoundSyncsTrackersAndEmbersWatchlist() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("tracker-sync").toFile()
        try {
            val trakt = Fake("trakt", mutableListOf(a, ep(10, 1, 1)), mutableListOf(b))
            val simkl = Fake("simkl", mutableListOf(c), canRemove = false)
            val engine = TrackerSyncEngine(SyncSnapshots(dir))
            var localRemoved = emptyList<SyncItem>()
            fun local(list: List<SyncItem>) = TrackerSyncEngine.Local("ember:p", list, {}, { localRemoved = it })
            engine.run(listOf(trakt, simkl), local(listOf(b, c)), false)
            assertEquals(setOf(a, ep(10, 1, 1), c), trakt.watched.toSet())
            assertEquals(setOf(a, ep(10, 1, 1), c), simkl.watched.toSet())
            assertEquals("Ember's own watchlist is copied out", setOf(b, c), trakt.list.toSet())
            assertEquals("never onto a watchlist where it's already watched", setOf(b), simkl.list.toSet())

            // b leaves the watchlist on Trakt. What Ember copied to Simkl last round doesn't count as
            // a new addition, so the removal stands: Ember drops b too; Simkl can't, so it isn't asked.
            trakt.list.remove(b)
            engine.run(listOf(trakt, simkl), local(listOf(b, c)), false)
            assertEquals(listOf(b), localRemoved)
            assertFalse("b is not copied back to Trakt", b in SyncIndex(trakt.list))

            // A watched episode removed on Simkl is removed on Trakt.
            simkl.watched.remove(ep(10, 1, 1))
            engine.run(listOf(trakt, simkl), local(listOf(c)), false)
            assertFalse(ep(10, 1, 1) in SyncIndex(trakt.watched))
            assertTrue("a later round with no changes changes nothing", a in SyncIndex(trakt.watched))
        } finally { dir.deleteRecursively() }
    }
}
