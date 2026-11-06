package app.ember.tv

import app.ember.tv.data.IconCacheVersion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IconCacheVersionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = File(temp.newFolder(), "icons.json")
    @Test fun unchangedConfigurationsReuseDownloadedBytesAcrossRestarts() {
        val file = file()
        val first = IconCacheVersion(file)
        val groups = mapOf("collections" to IconCacheVersion.digest("collection pack"), "catalogs" to IconCacheVersion.digest("catalog config"))
        assertTrue(first.update(groups))
        val key = IconCacheVersion.key(first.epoch, "https://example.test/icon.png")
        val reopened = IconCacheVersion(file)
        assertFalse(reopened.update(groups))
        assertEquals(key, IconCacheVersion.key(reopened.epoch, "https://example.test/icon.png"))
    }
    @Test fun editsAndRemovalInvalidateEvenAnUnchangedImageUrl() {
        val store = IconCacheVersion(file())
        store.update(mapOf("collections" to IconCacheVersion.digest("old collection")))
        val old = IconCacheVersion.key(store.epoch, "https://example.test/icon.png")
        assertTrue(store.update(mapOf("collections" to IconCacheVersion.digest("edited collection"))))
        assertNotEquals(old, IconCacheVersion.key(store.epoch, "https://example.test/icon.png"))
        val edited = store.epoch
        assertTrue(store.update(mapOf("collections" to IconCacheVersion.digest("[]"))))
        assertNotEquals(edited, store.epoch)
    }
    @Test fun loadingOneGroupDoesNotEraseTheOtherGroupsSavedSignature() {
        val file = file(); val catalogs = IconCacheVersion.digest("catalogs"); val collections = IconCacheVersion.digest("collections")
        val first = IconCacheVersion(file)
        first.update(mapOf("catalogs" to catalogs, "collections" to collections))
        val restarted = IconCacheVersion(file)
        assertFalse(restarted.update(mapOf("collections" to collections)))
        assertFalse(restarted.update(mapOf("catalogs" to catalogs)))
        assertEquals(first.epoch, restarted.epoch)
    }
    @Test fun privateArgumentsAndUrlsNeverAppearInTheIndexOrCacheKey() {
        val file = file(); val store = IconCacheVersion(file)
        store.update(mapOf("catalogs" to IconCacheVersion.digest("https://example.test/private?token=fixture-secret")))
        val key = IconCacheVersion.key(store.epoch, "https://example.test/icon?token=fixture-secret")
        assertFalse(key.contains("fixture-secret")); assertFalse(key.contains("example.test"))
        assertFalse(file.readText().contains("fixture-secret"))
    }
    @Test fun manualClearAndDamagedIndexesCannotReuseOldImages() {
        val file = file(); val store = IconCacheVersion(file)
        store.update(mapOf("catalogs" to IconCacheVersion.digest("catalogs")))
        val old = store.epoch
        assertTrue(store.update(emptyMap(), force = true)); assertNotEquals(old, store.epoch)
        val cleared = store.epoch
        file.writeText("invalid")
        val reopened = IconCacheVersion(file)
        assertNotEquals(cleared, reopened.epoch)
        assertTrue(reopened.update(emptyMap()))
    }
}
