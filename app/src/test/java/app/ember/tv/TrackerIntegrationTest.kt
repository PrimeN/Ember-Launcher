package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.data.WatchedIndex
import app.ember.tv.data.WatchedKeys
import app.ember.tv.tracker.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

/** Tracker sync and writes: #78 to #86. */
class TrackerIntegrationTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test fun simklUrlsCarryTheRequiredAppParameters() {
        val url = simklUrl("https://api.simkl.com/", "sync/playback?hide_watched=true", "abc", "1.2 beta")
        assertEquals("https://api.simkl.com/sync/playback?hide_watched=true&client_id=abc&app-name=ember-tv&app-version=1.2+beta", url)
        assertTrue(simklUrl("https://api.simkl.com/", "sync/activities", "abc", "1").contains("/sync/activities?client_id=abc&"))
    }

    @Test fun simklLibraryOnlyDownloadsWhatChanged() = runBlocking {
        var clock = 0L
        val calls = mutableListOf<String>()
        var activities = """{"all":"2026-01-01T00:00:00Z","movies":{"removed_from_list":"a"}}"""
        val responses = mutableMapOf<String, String>()
        val lib = SimklLibrary(null, { path ->
            calls += path
            j(when {
                path == "sync/activities" -> activities
                path.contains("date_from") -> responses.getValue("delta")
                else -> responses.getValue("full")
            })
        }, now = { clock })
        responses["full"] = """{"movies":[{"status":"plantowatch","movie":{"title":"A","ids":{"simkl":1}}}]}"""
        assertEquals(1, lib.entries().arr("movies")!!.size)
        assertEquals(listOf("sync/activities", SimklLibrary.ALL_ITEMS), calls)

        // Within a minute: nothing is asked.
        calls.clear(); clock += 30_000
        lib.entries()
        assertTrue(calls.isEmpty())

        // Unchanged activities: only the activities call.
        clock += 60_000
        lib.entries()
        assertEquals(listOf("sync/activities"), calls)

        // Changed: only the delta since the saved timestamp, merged by Simkl id.
        calls.clear(); clock += 60_000
        activities = """{"all":"2026-02-01T00:00:00Z","movies":{"removed_from_list":"a"}}"""
        responses["delta"] = """{"movies":[{"status":"completed","movie":{"title":"A","ids":{"simkl":1}}},{"status":"plantowatch","movie":{"title":"B","ids":{"simkl":2}}}]}"""
        val merged = lib.entries().arr("movies")!!
        assertEquals(2, calls.size)
        assertTrue(calls[1].contains("date_from=2026-01-01T00%3A00%3A00Z"))
        assertEquals(listOf("completed", "plantowatch"), merged.map { it.str("status") })

        // Something removed from a list: a full pull, since a delta can't say what went away.
        calls.clear(); clock += 60_000
        activities = """{"all":"2026-03-01T00:00:00Z","movies":{"removed_from_list":"b"}}"""
        lib.entries()
        assertEquals(listOf("sync/activities", SimklLibrary.ALL_ITEMS), calls)
    }

    @Test fun watchedKeysMatchEmbersOwnKeys() {
        val movie = Meta(id = "tt0111161", type = "movie", name = "M", imdbId = "tt0111161", tmdbId = 278)
        assertTrue(WatchedKeys.keys(movie).containsAll(watchedKeys(true, "tt0111161", 278, null, null)))
        val ep = Meta(id = "tt0903747", type = "series", name = "S", imdbId = "tt0903747", tmdbId = 1396, season = 2, episode = 3)
        assertTrue(WatchedKeys.keys(ep).containsAll(watchedKeys(false, "tt0903747", 1396, 2, 3)))
        assertEquals(emptyList<String>(), watchedKeys(false, "nope", null, 1, 1))
    }

    @Test fun trackerMarksShowUnlessYouMarkedItYourself() {
        val movie = Meta(id = "tt0111161", type = "movie", name = "M", imdbId = "tt0111161")
        val remote = watchedKeys(true, "tt0111161", null, null, null).toSet()
        assertFalse(WatchedIndex().watched(movie))
        assertTrue(WatchedIndex().watched(movie, remote))
        assertFalse("your own unwatched mark wins", WatchedIndex().changed(movie, false).watched(movie, remote))
    }

    @Test fun traktAndSimklWatchedListsBecomeKeys() {
        val trakt = traktWatchedKeys(
            j("""[{"movie":{"ids":{"imdb":"tt1","tmdb":5}}}]""".replace("tt1", "tt0000001")),
            j("""[{"show":{"ids":{"imdb":"tt0000002","tmdb":7}},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}]"""),
        )
        assertTrue(trakt.containsAll(listOf("m:i:tt0000001", "m:t:5", "s:i:tt0000002@1.1", "s:t:7@1.2")))
        val simkl = simklWatchedKeys(j("""{
            "movies":[{"status":"completed","movie":{"ids":{"imdb":"tt0000003"}}},{"status":"plantowatch","movie":{"ids":{"imdb":"tt0000004"}}}],
            "shows":[{"status":"completed","show":{"ids":{"tmdb":9}}},{"status":"watching","show":{"ids":{"tmdb":10}},"seasons":[{"number":2,"episodes":[{"number":4}]}]}]
        }"""))
        assertEquals(setOf("m:i:tt0000003", "s:t:9", "s:t:10@2.4"), simkl)
    }

    @Test fun historyBodiesCoverMoviesEpisodesAndWholeShows() {
        val ids = kotlinx.serialization.json.buildJsonObject { put("imdb", kotlinx.serialization.json.JsonPrimitive("tt0000001")) }
        val movie = historyBody(TitleRef(Kind.MOVIE, null, "tt0000001", "M", null), null, null, ids)
        assertNotNull(movie.arr("movies"))
        val ep = historyBody(TitleRef(Kind.SHOW, null, "tt0000001", "S", null), 3, 4, ids)
        val season = ep.arr("shows")!!.first().arr("seasons")!!.first()
        assertEquals(3, season.int("number")); assertEquals(4, season.arr("episodes")!!.first().int("number"))
        val show = historyBody(TitleRef(Kind.ANIME, null, "tt0000001", "A", null), null, null, ids)
        assertNull(show.arr("shows")!!.first().arr("seasons"))
    }

    private class Fake(override val id: String, val refuse: Boolean = false) : Tracker {
        override val name = id
        override val blurb = ""
        override val mode = LinkMode.USERNAME
        override val configured = true
        override val scrobbles = true
        val scrobbled = mutableListOf<ScrobbleState>()
        override fun isConnected() = true
        override fun accountLabel(): String? = null
        override fun disconnect() {}
        override suspend fun continueWatching() = emptyList<TrackedItem>()
        override suspend fun watchlist() = emptyList<TrackedItem>()
        override fun supportsWatchlist(ref: TitleRef, add: Boolean) = true
        override suspend fun addToWatchlist(ref: TitleRef): Boolean = if (refuse) throw TrackerRefusal(id, "$id limit reached") else true
        override suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) { scrobbled += state }
    }

    @Test fun aRefusedWatchlistChangeIsNotRetried() = runBlocking {
        val hub = TrackerHub(listOf(Fake("a"), Fake("b", refuse = true)))
        val ref = TitleRef(Kind.MOVIE, 1, null, "M", null)
        val r = hub.writeWatchlist(ref, true, setOf("a", "b"))
        assertEquals(setOf("a"), r.succeeded.keys)
        assertEquals(mapOf("b" to "b limit reached"), r.refused)
        assertTrue(r.pending.isEmpty())
    }

    @Test fun hidingATrackerFromHomeStillReportsButQuietDoesNot() = runBlocking {
        val home = Fake("home"); val quiet = Fake("quiet")
        val hub = TrackerHub(listOf(home, quiet))
        hub.disabled = setOf("home")
        hub.quiet = setOf("quiet")
        hub.scrobble(TrackedItem("k", "T", imdbId = "tt0000001"), ScrobbleState.STOP, 1f)
        assertEquals(listOf(ScrobbleState.STOP), home.scrobbled)
        assertTrue(quiet.scrobbled.isEmpty())
    }

    @Test fun kitsuIdsComeFromTheMappingResponse() {
        assertEquals(42L, kitsuIdFrom(j("""{"data":[{"relationships":{"item":{"data":{"type":"anime","id":"42"}}}}],"included":[{"type":"anime","id":"42"}]}""")))
        assertEquals(7L, kitsuIdFrom(j("""{"data":[{"relationships":{"item":{"data":{"type":"anime","id":"7"}}}}]}""")))
        assertNull(kitsuIdFrom(j("""{"data":[]}""")))
    }

    @Test fun aniListProgressOnlyMovesForward() = runBlocking {
        assertEquals(10L to 5, aniListTarget("kitsu:99:5", null) { if (it == 99L) 10L else null })
        assertEquals(10L to 3, aniListTarget("kitsu:99:1:3", null) { 10L })
        assertEquals(12L to 2, aniListTarget("anilist:12", 2) { null })
        assertNull(aniListTarget("tt0000001:1:2", 2) { 1L })
        assertEquals(5 to "CURRENT", aniListProgressUpdate(4, "CURRENT", 12, 5))
        assertNull("rewatching an earlier episode doesn't lower progress", aniListProgressUpdate(6, "CURRENT", 12, 5))
        assertEquals(12 to "COMPLETED", aniListProgressUpdate(11, "CURRENT", 12, 12))
        assertEquals(1 to "CURRENT", aniListProgressUpdate(0, null, null, 1))
    }
}
