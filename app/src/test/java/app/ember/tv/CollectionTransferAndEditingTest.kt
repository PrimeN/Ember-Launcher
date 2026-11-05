package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.phone.*
import app.ember.tv.discover.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CollectionTransferAndEditingTest {
    @Test fun portableEncryptedExportsIncludePrivateArgumentsAndFutureFieldsWithoutPlaintextCopies() {
        val source = CollectionSource(addonId = "a", type = "movie", catalogId = "c", extraArgs = mapOf("token" to "fixture-private", "language" to "en"), extensionFields = mapOf("future" to JsonPrimitive("kept")))
        val value = MediaCollection("c", "Collection", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(source))))
        val private = mutableMapOf<String,String>()
        val saved = CollectionSecrets.seal(listOf(value)) { k,v -> private[k]=v;true }
        val encrypted = CollectionTransfer.seal(saved, "fixture passphrase") { private[it] }
        assertFalse(encrypted.contains("fixture-private")); assertFalse(encrypted.contains("Collection"))
        val restored = Collections.parse(CollectionTransfer.open(encrypted, "fixture passphrase"))!!.single()
        assertEquals(source, restored.folders.single().allSources.single()); assertEquals(value, restored)
        assertTrue(runCatching { CollectionTransfer.open(encrypted, "incorrect passphrase") }.isFailure)
        val damaged = Json.parseToJsonElement(encrypted).jsonObject.toMutableMap().apply { put("data", JsonPrimitive("AAAA")) }
        assertTrue(runCatching { CollectionTransfer.open(JsonObject(damaged).toString(), "fixture passphrase") }.isFailure)
        assertTrue(runCatching { CollectionTransfer.seal(saved, "short") { private[it] } }.isFailure)
        assertTrue(runCatching { CollectionTransfer.seal(saved, "fixture passphrase") { null } }.isFailure)
    }
    @Test fun duplicationRetainsConfigurationAndUnknownFieldsWithFreshStableContainerIds() {
        val input = MediaCollection("c", "Collection", extensionFields = mapOf("future" to JsonPrimitive(true)), folders = listOf(CollectionFolder("f", "Folder", sources = listOf(CollectionSource(provider = "trakt", traktListId = 42)))))
        val copy = CollectionDrafts.duplicate(input)
        assertNotEquals(input.id, copy.id); assertNotEquals(input.folders.single().id, copy.folders.single().id)
        assertEquals(input.extensionFields, copy.extensionFields); assertEquals(input.folders.single().allSources, copy.folders.single().allSources)
        assertEquals(input.folders.single().allSources, CollectionDrafts.duplicate(input.folders.single()).allSources)
    }
    @Test fun phoneControlsEscapeUntrustedLabelsAndNeverPrefillPrivateDefaultsOrOptions() {
        val content = PhoneContent(listOf("opaque" to "<unsafe>"), emptyList(), emptyList(),
            catalogExtras = listOf(PhoneCatalogExtras("opaque", "Provider", listOf(CatalogExtraDef("token", true, listOf("fixture-option"), "fixture-default")))),
            collections = listOf("collection" to "Collection"), folderSources = listOf("source" to "Source"),
            existingExtras = listOf(PhoneCatalogExtras("source", "Source", listOf(CatalogExtraDef("language", false, listOf("en","fr"), "fr")))))
        val html = ContentPhone.render(content, "lease")
        assertFalse(html.contains("fixture-option")); assertFalse(html.contains("fixture-default")); assertFalse(html.contains("<unsafe>"))
        assertTrue(html.contains("edit-extra:source:language")); assertTrue(html.contains("/collection-export?k=lease"))
        assertTrue(html.contains("name=operation")); assertTrue(html.contains("Preview source change on TV")); assertTrue(html.contains("data-existing-source"))
    }
    @Test fun plainPacksRemainCompatibleAndKnownSourceFiltersRejectInvalidInput() {
        val c = MediaCollection("c", "Collection", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(CollectionSource(provider = "tmdb", tmdbSourceType = "DISCOVER", filters = mapOf("year" to JsonPrimitive("2020")))))))
        val plain = Collections.encode(listOf(c)); assertEquals(plain, CollectionTransfer.open(plain, ""))
        assertNull(Collections.inspect(plain).error)
        val bad = c.copy(folders = listOf(c.folders.single().copy(sources = listOf(c.folders.single().allSources.single().copy(filters = mapOf("voteAverageGte" to JsonPrimitive("11")))))))
        assertNotNull(Collections.inspect(Collections.encode(listOf(bad))).error)
    }
}
