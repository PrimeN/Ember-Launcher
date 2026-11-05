package app.ember.tv

import app.ember.tv.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Addon stream headers reach the player; TMDB folders ignore limits saved as 0. */
class StreamHeadersAndTmdbLimitsTest {
    private fun parse(json: String) = StreamParser.parse("Live addon", Json.parseToJsonElement(json))

    @Test fun anAddonsProxyHeadersAreKeptWithTheStream() {
        val stream = parse("""{"url":"https://live.example.test/ch1/index.m3u8","behaviorHints":{"notWebReady":true,
            "proxyHeaders":{"request":{"User-Agent":"Mozilla/5.0 (Fixture)","Referer":"https://site.example.test/","Origin":"https://site.example.test"},
            "response":{"Content-Type":"application/vnd.apple.mpegurl"}}}}""")!!
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0 (Fixture)", "Referer" to "https://site.example.test/", "Origin" to "https://site.example.test"), stream.headers)
        assertEquals("Mozilla/5.0 (Fixture)", StreamHeaders.userAgent(stream.headers))
        assertEquals(setOf("Referer", "Origin"), StreamHeaders.withoutUserAgent(stream.headers).keys)
        assertTrue(parse("""{"url":"https://example.test/v.mkv"}""")!!.headers.isEmpty())
        // A torrent has no web request to send them with.
        assertTrue(parse("""{"infoHash":"0123456789abcdef","behaviorHints":{"proxyHeaders":{"request":{"Referer":"x"}}}}""")!!.headers.isEmpty())
    }

    @Test fun unsafeOrReservedHeadersAreDropped() {
        val kept = StreamHeaders.clean(listOf(
            "Referer" to "https://ok.example.test/", "Host" to "evil.example.test", "Range" to "bytes=0-",
            "Content-Length" to "1", "Bad Name" to "x", "X-Inject" to "a\r\nSet-Cookie: b", "X-Empty" to " ",
            "referer" to "https://duplicate.example.test/", "Cookie" to "session=1"))
        assertEquals(mapOf("Referer" to "https://ok.example.test/", "Cookie" to "session=1"), kept)
        assertEquals(16, StreamHeaders.clean((1..30).map { "X-H$it" to "v" }).size)
    }

    @Test fun limitsSavedAsZeroAreNotSentToTmdb() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"results":[{"id":1,"title":"Film"}],"total_pages":1}""".toResponseBody()).build()
        }.build()
        val requests = CollectionRequests(client) { "fixture-key" }
        val source = CollectionSource(provider = "tmdb", tmdbSourceType = "DISCOVER", mediaType = "MOVIE", sortBy = "popularity.desc",
            filters = mapOf("voteAverageGte" to JsonPrimitive(0), "voteAverageLte" to JsonPrimitive(0), "voteCountGte" to JsonPrimitive(20),
                "year" to JsonPrimitive(0), "releaseDateLte" to JsonPrimitive("0"), "withGenres" to JsonPrimitive("28"), "withKeywords" to JsonNull))
        val page = requests.page(source, 0)
        val url = seen.single().url
        assertNull(url.queryParameter("vote_average.lte")); assertNull(url.queryParameter("vote_average.gte"))
        assertNull(url.queryParameter("primary_release_year")); assertNull(url.queryParameter("primary_release_date.lte"))
        assertNull(url.queryParameter("with_keywords"))
        assertEquals("20", url.queryParameter("vote_count.gte")); assertEquals("28", url.queryParameter("with_genres"))
        assertEquals(1, page.items.size)
    }
}
