package app.ember.tv

import app.ember.tv.tracker.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

/** WeTrakr's real list names, row shapes and write bodies (#87, #88). */
class WeTrakrApiTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private val ids = buildJsonObject { put("imdb", JsonPrimitive("tt0000001")) }

    @Test fun playingRowsBecomeContinueWatchingWithTheirRemoveBody() {
        val movies = j("""[{"id":11,"title":"Film","year":2020,"ids":{"imdb":"tt0000001","tmdb":5},"poster_path":"/p.jpg",
            "playback":{"status":"paused","progress_percent":42.5,"tracked_at":"2026-01-01T00:00:00Z"}}]""").list()
        val episodes = j("""[{"id":99,"season_number":2,"number":3,"title":"Ep","show":{"id":7,"title":"Show","ids":{"tmdb":1396}},
            "show_ids":{"tmdb":1396,"imdb":"tt0903747"},"playback":{"progress_percent":10}}]""").list()
        val out = weTrakrPlaying(movies, episodes, "WeTrakr")
        val (movie, movieBody) = out[0]
        assertEquals("wetrakr:p:m:11", movie.key)
        assertEquals(0.425f, movie.progress!!, 0.001f)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", movie.poster)
        assertEquals("tt0000001", movieBody.obj("movie").obj("ids").str("imdb"))
        val (ep, epBody) = out[1]
        assertEquals("Show", ep.title)
        assertEquals(2, ep.season); assertEquals(3, ep.episode)
        assertEquals("tt0903747", ep.imdbId)
        assertEquals(3, epBody.obj("episode").int("number"))
    }

    @Test fun watchlistWritesUsePlanningAndEpisodeMarksAreScoped() {
        val movie = weTrakrBody(TitleRef(Kind.MOVIE, null, "tt0000001", "M", null), ids, "planning")
        assertEquals("planning", movie.arr("movies")!!.first().str("status"))
        val ep = weTrakrBody(TitleRef(Kind.SHOW, null, "tt0000001", "S", null), ids, "watched", 1, 4)
        val show = ep.arr("shows")!!.first()
        assertEquals("watched", show.str("status"))
        assertEquals(4, show.arr("seasons")!!.first().arr("episodes")!!.first().int("number"))
    }

    @Test fun unresolvedTitlesInA200AnswerAreNotReportedAsSaved() {
        assertFalse(weTrakrUnresolved(j("""{"added":{"movies":1},"notFound":{"movies":[]},"errored":[]}""")))
        assertTrue(weTrakrUnresolved(j("""{"added":{"movies":0},"notFound":{"movies":[{"ids":{"imdb":"tt0000001"}}]}}""")))
        assertTrue(weTrakrUnresolved(j("""{"errored":[{"message":"status required"}]}""")))
    }

    @Test fun watchedListsBecomeKeysIncludingFinishedShows() {
        val keys = weTrakrWatchedKeys(
            j("""[{"type":"movie","id":1,"ids":{"imdb":"tt0000001"}}]""").list(),
            j("""[{"type":"episode","show_ids":{"tmdb":1396},"season_number":1,"number":2}]""").list(),
            j("""[{"type":"show","ids":{"tmdb":1399}}]""").list(),
        )
        assertEquals(setOf("m:i:tt0000001", "s:t:1396@1.2", "s:t:1399"), keys)
    }

    @Test fun watchStampIgnoresUnrelatedActivity() {
        val a = j("""{"all":"x","movies":{"last_tracking_watched_at":"1","last_tracking_removed_at":"2"},"ratings":{"rated_at":"3"}}""")
        val b = j("""{"all":"y","movies":{"last_tracking_watched_at":"1","last_tracking_removed_at":"2"},"ratings":{"rated_at":"9"}}""")
        assertEquals(weTrakrWatchStamp(a), weTrakrWatchStamp(b))
        assertEquals("z", weTrakrWatchStamp(j("""{"all":"z"}""")))
    }

    @Test fun ratingsReadEitherShape() {
        assertEquals(8, weTrakrRating(j("""{"interactions":{"user":{"rating":{"rating":7.6,"rated_at":"2026"}}}}""")))
        assertEquals(6, weTrakrRating(j("""{"interactions":{"user":{"rating":6}}}""")))
        assertNull(weTrakrRating(j("""{"ids":{}}""")))
    }

    @Test fun scrobblesCarryProgressAndAppVersion() {
        val body = weTrakrScrobbleBody(TrackedItem("k", "S", kind = Kind.SHOW, imdbId = "tt0000001", season = 1, episode = 2), 0.5f, "1.0")!!
        assertEquals(50.0, body.dbl("progress")!!, 0.01)
        assertEquals("1.0", body.str("app_version"))
        assertEquals(2, body.obj("episode").int("number"))
        assertNull("an episode needs its number", weTrakrScrobbleBody(TrackedItem("k", "S", kind = Kind.SHOW, imdbId = "tt0000001"), 0.5f, "1.0"))
    }
}
