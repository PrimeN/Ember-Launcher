package app.ember.tv

import app.ember.tv.data.AddonClient
import app.ember.tv.data.Backup
import app.ember.tv.data.LocalLibrary
import app.ember.tv.data.Meta
import app.ember.tv.player.SubtitleAutoSelection
import app.ember.tv.state.DiscoverState
import app.ember.tv.state.SearchState
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BugFixRegressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun movieAndTvIdsBelongToDifferentNamespaces() {
        val movie = Meta(id = "movie:100", type = "movie", name = "Movie", tmdbId = 100)
        val show = Meta(id = "show:100", type = "series", name = "Show", tmdbId = 100)
        assertFalse(movie.sameTitle(show))
        assertTrue(movie.sameTitle(movie.copy(id = "another-provider")))
        assertTrue(show.sameTitle(show.copy(type = "anime")))
        assertFalse(movie.sameTitle(show.copy(id = movie.id, name = movie.name)))
    }

    @Test fun profileResetClearsSavedMatchesAndRejectsLateSearchResults() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val search = SearchState(scope, AddonClient(), { emptyList() }, {
                listOf(Meta(id = "adult", name = "Adult title"))
            }, {
                withContext(NonCancellable) { gate.await() }
                emptyList()
            })
            search.setQuery("Adult", immediate = true)
            assertEquals(1, search.searchSaved.size)
            search.requestVoice()
            search.reset()
            assertTrue(search.searchSaved.isEmpty())
            assertTrue(search.searchApps.isEmpty())
            assertTrue(search.searchRows.isEmpty())
            assertFalse(search.voicePending)
            gate.complete(Unit)
            yield()
            assertNull(search.searchNote)
            assertFalse(search.searching)
            assertEquals("", search.searchQuery)
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun discoverResetRejectsOldCatalogLoad() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val discover = DiscoverState(scope, AddonClient()) {
                withContext(NonCancellable) { gate.await() }
                emptyList()
            }
            discover.openDiscover()
            assertTrue(discover.discoverLoading)
            discover.reset()
            gate.complete(Unit)
            yield()
            assertFalse(discover.discoverLoading)
            assertNull(discover.discoverNote)
            assertTrue(discover.discoverItems.isEmpty())
        } finally { gate.complete(Unit); scope.cancel() }
    }

    @Test fun aFailedRestoreWriteReportsABackupErrorAndLeavesNoPartialFile() {
        val dir = temp.newFolder()
        java.io.File(dir, "library.json").writeText("old library")
        // A directory in the way makes the replacement fail, like a full disk or a read-only file.
        java.io.File(dir, "collections.json").mkdirs()
        java.io.File(dir, "collections.json/keep.txt").writeText("x")
        val error = runCatching {
            Backup.restoreDataFiles(dir, buildJsonObject { put("collections.json", "new"); put("library.json", "new library") })
        }.exceptionOrNull()
        assertTrue(error is Backup.BackupException)
        assertTrue(java.io.File(dir, "collections.json").isDirectory)
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test fun restoredFilesAreWrittenCompletely() {
        val dir = temp.newFolder()
        val big = "x".repeat(256 * 1024)
        Backup.restoreDataFiles(dir, buildJsonObject { put("library.json", big) })
        assertEquals(big, java.io.File(dir, "library.json").readText())
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test fun restoringOmittedFilesRemovesOldDataButLeavesOtherFilesAlone() {
        val dir = temp.newFolder()
        listOf("collections.json", "badges-custom.json", "library.json", "unrelated.txt").forEach {
            java.io.File(dir, it).writeText("old")
        }
        Backup.restoreDataFiles(dir, buildJsonObject {
            put("collections.json", "new")
            put("../outside.txt", "must not be restored")
        })
        assertEquals("new", java.io.File(dir, "collections.json").readText())
        assertFalse(java.io.File(dir, "badges-custom.json").exists())
        assertFalse(java.io.File(dir, "library.json").exists())
        assertEquals("old", java.io.File(dir, "unrelated.txt").readText())
        assertFalse(java.io.File(dir.parentFile, "outside.txt").exists())
        Backup.restoreDataFiles(dir, JsonObject(emptyMap()))
        assertFalse(java.io.File(dir, "collections.json").exists())
    }

    @Test fun aPartialTrackerFailureRetriesOnlyTheFailedService() = runBlocking {
        val first = FakeTracker("first")
        val second = FakeTracker("second").apply { accept = false }
        val unsupported = FakeTracker("unsupported").apply { supported = false }
        val hub = TrackerHub(listOf(first, second, unsupported))
        val ref = TitleRef(Kind.MOVIE, 100, "tt100", "Movie", 2020)
        val result = hub.writeWatchlist(ref, true, hub.watchlistTargets(ref, true))
        assertEquals(setOf("first"), result.succeeded.keys)
        assertEquals(setOf("second"), result.pending)
        second.connected = false
        assertEquals(setOf("second"), hub.writeWatchlist(ref, true, result.pending).pending)
        second.connected = true
        second.accept = true
        assertTrue(hub.writeWatchlist(ref, true, result.pending).pending.isEmpty())
        assertEquals(1, first.writes)
        assertEquals(2, second.writes)
        assertEquals(0, unsupported.writes)
    }

    @Test fun staleSyncCompletionCannotDeleteANewerWatchlistChange() {
        LocalLibrary.resetForTest()
        val meta = Meta(id = "tt100", type = "movie", name = "Movie")
        LocalLibrary.queue(true, meta, setOf("first", "second"))
        val old = LocalLibrary.pending.single()
        LocalLibrary.remaining(old, setOf("second"))
        assertEquals(setOf("second"), LocalLibrary.pending.single().targets)
        val retry = LocalLibrary.pending.single()
        LocalLibrary.queue(false, meta, setOf("first", "second"))
        LocalLibrary.done(retry)
        assertFalse(LocalLibrary.pending.single().add)
        assertEquals(setOf("first", "second"), LocalLibrary.pending.single().targets)
        LocalLibrary.resetForTest()
    }

    @Test fun concurrentAndDelayed401sRenewOnlyOnceAcrossCallers() = runBlocking {
        val service = java.util.UUID.randomUUID().toString()
        var access: String? = "expired"
        var renewals = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun retry() = TokenRefreshCoordinator.refresh(service, "expired", { access }) {
            renewals++
            entered.complete(Unit)
            release.await()
            access = "renewed"
            true
        }
        val first = async { retry() }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { retry() }
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(second.await())
        assertTrue(retry()) // a delayed 401 for the old token must not rotate again
        assertEquals(1, renewals)
        access = null // signing out must not resurrect the previous token
        assertFalse(retry())
        assertEquals(1, renewals)
    }

    @Test fun manualSubtitlesWinBeforeSearchOrPlaybackBecomesReady() {
        for ((searched, ready) in listOf(false to true, true to false, false to false)) {
            val selection = SubtitleAutoSelection()
            assertFalse(selection.claim(searched, ready))
            selection.manual()
            assertFalse(selection.claim(true, true))
            selection.reset()
            assertTrue(selection.claim(true, true))
            assertFalse(selection.claim(true, true))
        }
    }

    private class FakeTracker(override val id: String) : Tracker {
        override val name = id
        override val blurb = ""
        override val mode = LinkMode.DEVICE_CODE
        override val configured = true
        override val scrobbles = false
        var connected = true
        var supported = true
        var accept = true
        var writes = 0
        override fun isConnected() = connected
        override fun accountLabel(): String? = null
        override fun disconnect() { connected = false }
        override suspend fun continueWatching() = emptyList<TrackedItem>()
        override suspend fun watchlist() = emptyList<TrackedItem>()
        override fun supportsWatchlist(ref: TitleRef, add: Boolean) = supported
        override suspend fun addToWatchlist(ref: TitleRef): Boolean { writes++; return accept }
        override suspend fun removeFromWatchlist(ref: TitleRef): Boolean { writes++; return accept }
    }
}
