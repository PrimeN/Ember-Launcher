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

    @Test fun deletingAFolderKeepsTheRestAndTheLastFolderTakesItsCollection() {
        // #157: a collection must keep a folder, so it never lingers empty and unsaveable.
        fun folder(id: String) = CollectionFolder(id, "Folder $id", sources = listOf(CollectionSource(provider = "trakt", traktListId = 42)))
        val two = MediaCollection("a", "Two", folders = listOf(folder("f1"), folder("f2")))
        val one = MediaCollection("b", "One", folders = listOf(folder("f1")))
        val list = listOf(two, one)
        assertEquals(listOf("f2"), CollectionDrafts.withoutFolder(list, "a", "f1").first { it.id == "a" }.folders.map { it.id })
        assertEquals(2, CollectionDrafts.withoutFolder(list, "a", "f1").size)
        assertEquals(listOf("a"), CollectionDrafts.withoutFolder(list, "b", "f1").map { it.id })
        assertEquals(list, CollectionDrafts.withoutFolder(list, "a", "missing"))
        for (c in CollectionDrafts.withoutFolder(list, "a", "f1")) assertNull(Collections.inspect(Collections.encode(listOf(c))).error)
        assertTrue(app.ember.tv.ui.CollectionDeletion.folderMessage(last = true).contains("whole collection"))
        assertFalse(app.ember.tv.ui.CollectionDeletion.folderMessage(last = false).contains("whole collection"))
        assertTrue(app.ember.tv.ui.CollectionDeletion.collectionMessage(1).contains("its 1 folder from"))
        assertTrue(app.ember.tv.ui.CollectionDeletion.collectionMessage(3).contains("its 3 folders from"))
        for (text in listOf(app.ember.tv.ui.CollectionDeletion.collectionMessage(3), app.ember.tv.ui.CollectionDeletion.ALL_MESSAGE, app.ember.tv.ui.CollectionDeletion.FOLDER_MESSAGE))
            assertTrue(text, text.contains("can't be undone"))
    }
    @Test fun manyCollectionsExportAsOnePackAndComeBackWhole() {
        // #158: Export all.
        val list = (1..3).map { MediaCollection("c$it", "Collection $it", folders = listOf(CollectionFolder("f", "Folder", sources = listOf(CollectionSource(provider = "trakt", traktListId = it.toLong()))))) }
        val pack = CollectionTransfer.seal(list, "fixture passphrase") { null }
        assertFalse(pack.contains("Collection 2"))
        assertEquals(list, Collections.parse(CollectionTransfer.open(pack, "fixture passphrase")))
    }
    @Test fun thePhonePageHasHeadedCreateExportAndDeleteSections() {
        // #158
        val content = PhoneContent(listOf("opaque" to "Popular"), listOf("c-1" to "Films / New folder", "f-1" to "Films / Action", "c-2" to "Shows / New folder", "f-2" to "Shows / Drama"), emptyList(),
            collections = listOf("c-1" to "Films", "c-2" to "Shows"))
        val html = ContentPhone.render(content, "lease")
        for (heading in listOf("Create or add to a collection", "Edit a folder", "Edit a collection", "Edit a source", "Export", "Delete", "Add-ons"))
            assertTrue(heading, html.contains("<h3>$heading</h3>"))
        assertTrue(html.indexOf("<h3>Create or add to a collection</h3>") < html.indexOf("<h3>Export</h3>"))
        assertTrue(html.contains("value=\"all\">All collections (2)"))
        val delete = html.substringAfter("name=action value=delete>").substringBefore("</form>")
        assertTrue(delete.contains("Films (whole collection)")); assertTrue(delete.contains("Shows / Drama")); assertFalse(delete.contains("New folder"))
        assertTrue(delete.contains("review on the TV"))
        // One collection: nothing to combine.
        assertFalse(ContentPhone.render(content.copy(collections = listOf("c-1" to "Films")), "lease").contains("All collections"))
    }

    @Test fun thePhonePagesFlowIntoColumnsOnTabletsAndWiderScreens() {
        // #159: each Organize content task is one card inside the column flow.
        val html = ContentPhone.render(PhoneContent(listOf("opaque" to "Popular"), listOf("f-1" to "Films / Action"), emptyList(), collections = listOf("c-1" to "Films")), "lease")
        assertEquals(1, Regex("<div class=cols>").findAll(html).count())
        assertEquals(7, Regex("<div class=grp>").findAll(html).count())
        assertEquals(Regex("<div").findAll(html).count(), Regex("</div>").findAll(html).count())
        assertTrue(html.indexOf("<div class=cols>") < html.indexOf("<h3>Create or add to a collection</h3>"))
        val server = java.io.File("src/main/java/app/ember/tv/phone/PhoneSetup.kt").readText()
        assertTrue(server.contains(".cols{column-width:320px"))
        assertTrue(server.contains("append(\"<div class=cols>\")"))
        assertTrue("phones keep the readable single column; wide screens get room", server.contains("main{max-width:1240px"))
        assertFalse(server.contains("max-width:640px"))
    }
}
