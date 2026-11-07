package app.ember.tv

import app.ember.tv.data.StreamParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #74: binge groups, file hashes and a source's own subtitles. */
class BingeGroupAndStreamHintsTest {
    private fun stream(json: String) = StreamParser.parse("Addon", Json.parseToJsonElement(json))!!

    @Test fun readsBingeGroupHashSizeAndSubtitles() {
        val s = stream("""{"url":"https://cdn.example/e1.mkv","name":"1080p","behaviorHints":{"bingeGroup":"addon|1080p|WEB",
            "videoHash":"8E245D9679D31E12","videoSize":1234567},"subtitles":[{"url":"https://subs.example/en.srt","lang":"eng"},{"url":"file:///sdcard/x.srt","lang":"eng"}]}""")
        assertEquals("addon|1080p|WEB", s.bingeGroup)
        assertEquals("8e245d9679d31e12", s.videoHash); assertEquals(1234567L, s.videoSize)
        assertEquals(listOf("https://subs.example/en.srt"), s.subtitles.map { it.url })
        assertNull(stream("""{"url":"https://cdn.example/x.mkv","behaviorHints":{"videoHash":"not-a-hash"}}""").videoHash)
    }

    @Test fun nextEpisodeKeepsTheSameBingeGroupFirst() {
        val better = stream("""{"url":"https://cdn.example/a.mkv","name":"4K REMUX","behaviorHints":{"bingeGroup":"other"}}""")
        val same = stream("""{"url":"https://cdn.example/b.mkv","name":"720p WEB","behaviorHints":{"bingeGroup":"mine"}}""")
        assertEquals(same, StreamParser.pickSimilar(listOf(better, same), null, null, "mine"))
        assertEquals(better, StreamParser.pickSimilar(listOf(better, same), null, null, null))
    }
}
