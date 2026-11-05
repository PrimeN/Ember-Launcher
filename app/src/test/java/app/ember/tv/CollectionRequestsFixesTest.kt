package app.ember.tv

import app.ember.tv.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CollectionRequestsFixesTest {
    private class Fixture(val response: (Request) -> Pair<String, Map<String, String>>) {
        val seen = CopyOnWriteArrayList<Request>()
        var status = 200
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); seen += request
            val (body, headers) = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        }.build()
        val requests = CollectionRequests(client) { "fixture-key" }
    }
    private fun traktItems(from: Int, count: Int, type: String = "movie") = JsonArray((from until from + count).map { n ->
        buildJsonObject { put(type, buildJsonObject { put("title", "Title $n"); put("year", 2024); put("ids", buildJsonObject { put("tmdb", n) }) }) }
    }).toString()

    @Test fun networkSourcesSendAllFiltersAsTvFiltersIncludingSortAndRegion() = runBlocking {
        val f = Fixture { """{"results":[{"id":1,"name":"Series"}],"total_pages":3}""" to emptyMap() }
        val source = CollectionSource(provider = "tmdb", tmdbSourceType = "NETWORK", tmdbId = 213, mediaType = "MOVIE",
            sortBy = "primary_release_date.desc", filters = mapOf("year" to JsonPrimitive(2024), "voteAverageGte" to JsonPrimitive(8),
                "releaseDateGte" to JsonPrimitive("2024-01-01"), "withGenres" to JsonPrimitive("16|35"),
                "withOriginalLanguage" to JsonPrimitive("en"), "watchRegion" to JsonPrimitive("AU"), "withWatchProviders" to JsonPrimitive("8")))
        val page = f.requests.page(source, 20); val url = f.seen.single().url
        assertEquals("/3/discover/tv", url.encodedPath); assertEquals("213", url.queryParameter("with_networks"))
        assertEquals("2024", url.queryParameter("first_air_date_year")); assertEquals("2024-01-01", url.queryParameter("first_air_date.gte"))
        assertEquals("first_air_date.desc", url.queryParameter("sort_by")); assertNull(url.queryParameter("primary_release_year"))
        assertEquals("16|35", url.queryParameter("with_genres")); assertEquals("8", url.queryParameter("vote_average.gte"))
        assertEquals("AU", url.queryParameter("watch_region")); assertEquals("8", url.queryParameter("with_watch_providers"))
        assertEquals("en", url.queryParameter("with_original_language")); assertEquals("2", url.queryParameter("page"))
        assertEquals("series", page.items.single().type); assertEquals(40, page.nextSkip); assertFalse(page.end)
    }
    @Test fun movieSourcesKeepMovieDatesAndFilters() = runBlocking {
        val f = Fixture { """{"results":[{"id":1,"title":"Film"}],"total_pages":1}""" to emptyMap() }
        val source = CollectionSource(provider = "tmdb", tmdbSourceType = "COMPANY", tmdbId = 521,
            filters = mapOf("year" to JsonPrimitive(2022), "releaseDateLte" to JsonPrimitive("2022-12-31")))
        val page = f.requests.page(source, 0); val url = f.seen.single().url
        assertEquals("/3/discover/movie", url.encodedPath); assertEquals("521", url.queryParameter("with_companies"))
        assertEquals("2022", url.queryParameter("primary_release_year")); assertEquals("2022-12-31", url.queryParameter("primary_release_date.lte"))
        assertTrue(page.end)
    }
    @Test fun traktListsContinuePastOneHundredAndUseProviderEndMetadata() = runBlocking {
        val f = Fixture { request ->
            val page = request.url.queryParameter("page")!!.toInt()
            traktItems(if (page == 1) 1 else 101, if (page == 1) 100 else 50) to mapOf("X-Pagination-Page-Count" to "2")
        }
        val source = CollectionSource(provider = "trakt", traktListId = 123)
        val first = f.requests.page(source, 0); val second = f.requests.page(source, first.nextSkip)
        assertEquals(150, first.items.size + second.items.size); assertFalse(first.end); assertTrue(second.end)
        assertEquals(listOf("1", "2"), f.seen.map { it.url.queryParameter("page") }); assertEquals(200, second.nextSkip)
        assertEquals("fixture-key", f.seen.single { it.url.queryParameter("page") == "1" }.header("trakt-api-key"))
    }
    @Test fun sparseOrUnmappableProviderPagesStillAdvanceTheirRawCursor() = runBlocking {
        val f = Fixture { request ->
            if (request.url.queryParameter("page") == "1") """[{"movie":{"title":"No IDs"}}]""" to mapOf("X-Pagination-Page-Count" to "2")
            else traktItems(100, 1) to mapOf("X-Pagination-Page-Count" to "2")
        }
        val source = CollectionSource(provider = "trakt", traktListId = 123)
        val first = f.requests.page(source, 0); assertTrue(first.items.isEmpty()); assertFalse(first.end); assertEquals(100, first.nextSkip)
        assertEquals(1, f.requests.page(source, first.nextSkip).items.size)
    }
    @Test fun absentPaginationHeadersDoNotTreatAShortPageAsTheEnd() = runBlocking {
        val f = Fixture { request ->
            (if (request.url.queryParameter("page") == "1") traktItems(1, 2, "show") else "[]") to emptyMap()
        }
        val source = CollectionSource(provider = "trakt", traktListId = 123, mediaType = "TV")
        val first = f.requests.page(source, 0)
        assertFalse(first.end); assertEquals(100, first.nextSkip)
        assertTrue(f.requests.page(source, first.nextSkip).end)
        assertTrue(f.seen.all { "/show/" in it.url.encodedPath })
    }
    @Test fun finiteTmdbCollectionDoesNotRequestASecondPage() = runBlocking {
        val f = Fixture { """{"parts":[{"id":1,"title":"Film"}]}""" to emptyMap() }
        val source = CollectionSource(provider = "tmdb", tmdbSourceType = "COLLECTION", tmdbId = 10)
        assertTrue(f.requests.page(source, 0).end); assertTrue(f.requests.page(source, 20).items.isEmpty())
        assertEquals(1, f.seen.size)
    }
    @Test fun providerFailuresDoNotEchoResponseBodiesOrKeys() = runBlocking {
        val f = Fixture { "password=fixture-secret" to emptyMap() }; f.status = 401
        val error = runCatching { f.requests.page(CollectionSource(provider = "trakt", traktListId = 123), 0) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("fixture-secret")); assertFalse(error.message!!.contains("fixture-key"))
    }
}
