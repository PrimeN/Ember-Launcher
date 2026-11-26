package app.ember.tv

import android.app.Application
import app.ember.tv.data.*
import app.ember.tv.state.CollectionsState
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #459: collections AIOMetadata serves as catalogs of type "collection". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AddonCollectionsTest {
    private val addon = AddonInfo("https://aio.example/x/manifest.json", "https://aio.example/x", "AIOMetadata", listOf("movie", "collection"), null,
        emptyList(), emptyList(), manifestId = "aio-metadata")
    private fun def(id: String = "aiom.collection.5f7f", type: String = "collection", name: String = "Discover") =
        CatalogDef(addon, type, id, name, listOf("None"), true, false, false, false)
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val entries = json("""[
      {"id":"aiom.folder.watchlist","type":"collection","name":"Watchlist","poster":"https://img/watch.png","posterShape":"landscape",
       "collection":{"sources":[{"type":"all","catalogId":"trakt.watchlist","genre":"None","name":"[Discover] Watchlist"}]}},
      {"id":"aiom.folder.trending","type":"collection","name":"Trending","poster":"https://img/t.png",
       "collection":{"sources":[{"type":"movie","catalogId":"mdblist.158678"},{"addonId":"com.other","type":"series","catalogId":"top"},
                                {"addonId":"https://lists.example/abc/","type":"movie","catalogId":"best"},{"addonId":"https://aio.example/x/manifest.json","type":"series","catalogId":"mdblist.1"}],
        "items":[{"id":"aiom.folder.anime","type":"collection","name":"Anime","collection":{"sources":[{"type":"anime","catalogId":"mal.airing"}]}},
                 {"id":"tt0903747","type":"series","name":"Breaking Bad"}]}},
      {"id":"aiom.folder.empty","type":"collection","name":"Opens later","collection":{}}
    ]""").let { (it as kotlinx.serialization.json.JsonArray).toList() }

    @Test fun aCollectionWhoseFoldersOpenNothingSaysSo() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        try {
            state.fetchLinked = { d -> AddonCollections.collection(d, listOf(json("""{"id":"f","type":"movie","name":"F","collection":{}}"""))) { null } }
            state.syncLinked(listOf(def()))
            assertTrue(state.all.isEmpty())
            assertTrue(state.linkedProblems.getValue("5f7f"), state.linkedProblems.getValue("5f7f").contains("without any folders"))
        } finally { scope.cancel() }
    }

    @Test fun aCollectionCatalogIsNeitherARowNorADiscoverCatalog() {
        val c = def()
        assertTrue(c.isCollection); assertFalse(c.homeable); assertFalse(c.browsable); assertFalse(c.featurable)
        assertEquals("5f7f", AddonCollections.collectionId(c))
        assertTrue(AddonCollections.collectionId(def(id = "other")).startsWith("linked-"))
        assertFalse(def(type = "movie", id = "mdblist.1").isCollection)
    }

    @Test fun foldersCarryTheirCatalogsAndSubfoldersAreFlattened() = runBlocking {
        val opened = mutableListOf<String>()
        val collection = AddonCollections.collection(def(), entries) { entry ->
            opened += entry.toString().substringAfter("\"id\":\"").substringBefore('"')
            // Some addons write "catalogs" for "sources".
            json("""{"id":"aiom.folder.empty","name":"Opened","collection":{"catalogs":[{"type":"series","catalogId":"mdblist.159365"}]}}""")
        }
        assertEquals("5f7f", collection.id); assertEquals("Discover", collection.title)
        assertEquals(listOf("Watchlist", "Trending", "Anime", "Opened"), collection.folders.map { it.title })
        assertEquals(listOf("aiom.folder.empty"), opened)
        val watch = collection.folders[0]
        assertEquals("LANDSCAPE", watch.tileShape); assertEquals("https://img/watch.png", watch.coverImageUrl)
        val source = watch.allSources.single()
        assertEquals("aio-metadata", source.addonId); assertEquals("all", source.type); assertEquals("trakt.watchlist", source.catalogId)
        assertEquals("None", source.genre); assertEquals(AddonAccess.key(addon.url), source.addonKey)
        val trending = collection.folders[1].allSources
        val other = trending[1]
        assertEquals("com.other", other.addonId); assertNull("another addon's catalog is matched by its id", other.addonKey)
        // The format lets a source name its addon by manifest link.
        val byLink = trending[2]
        assertEquals("https://lists.example/abc/manifest.json", byLink.addonId)
        val lists = AddonInfo("https://lists.example/abc/manifest.json", "https://lists.example/abc", "Lists", listOf("movie"), null, emptyList(), emptyList(), manifestId = "org.lists")
        assertTrue(Collections.matches(CatalogDef(lists, "movie", "best", "Best", emptyList(), false, false, false, false), byLink))
        assertFalse(Collections.matches(CatalogDef(addon, "movie", "best", "Best", emptyList(), false, false, false, false), byLink))
        assertEquals("its own link is the collection addon itself", "aio-metadata", trending[3].addonId)
        assertEquals("POSTER", collection.folders[1].tileShape)
        assertTrue(Collections.matches(CatalogDef(addon, "all", "trakt.watchlist", "W", listOf("None"), true, false, false, false), source))
    }

    @Test fun linkedCollectionsFollowTheAddonAreOnHomeAndYieldToYourOwnCopy() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        try {
            var fail = false
            state.fetchLinked = { d -> if (fail) error("offline") else AddonCollections.collection(d, entries) { null } }
            state.syncLinked(listOf(def()))
            val linked = state.all.single()
            assertEquals("5f7f", linked.id); assertTrue(state.isLinked("5f7f"))
            assertFalse("a new collection is on Home straight away", "5f7f" in state.hidden)
            assertEquals(listOf("5f7f"), state.onHome.map { it.id })
            assertTrue(state.linkedProblems.isEmpty())
            // A failed read keeps what the addon sent last; it stays shown.
            fail = true; state.syncLinked(listOf(def()))
            assertEquals(linked, state.all.single()); assertFalse("5f7f" in state.hidden)
            assertTrue(state.linkedProblems.getValue("5f7f").contains("offline"))
            // Deleting it hides it; it comes from the addon.
            assertNull(state.deleteCollection("5f7f")); assertTrue("5f7f" in state.hidden); assertEquals(1, state.all.size)
            // Saving it makes it your own, which wins.
            assertNull(state.saveCollection(linked.copy(title = "Mine"), linked))
            assertEquals(listOf("Mine"), state.all.map { it.title }); assertFalse(state.isLinked("5f7f"))
            // The addon dropping the catalog leaves nothing linked.
            fail = false; state.syncLinked(emptyList()); assertTrue(state.linked.isEmpty()); assertEquals(1, state.all.size)
        } finally { scope.cancel() }
    }
}
