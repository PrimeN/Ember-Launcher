package app.ember.tv

import app.ember.tv.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CollectionFieldPreservationTest {
    @Test fun unknownCollectionFolderAndSourceFieldsSurviveEditsAndRoundTrips() {
        val raw = """[{"id":"c","title":"Original","vendorCollection":{"layout":3},"folders":[{"id":"f","title":"Folder","vendorFolder":[1,2],"sources":[{"provider":"trakt","traktListId":12,"vendorSource":{"sort":"future"}}]}]}]"""
        val value = Collections.parse(raw)!!.single()
        val edited = value.copy(title = "Edited", folders = value.folders.map { it.copy(title = "New folder") })
        val encoded = Collections.encode(listOf(edited)); val restored = Collections.parse(encoded)!!.single()
        assertEquals(edited, restored)
        val json = Json.parseToJsonElement(encoded).jsonArray.single().jsonObject
        assertEquals("Edited", json["title"]!!.jsonPrimitive.content)
        assertEquals(3, json["vendorCollection"]!!.jsonObject["layout"]!!.jsonPrimitive.int)
        val folder = json["folders"]!!.jsonArray.single().jsonObject
        assertEquals(2, folder["vendorFolder"]!!.jsonArray.size)
        assertEquals("future", folder["sources"]!!.jsonArray.single().jsonObject["vendorSource"]!!.jsonObject["sort"]!!.jsonPrimitive.content)
    }
    @Test fun encryptedAndUnencryptedRepresentationsHaveTheSameOpaqueSourceIdentity() {
        val input = CollectionSource(addonId = "a", catalogId = "c", type = "movie", extraArgs = linkedMapOf("token" to "fixture-private", "language" to "en"))
        val private = mutableMapOf<String,String>()
        val collection = MediaCollection("c", "Collection", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(input))))
        val sealed = CollectionSecrets.seal(listOf(collection)) { k,v -> private[k]=v;true }.single().folders.single().allSources.single()
        assertEquals(CollectionSourceIdentity.key(input), CollectionSourceIdentity.key(sealed) { private[it] })
        assertEquals(CollectionSourceIdentity.key(input), CollectionSourceIdentity.key(input.copy(title = "Renamed", extraArgs = input.extraArgs.toSortedMap())))
        assertNotEquals(CollectionSourceIdentity.key(input), CollectionSourceIdentity.key(input.copy(extraArgs = input.extraArgs + ("language" to "fr"))))
        assertFalse(CollectionSourceIdentity.key(input).contains("fixture"))
    }
    @Test fun mixedManifestRequiredInputsRemainRequiredAndCannotLoadHomeWithoutADefault() {
        val manifest = Json.parseToJsonElement("""{"name":"Fixture","catalogs":[{"type":"movie","id":"c","extraRequired":["language"],"extra":[{"name":"language","options":["en","fr"]}]}]}""")
        val catalog = AddonClient().catalogsOf(AddonInfo.parse("https://example.com/manifest.json", "https://example.com", manifest)).single()
        assertTrue(catalog.extras.single().required); assertFalse(catalog.homeable)
        assertNotNull(catalog.extraError(emptyMap())); assertNull(catalog.extraError(mapOf("language" to "en")))
    }
    @Test fun opaqueCatalogKeysAndEncodedHomeSectionsMigrateWithoutChangingOrderingOrInstanceIdentity() {
        val raw = "https://example.com/fixture-private/manifest.json?token=x|movie|top"
        val opaque = CatalogIdentity.migrate(raw)
        assertFalse(opaque.contains("fixture-private")); assertTrue(CatalogIdentity.belongs(opaque, raw.substringBeforeLast('|').substringBeforeLast('|')))
        assertEquals(opaque, CatalogIdentity.migrate(opaque))
        val section = app.ember.tv.state.HomeSectionsState.catalogSection(raw)
        val migrated = CatalogIdentity.migrate(section)
        assertEquals(opaque, app.ember.tv.state.HomeSectionsState.catalogKey(migrated))
        assertNotEquals(opaque, CatalogIdentity.migrate(raw.replace("fixture-private", "other")))
    }
}
