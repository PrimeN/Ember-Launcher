package app.ember.tv

import app.ember.tv.iptv.IptvSource
import app.ember.tv.iptv.Xtream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** #53: Xtream channel lists are decoded one element at a time. */
class XtreamStreamingTest {
    private val source = IptvSource("src", "Provider", "xtream", "http://host:8080", "user", "p@ss")

    /** A large response produced on the fly, so the test itself never holds it as one string. */
    private fun response(count: Int): InputStream {
        val parts = sequenceOf("[") + (0 until count).asSequence().map { i ->
            (if (i > 0) "," else "") + """{"num":${i + 1},"name":" Channel $i ","stream_id":$i,"stream_icon":"","category_id":"${i % 3}","epg_channel_id":"ch$i.example"}"""
        } + sequenceOf("]")
        val iterator = parts.iterator()
        return object : InputStream() {
            var chunk = ByteArray(0); var pos = 0
            override fun read(): Int {
                while (pos >= chunk.size) { if (!iterator.hasNext()) return -1; chunk = iterator.next().toByteArray(); pos = 0 }
                return chunk[pos++].toInt() and 0xFF
            }
        }
    }

    @Test fun largeListsDecodeIntoChannels() {
        val channels = Xtream.streams(response(50_000), source, mapOf("0" to "News", "1" to "Sports"), "ts")
        assertEquals(50_000, channels.size)
        val first = channels.first()
        assertEquals("src:0", first.key); assertEquals("Channel 0", first.name); assertEquals("News", first.group)
        assertEquals("http://host:8080/live/user/p%40ss/0.ts", first.url)
        assertEquals(null, first.logo)
        assertEquals("Other", channels[2].group)
        assertEquals(50_000, channels.last().number)
    }

    @Test fun anErrorObjectInsteadOfAListGivesNoChannels() {
        assertTrue(Xtream.streams("""{"user_info":{"auth":0}}""".byteInputStream(), source, emptyMap(), "ts").isEmpty())
        assertTrue(Xtream.streams("[]".byteInputStream(), source, emptyMap(), "ts").isEmpty())
    }
}
