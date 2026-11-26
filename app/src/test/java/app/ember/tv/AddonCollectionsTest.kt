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
       "collection":{"catalogs":[{"type":"all","catalogId":"trakt.watchlist","genre":"None","name":"[Discover] Watchlist"}]}},
      {"id":"aiom.folder.trending","type":"collection","name":"Trending","poster":"https://img/t.png",
       "collection":{"catalogs":[{"type":"movie","catalogId":"mdblist.158678"},{"addonId":"com.other","type":"series","catalogId":"top"}],
        "items":[{"id":"aiom.folder.anime","type":"collection","name":"Anime","collection":{"catalogs":[{"type":"anime","catalogId":"mal.airing"}]}},
                 {"id":"tt0903747","type":"series","name":"Breaking Bad"}]}},
      {"id":"aiom.folder.empty","type":"collection","name":"Opens later","collection":{}}
    ]""").let { (it as kotlinx.serialization.json.JsonArray).toList() }

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
        val other = collection.folders[1].allSources[1]
        assertEquals("com.other", other.addonId); assertNull("another addon's catalog is matched by its id", other.addonKey)
        assertEquals("POSTER", collection.folders[1].tileShape)
        assertTrue(Collections.matches(CatalogDef(addon, "all", "trakt.watchlist", "W", listOf("None"), true, false, false, false), source))
    }

    @Test fun linkedCollectionsFollowTheAddonStartHiddenAndYieldToYourOwnCopy() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = CollectionsState(ctx, Prefs(ctx), scope, AddonClient(), { emptyList() }, {})
        try {
            var fail = false
            state.fetchLinked = { d -> if (fail) error("offline") else AddonCollections.collection(d, entries) { null } }
            state.syncLinked(listOf(def()))
            val linked = state.all.single()
            assertEquals("5f7f", linked.id); assertTrue(state.isLinked("5f7f"))
            assertTrue("new collections start hidden on Home", "5f7f" in state.hidden)
            assertTrue(state.onHome.isEmpty())
            state.toggle("5f7f"); assertEquals(listOf("5f7f"), state.onHome.map { it.id })
            // A failed read keeps what the addon sent last; it stays shown.
            fail = true; state.syncLinked(listOf(def()))
            assertEquals(linked, state.all.single()); assertFalse("5f7f" in state.hidden)
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
