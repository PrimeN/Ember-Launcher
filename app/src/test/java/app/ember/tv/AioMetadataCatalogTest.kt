package app.ember.tv

import app.ember.tv.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Catalogs shaped like AIOMetadata's: genre required with "None" (all) first, Collections asking for "None". */
class AioMetadataCatalogTest {
    private val manifest = """{"id":"aio-metadata","name":"AIOMetadata","version":"3.8.1","types":["movie","all"],"resources":["catalog"],"catalogs":[
        {"id":"mdblist.158800","type":"movie","name":"[Genres] Action (Movies)","extra":[{"name":"genre","isRequired":true,"options":["None","Action","Comedy"]},{"name":"skip"}]},
        {"id":"trakt.list.-10","type":"all","name":"Watchlist","extra":[{"name":"genre","options":["Action","Comedy"]},{"name":"skip"}]}]}"""
    private val seen = mutableListOf<HttpUrl>()
    private val client = AddonClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); seen += request.url
        val body = if (request.url.encodedPath.endsWith("manifest.json")) manifest
            else """{"metas":[{"id":"tt1","type":"movie","name":"Film"}]}"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }.build())
    private val url = "https://aio.example.test/stremio/fixture/manifest.json"

    @Test fun requiredGenreUsesItsFirstOptionAndCollectionsMayAskForNone() = runBlocking {
        val catalogs = client.catalogsOf(client.manifest(url))
        val action = catalogs.first { it.id == "mdblist.158800" }
        assertFalse(action.homeable) // Home rows keep needing a declared default
        assertEquals(1, client.browse(action).size)
        assertTrue(seen.last().encodedPath.endsWith("/catalog/movie/mdblist.158800/genre=None.json"))
        client.browse(action, genre = "Comedy")
        assertTrue(seen.last().encodedPath.endsWith("/genre=Comedy.json"))
        // A required value the manifest does not list is the addon's to judge.
        client.browse(action, genre = "Westerns")
        assertTrue(seen.last().encodedPath.endsWith("/genre=Westerns.json"))
    }

    @Test fun anOptionalGenreTheCatalogDoesNotOfferIsLeftOut() = runBlocking {
        val watchlist = client.catalogsOf(client.manifest(url)).first { it.id == "trakt.list.-10" }
        assertEquals(1, client.browse(watchlist, genre = "None").size)
        assertTrue(seen.last().encodedPath.endsWith("/catalog/all/trakt.list.-10.json"))
    }

    @Test fun aCollectionSourceLoadsAndAMissingCatalogIsNamed() = runBlocking {
        val defs = client.catalogsOf(client.manifest(url))
        val source = CollectionSource(provider = "addon", addonId = "aio-metadata", type = "movie", catalogId = "mdblist.158800", genre = "None")
        assertEquals(1, Collections.page(source, defs, client).getOrThrow().items.size)
        val missing = source.copy(catalogId = "mdblist.158630", title = "Apple TV",
            extensionFields = mapOf("catalogName" to JsonPrimitive("[Streaming Services] Apple TV+ (Movies)"), "addonName" to JsonPrimitive("AIOMetadata")))
        val error = Collections.page(missing, defs, client).exceptionOrNull()?.message.orEmpty()
        assertTrue(error, error.contains("[Streaming Services] Apple TV+ (Movies)") && error.contains("AIOMetadata"))
    }
}
