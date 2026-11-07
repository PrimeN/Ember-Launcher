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

    @Test fun downloadedIconsAreReusedAcrossRestarts() {
        val file = file()
        val key = IconCacheVersion.key(IconCacheVersion(file).epoch, "https://example.test/icon.png")
        assertEquals(key, IconCacheVersion.key(IconCacheVersion(file).epoch, "https://example.test/icon.png"))
    }

    @Test fun indexesFromBeforeTheChangeKeepTheirIcons() {
        // #55: older indexes also listed catalog and collection digests.
        val file = file()
        val epoch = java.util.UUID.randomUUID().toString()
        file.writeText("""{"epoch":"$epoch","groups":{"catalogs":"${"a".repeat(64)}"}}""")
        assertEquals(epoch, IconCacheVersion(file).epoch)
    }

    @Test fun privateArgumentsAndUrlsNeverAppearInTheIndexOrCacheKey() {
        val file = file(); val store = IconCacheVersion(file)
        val key = IconCacheVersion.key(store.epoch, "https://example.test/icon?token=fixture-secret")
        assertFalse(key.contains("fixture-secret")); assertFalse(key.contains("example.test"))
        assertFalse(file.readText().contains("fixture-secret"))
    }

    @Test fun manualClearAndDamagedIndexesCannotReuseOldImages() {
        val file = file(); val store = IconCacheVersion(file)
        val old = store.epoch
        store.rotate(); assertNotEquals(old, store.epoch)
        assertEquals(store.epoch, IconCacheVersion(file).epoch)
        val cleared = store.epoch
        file.writeText("invalid")
        assertNotEquals(cleared, IconCacheVersion(file).epoch)
    }
}
