package app.ember.tv

import app.ember.tv.data.AsyncCache
import app.ember.tv.data.BadgePatterns
import app.ember.tv.data.CrashLog
import app.ember.tv.data.Meta
import app.ember.tv.data.ResponseTooLargeException
import app.ember.tv.data.boundedText
import app.ember.tv.ui.WatchedUi
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Fixes from the 29 October main analysis: redaction, reply limits, badge patterns, caches. */
class MainAnalysisFixesTest {
    @Test fun crashLogRemovesPasswordsFromEveryKindOfLink() {
        val text = CrashLog.redact(
            "open ftp://alice:s3cret@nas.local/share/movie.mkv failed; " +
                "http://bob:hunter2@iptv.example.test:8080/live/1.ts; smb://carol:pw@192.168.1.5/media; " +
                "rtsp://cam.example.test/stream?auth=tok; magnet:?xt=urn:btih:abcdef&tr=udp://tracker; passwd=x")
        listOf("alice", "s3cret", "bob", "hunter2", "carol", ":pw@", "auth=tok", "abcdef", "share/movie", "passwd=x").forEach {
            assertFalse("$it leaked: $text", text.contains(it))
        }
        listOf("ftp://nas.local/…", "http://iptv.example.test:8080/…", "smb://192.168.1.5/…", "rtsp://cam.example.test/…", "magnet:?…")
            .forEach { assertTrue("$it missing: $text", text.contains(it)) }
    }

    @Test fun repliesOverTheirLimitAreRefused() {
        assertEquals("small", "small".toResponseBody().boundedText(16))
        assertEquals("x".repeat(16), "x".repeat(16).toResponseBody().boundedText(16))
        assertThrows(ResponseTooLargeException::class.java) { "x".repeat(17).toResponseBody().boundedText(16) }
    }

    @Test fun slowOrOverlongBadgePatternsAreSkipped() {
        assertTrue(BadgePatterns.safe(Regex("""(?i)\b(2160p|4k|uhd)\b""")))
        assertTrue(BadgePatterns.safe(Regex("""(?i)dolby\s*vision|\bDV\b""")))
        assertTrue(BadgePatterns.safe(Regex("""(?s)^(?=.*a)(?!.*b)""")))
        assertTrue(BadgePatterns.safe(Regex("""([a-z]+)?x|\((\d+)\)+""")))
        listOf("""(a+)+$""", """(x+x+)+y""", """(\w+\s?)+$""", """(.*a){12}""", """((ab)*c)*""").forEach {
            assertFalse(it, BadgePatterns.safe(Regex(it)))
        }
        assertFalse(BadgePatterns.safe(Regex("x".repeat(BadgePatterns.MAX_LENGTH + 1))))
    }

    @Test fun aFailedRefreshCanPutBackTheUsableValue() = runBlocking {
        val cache = AsyncCache<String, String>()
        assertEquals("v1", cache.load("k") { "v1" })
        val version = cache.invalidate("k")
        cache.restore("k", "v1", version)
        assertEquals("v1", cache.peek("k"))
        // A newer value is never replaced by the old one.
        val newer = cache.invalidate("k")
        assertEquals("v2", cache.load("k") { "v2" })
        cache.restore("k", "v1", newer)
        assertEquals("v2", cache.peek("k"))
    }

    @Test fun watchedBadgesLookNothingUpWhenSwitchedOff() {
        var lookups = 0
        val meta = Meta(id = "tt1", type = "movie", name = "Film")
        val off = WatchedUi(showBadges = false, title = { lookups++; true })
        assertFalse(off.badge(meta))
        assertEquals(0, lookups)
        assertTrue(off.copy(showBadges = true).badge(meta))
        assertEquals(1, lookups)
    }
}
