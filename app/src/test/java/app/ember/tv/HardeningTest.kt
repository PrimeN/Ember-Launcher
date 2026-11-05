package app.ember.tv

import app.ember.tv.data.StreamParser
import app.ember.tv.data.Updater
import app.ember.tv.player.NetworkAccount
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Addon link schemes, verified update downloads and the SMB encryption choice. */
class HardeningTest {
    private fun parse(json: String) = StreamParser.parse("Addon", Json.parseToJsonElement(json))

    @Test fun addonStreamsMayOnlyUseNetworkSchemes() {
        for (ok in listOf("https://cdn.example.test/v.mkv", "HTTP://example.test/a", "rtsp://cam.example.test/live",
            "rtmps://live.example.test/app", "magnet:?xt=urn:btih:abc", "https://user@example.test/v"))
            assertTrue(ok, StreamParser.allowedUrl(ok))
        for (bad in listOf("file:///data/data/app.ember.tv/files/library.json", "content://media/external/video/1",
            "lavfi://color", "tcp://example.test:9000", "javascript:alert(1)", "https://", "https:///path",
            "fd://3", "av://lavfi:anullsrc", "https://exa mple.test/v", "magnet:xt"))
            assertFalse(bad, StreamParser.allowedUrl(bad))
    }

    @Test fun aLocalFileLinkFromAnAddonIsDropped() {
        assertNull(parse("""{"url":"file:///sdcard/secret.mkv"}"""))
        val torrent = parse("""{"url":"lavfi://color","infoHash":"0123456789abcdef"}""")!!
        assertNull(torrent.url)
        assertEquals("0123456789abcdef", torrent.infoHash)
        assertEquals("https://example.test/v", parse("""{"url":" https://example.test/v "}""")!!.url)
    }

    private val hex = "a".repeat(64)
    private fun release(size: Long = 10, sha: String? = hex) =
        Updater.Release("latest-build", "Build", "", "https://api.example.test/asset", "ember-tv-0.9.0-1.apk", size, 1, sha)

    @Test fun githubAssetDigestsAreReadOnlyWhenWellFormed() {
        assertEquals(hex, Updater.sha256Of("sha256:${hex.uppercase()}"))
        assertNull(Updater.sha256Of(null))
        assertNull(Updater.sha256Of("sha1:$hex"))
        assertNull(Updater.sha256Of("sha256:abc"))
        assertNull(Updater.sha256Of("sha256:" + "g".repeat(64)))
    }

    @Test fun aDownloadMustMatchTheReleaseSizeAndChecksum() {
        assertNull(Updater.verify(release(), 10, hex))
        assertNull(Updater.verify(release(), 10, hex.uppercase()))
        assertNotNull(Updater.verify(release(), 9, hex))
        assertNotNull(Updater.verify(release(), 10, "b".repeat(64)))
        // Releases without a published digest or size still install (TLS and Android's signature check remain).
        assertNull(Updater.verify(release(size = 0, sha = null), 123, "c".repeat(64)))
    }

    @Test fun eachUpdateChannelReadsItsOwnRelease() {
        assertEquals("https://api.github.com/repos/o/r/releases/tags/latest-build", Updater.releaseUrl("o/r", "builds"))
        assertEquals("https://api.github.com/repos/o/r/releases/tags/beta-build", Updater.releaseUrl("o/r", "beta"))
        assertEquals("https://api.github.com/repos/o/r/releases/latest", Updater.releaseUrl("o/r", "releases"))
    }

    @Test fun savedSharesKeepRequiringSmbEncryption() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<NetworkAccount>("""{"id":"1","label":"NAS","address":"smb://nas/Movies"}""")
        assertFalse(old.allowUnencryptedSmb)
        val opted = old.copy(allowUnencryptedSmb = true)
        assertTrue(json.decodeFromString<NetworkAccount>(json.encodeToString(NetworkAccount.serializer(), opted)).allowUnencryptedSmb)
    }
}
