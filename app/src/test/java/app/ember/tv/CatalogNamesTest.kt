package app.ember.tv

import app.ember.tv.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CatalogNamesTest {
    @After fun clearAliases() { CatalogNames.load(emptyMap()) }

    private fun catalog(url: String = "https://example.com/manifest.json", type: String = "movie"): CatalogDef {
        val addon = AddonInfo.parse(url, url.removeSuffix("/manifest.json"), Json.parseToJsonElement(
            """{"id":"test","name":"Addon","version":"1","catalogs":[{"type":"$type","id":"popular","name":"Popular"}]}""",
        ))
        return AddonClient().catalogsOf(addon).single()
    }

    @Test fun aliasesAreScopedToOneProviderAndCatalogTypeWithoutChangingIdentity() {
        val movie = catalog()
        val show = catalog(type = "series")
        val other = catalog("https://other.example/manifest.json")
        val original = movie.copy()
        CatalogNames.load(CatalogNames.updated(emptyMap(), movie.key, "  My movies  "))
        assertEquals("My movies", movie.label)
        assertEquals(show.originalLabel, show.label)
        assertEquals(other.originalLabel, other.label)
        assertEquals(original, movie)
        assertEquals("popular", movie.id)
        assertEquals("Popular", movie.name)
        assertEquals(original.key, movie.key)
    }

    @Test fun cachedRowsRenameAndRestoreWithoutFetchingAnAddonAgain() {
        val def = catalog()
        val row = CatalogRow(def.addon.name, def.originalLabel, emptyList(), catalogKey = def.key)
        val snapshot = with(HomeSnapshot) { row.toSnap() }
        CatalogNames.load(mapOf(def.key to "Friday movies"))
        assertEquals("Friday movies", row.displayTitle)
        val restored = with(HomeSnapshot) { snapshot.toRow() }
        assertEquals("Friday movies", restored.displayTitle)
        assertEquals(def.originalLabel, snapshot.title)
        CatalogNames.load(CatalogNames.updated(CatalogNames.names, def.key, "  "))
        assertEquals(def.originalLabel, restored.displayTitle)
        assertEquals(def.originalLabel, def.label)
    }

    @Test fun persistedNamesApplyToNewDefinitionsButNotUnrelatedRows() {
        val def = catalog()
        val saved = Json.encodeToString(mapOf(def.key to "週末 movies"))
        CatalogNames.load(Json.decodeFromString<Map<String, String>>(saved))
        assertEquals("週末 movies", catalog().label)
        assertEquals("Continue watching", CatalogRow("Plex", "Continue watching", emptyList()).displayTitle)
        assertEquals("Recommended", CatalogRow("Trakt", "Recommended", emptyList(), catalogKey = "smart:recommended").displayTitle)
    }
}
