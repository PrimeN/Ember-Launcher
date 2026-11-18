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

    /** #345: a panel that lists a stream under two categories still gives one channel. */
    @Test fun aRepeatedStreamIsOneChannel() {
        val json = """[{"stream_id":7,"name":"News","category_id":"0"},{"stream_id":7,"name":"News","category_id":"1"},{"stream_id":8,"name":"Sport"}]"""
        assertEquals(listOf("src:7", "src:8"), Xtream.streams(json.byteInputStream(), source, mapOf("0" to "News", "1" to "Sports"), "ts").map { it.key })
    }

    @Test fun anErrorObjectInsteadOfAListGivesNoChannels() {
        assertTrue(Xtream.streams("""{"user_info":{"auth":0}}""".byteInputStream(), source, emptyMap(), "ts").isEmpty())
        assertTrue(Xtream.streams("[]".byteInputStream(), source, emptyMap(), "ts").isEmpty())
    }

    /** A login with a space plays: the space is %20 in the channel's link, not "+". */
    @Test fun aSpaceInTheLoginIsEncodedForTheLink() {
        val spaced = IptvSource("src", "Provider", "xtream", "http://host:8080", "my user", "a b")
        val url = Xtream.streams("""[{"stream_id":7,"name":"News"}]""".byteInputStream(), spaced, emptyMap(), "ts").single().url
        assertEquals("http://host:8080/live/my%20user/a%20b/7.ts", url)
    }
}
