package app.ember.tv

import app.ember.tv.data.StreamParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #73: externalUrl and ytId streams become links that open in another app. */
class AddonLinkSourcesTest {
    private fun link(json: String) = StreamParser.link("Catalogs", Json.parseToJsonElement(json))

    @Test fun serviceLinksAreNamedAfterTheirService() {
        val netflix = link("""{"name":"Netflix 4K","externalUrl":"https://www.netflix.com/title/80057281"}""")!!
        assertEquals("Netflix", netflix.name); assertEquals("https://www.netflix.com/title/80057281", netflix.webUrl)
        assertNull(netflix.androidUrl); assertEquals("4K", netflix.quality); assertEquals("From your addons", netflix.label)
        val deepLink = link("""{"name":"Watch","externalUrl":"disneyplus://video/123"}""")!!
        assertEquals("Disney+", deepLink.name); assertEquals("disneyplus://video/123", deepLink.androidUrl); assertNull(deepLink.webUrl)
        // Links that aren't a streaming service (setup, sign-in, error pages) are not offers (#143).
        assertNull(link("""{"name":"Watch\nmore","externalUrl":"https://example.org/x"}"""))
        assertNull(link("""{"name":"[RD] Error","title":"Configure the addon","externalUrl":"https://torrentio.strem.fun/configure"}"""))
        assertNull(link("""{"name":"Open","externalUrl":"someapp://open/1"}"""))
    }

    @Test fun youtubeIdsOpenInYouTube() {
        val yt = link("""{"title":"Trailer","ytId":"dQw4w9WgXcQ"}""")!!
        assertEquals("YouTube", yt.name); assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", yt.webUrl)
        assertNull(link("""{"ytId":"not a valid id"}"""))
    }

    @Test fun unsafeOrPlayableEntriesAreNotLinks() {
        listOf("javascript:alert(1)", "intent://x#Intent;component=a/b;end", "file:///sdcard/x", "content://x/y", "data:text/html,x", "1abc://x")
            .forEach { assertFalse(it, StreamParser.safeExternal(it)) }
        assertNull(link("""{"externalUrl":"intent://evil#Intent;end"}"""))
        assertNull("playable streams stay sources", link("""{"url":"https://cdn.example/v.mkv","externalUrl":"https://www.netflix.com/x"}"""))
        assertNotNull(StreamParser.parse("Addon", Json.parseToJsonElement("""{"url":"https://cdn.example/v.mkv"}""")))
        assertNull(StreamParser.parse("Addon", Json.parseToJsonElement("""{"externalUrl":"https://www.netflix.com/x"}""")))
        assertTrue(StreamParser.safeExternal("netflix://title/1"))
    }
}
