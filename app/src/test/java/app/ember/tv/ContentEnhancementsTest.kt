package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.discover.*
import app.ember.tv.phone.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ContentEnhancementsTest {
    @After fun reset() { AddonAccess.paused = emptySet() }
    @Test fun configuredUrlsMigrateOnlyAfterVerifiedEncryptedStorageAndPreserveQueryCredentials() {
        val configured = listOf("https://example.com/config/manifest.json?token=fixture-private")
        var legacy = true; var encrypted: String? = null; var available = false
        val store = AddonConfiguration({ encrypted }, { if (available) { encrypted = it; true } else false }, { configured }, { legacy }, { legacy = false })
        assertEquals(configured, store.load()); assertTrue(legacy); assertNull(encrypted)
        available = true
        assertEquals(configured, store.load()); assertFalse(legacy)
        assertEquals(configured, store.load())
        assertTrue(store.save(emptyList())); assertTrue(store.load().isEmpty())
    }
    @Test fun failedReadbackCannotEraseLegacyConfiguration() {
        var legacy = true
        val store = AddonConfiguration({ null }, { true }, { listOf("private fixture URL") }, { legacy }, { legacy = false })
        assertEquals(listOf("private fixture URL"), store.load()); assertTrue(legacy)
        assertFalse(store.save(emptyList())); assertTrue(legacy)
    }
    @Test fun sensitiveCatalogArgumentsAreSealedAndResolvedWithoutPlaintextJsonCopies() {
        val source = CollectionSource(addonId = "a", catalogId = "c", type = "movie",
            extraArgs = mapOf("language" to "en", "api_key" to "fixture-private", "password" to "fixture-password"))
        val pack = listOf(MediaCollection("c", "Collection", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(source)))))
        val private = mutableMapOf<String, String>()
        val sealed = CollectionSecrets.seal(pack) { key, value -> private[key] = value; true }
        val text = Collections.encode(sealed)
        assertFalse(text.contains("fixture-private")); assertFalse(text.contains("fixture-password"))
        val stored = Collections.parse(text)!!.single().folders.single().allSources.single()
        assertEquals(mapOf("language" to "en"), stored.extraArgs)
        assertEquals(source.extraArgs, CollectionSecrets.resolve(stored) { private[it] })
        assertTrue(runCatching { CollectionSecrets.resolve(stored) { null } }.isFailure)
        assertTrue(runCatching { CollectionSecrets.seal(pack) { _, _ -> false } }.isFailure)
        assertEquals("fixture-private", source.extraArgs["api_key"])
    }
    @Test fun discoverQueriesRetainAwardsPeopleAndOwnershipWithoutLossyTmdbConversion() {
        val query = ExploreQuery(kind = ExploreKind.MOVIE, oscars = OscarMode.WIN, bestPicture = true,
            actors = listOf(ExploreRef(31, "Actor")), minRating = 7.0, fromYear = 2000)
        val source = CollectionDrafts.discover("Award winners", query, "profile-one")
        val text = Collections.encode(listOf(MediaCollection("c", "Collection", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(source))))))
        val imported = Collections.parse(text)!!.single().folders.single().allSources.single()
        assertEquals(query, imported.exploreQuery); assertEquals("profile-one", imported.ownerProfile)
        assertNotNull(Collections.inspect(text.replace("profile-one", "")).error)
    }
    @Test fun sourceIdentityDisambiguatesConfiguredInstancesWithoutPersistingTheirUrls() {
        fun def(url: String) = CatalogDef(AddonInfo(url, url, "Provider", emptyList(), null, emptyList(), emptyList(), manifestId = "same"), "movie", "top", "Top", emptyList(), false, false, false, false)
        val first = def("https://example.com/private-one/manifest.json"); val other = def("https://example.com/private-two/manifest.json")
        val source = CollectionDrafts.catalog(first)
        assertTrue(Collections.matches(first, source)); assertFalse(Collections.matches(other, source))
        assertFalse(source.toString().contains("private-one"))
    }
    @Test fun manifestDefaultsFiltersPagingAndConfiguredQueryUrlsAreHonored() = runBlocking {
        val seen = CopyOnWriteArrayList<Request>()
        val manifest = """{"id":"fixture","name":"Fixture","types":["movie"],"catalogs":[{"id":"top","type":"movie","extra":[{"name":"language","isRequired":true,"options":["en","fr"],"default":"en"},{"name":"skip"}]}]}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (chain.request().url.encodedPath.endsWith("manifest.json")) manifest else """{"metas":[{"id":"tt1234567","type":"movie","name":"Title"}]}""").toResponseBody()).build()
        }.build()
        val client = AddonClient(http)
        val info = client.manifest("https://example.com/config/manifest.json?token=fixture")
        val catalog = client.catalogsOf(info).single()
        assertTrue(catalog.homeable); assertTrue(catalog.browsable); assertTrue(catalog.supportsSkip)
        assertEquals("en", catalog.defaultExtras["language"])
        client.browse(catalog, skip = 40, extras = mapOf("language" to "fr"))
        assertTrue(seen.last().url.encodedPath.contains("language=fr")); assertTrue(seen.last().url.encodedPath.contains("skip=40"))
        assertTrue(seen.all { it.url.queryParameter("token") == "fixture" })
        assertEquals("fixture/", client.baseOf("https://example.com/manifest.json?token=fixture/").toHttpUrl().queryParameter("token"))
        assertEquals("https://example.com/", client.baseOf("https://example.com/manifest.json"))
        assertTrue(seen.first().url.encodedPath.endsWith("/config/manifest.json"))
        assertNotNull(catalog.extraError(mapOf("language" to "unsupported")))
        val noDefault = catalog.copy(extras = catalog.extras.map { it.copy(default = null) })
        assertFalse(noDefault.homeable); assertTrue(noDefault.browsable); assertNotNull(noDefault.extraError(emptyMap()))
    }
    @Test fun pausingRejectsEveryFutureAddonResourceWithoutAnotherNetworkCall() = runBlocking {
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor { chain -> calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"fixture","name":"Fixture","catalogs":[{"id":"top","type":"movie"}]}""".toResponseBody()).build()
        }.build()
        val client = AddonClient(http); val url = "https://example.com/manifest.json"
        val info = client.manifest(url); val catalog = client.catalogsOf(info).single()
        AddonAccess.paused = setOf(AddonAccess.key(url)); AddonClient.cancel(url)
        assertTrue(runCatching { client.manifest(url) }.isFailure)
        assertTrue(runCatching { client.browse(catalog) }.isFailure)
        assertTrue(runCatching { client.meta(info, "movie", "x") }.isFailure)
        assertTrue(runCatching { client.streams(info, "movie", "x") }.isFailure)
        assertTrue(runCatching { client.subtitles(info, "movie", "x") }.isFailure)
        assertEquals(1, calls); assertNotNull(client.cachedManifest(url))
        AddonAccess.paused = emptySet(); assertEquals(info, client.manifest(url)); assertEquals(1, calls)
    }
    @Test fun phoneContentEscapesLabelsAndPostsOnlyOpaqueReferencesWithTheSessionCode() {
        val html = ContentPhone.render(PhoneContent(listOf("source-hash" to "<script>private</script>"), listOf("c-hash" to "Name\" onclick=evil"), listOf("addon-hash" to "Provider & name")), "session-code")
        assertFalse(html.contains("<script>private</script>")); assertTrue(html.contains("&lt;script&gt;")); assertTrue(html.contains("&quot;"))
        assertTrue(html.contains("/content?k=session-code")); assertTrue(html.contains("source-hash"))
        assertTrue(html.contains("Approve")); assertFalse(html.contains("manifest.json"))
    }
    @Test fun idInputsAcceptProviderUrlsAndDraftMovesRetainSourceConfiguration() {
        assertEquals(123L, CollectionDrafts.numericId("https://trakt.tv/lists/123-title"))
        assertEquals(420L, CollectionDrafts.numericId("https://www.themoviedb.org/company/420-studio"))
        assertNull(CollectionDrafts.numericId("-1")); assertNull(CollectionDrafts.numericId("not an id"))
        val source = CollectionSource(provider = "trakt", traktListId = 1)
        assertEquals(listOf(source.copy(traktListId = 2), source), CollectionDrafts.move(listOf(source, source.copy(traktListId = 2)), 0, 1))
    }
    @Test fun encryptedAddonBackupsReportTheCorrectSourceCount() {
        val data = buildJsonObject { put("format", Backup.FORMAT); put("version", 1); put("encrypted", false)
            put("data", buildJsonObject { put("prefs.ember", buildJsonObject {}); put("files", buildJsonObject {}); put("tokens", buildJsonObject { put("addons.config", "[\"one\",\"two\"]") }) }) }
        assertEquals(2, Backup.read(data.toString().toByteArray(), "").second.addons)
    }

    @Test fun pausingCancelsAnAlreadyRunningRequestAndPreventsLateManifestPublication() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"fixture","name":"Fixture"}""".toResponseBody()).build()
        }.build()
        val client = AddonClient(http); val url = "https://example.com/manifest.json"
        val result = async(Dispatchers.Default) { runCatching { client.manifest(url) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            AddonAccess.paused = setOf(AddonAccess.key(url)); AddonClient.cancel(url); release.countDown()
            assertTrue(withTimeout(5000) { result.await() }.isFailure); assertNull(client.cachedManifest(url))
        } finally { release.countDown() }
    }
}
