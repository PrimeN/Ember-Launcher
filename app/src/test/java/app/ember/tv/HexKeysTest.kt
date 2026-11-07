package app.ember.tv

import app.ember.tv.data.AddonAccess
import app.ember.tv.data.HomeTitleVisibility
import app.ember.tv.data.Meta
import app.ember.tv.data.toHex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** #57: faster hashing must keep every stored key identical. */
class HexKeysTest {
    private fun old(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    @Test fun hexMatchesTheOldFormatting() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(old(all), all.toHex())
        assertEquals("", ByteArray(0).toHex())
        listOf("", "a", "https://addon.example/manifest.json").forEach { assertEquals(old(sha(it)), sha(it).toHex()) }
    }

    @Test fun addonAndTitleKeysAreUnchanged() {
        val url = "https://addon.example/manifest.json"
        assertEquals(old(sha(url)), AddonAccess.key(url))
        assertEquals(AddonAccess.key(url), AddonAccess.key(url))
        val meta = Meta(id = "tt0111161", type = "movie", name = "Fixture")
        assertEquals(old(sha("main")) + ":" + old(sha("movie:tt0111161")), HomeTitleVisibility.key("main", meta))
    }
}
