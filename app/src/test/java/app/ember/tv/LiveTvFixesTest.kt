package app.ember.tv

import app.ember.tv.iptv.*
import org.junit.Assert.*
import org.junit.Test

class LiveTvFixesTest {
    private fun channel(i: Int) = Channel("a:$i", "a", "Channel $i", "https://example.com/$i")
    @Test fun channelHeadersAlwaysIncludeDefaultsAndClearAbsentOverrides() {
        val custom = channel(1).copy(userAgent = "Custom", referrer = "https://provider.example")
        assertEquals("Custom", ChannelHeaders.agent(custom))
        assertEquals(mapOf("Referer" to "https://provider.example"), ChannelHeaders.properties(custom))
        assertEquals(ChannelHeaders.DEFAULT_AGENT, ChannelHeaders.agent(channel(2)))
        assertTrue(ChannelHeaders.properties(channel(2)).isEmpty())
        assertTrue(ChannelHeaders.properties(custom.copy(referrer = " ")).isEmpty())
    }
    @Test fun playbackOrderIncludesEveryChannelAndReportsZapping() {
        val list = List(1200, ::channel)
        val played = mutableListOf<String>()
        val session = LiveSession(list, list[399].key, { list }, { played += it.key })
        assertEquals(list[399], session.select())
        assertEquals(list[400], session.select(1))
        repeat(799) { session.select(1) }
        assertEquals(list.last().key, session.currentKey)
        assertEquals(list.first(), session.select(1))
        assertEquals(listOf("a:399", "a:400"), played.take(2))
    }
    @Test fun sessionRevalidatesVisibilityAndUsesUpdatedChannelData() {
        val initial = listOf(channel(1), channel(2), channel(3))
        var available = initial
        val session = LiveSession(initial, "a:1", { available }, {})
        available = listOf(initial[1].copy(url = "https://updated.example/2"), initial[2])
        assertEquals("https://updated.example/2", session.select(1)?.url)
        available = emptyList()
        assertNull(session.select())
    }
    @Test fun nextProgrammeRemainsReachableAcrossLongProgrammeAndGap() {
        val start = 1_800_000L * 100
        val shows = listOf(Programme(start, start + 4 * 3_600_000L, "Long"), Programme(start + 5 * 3_600_000L, start + 6 * 3_600_000L, "Next"))
        val slots = GuideWindow.slots(shows, start)
        val gap = GuideWindow.move(slots, start + 60_000, 1)
        assertEquals(start + 4 * 3_600_000L, gap)
        assertNull(GuideWindow.at(slots, gap)?.programme)
        val next = GuideWindow.move(slots, gap, 1)
        assertEquals("Next", GuideWindow.at(slots, next)?.programme?.title)
        assertEquals(start, GuideWindow.move(slots, gap, -1))
    }
    @Test fun subMinuteSlotsHaveLogicalTargetsWithoutChangingTimelineWidth() {
        val slots = GuideWindow.slots(listOf(Programme(0, 10_000, "Tiny"), Programme(10_000, 3_600_000, "Next")), 0)
        assertEquals("Tiny", GuideWindow.at(slots, 0)?.programme?.title)
        assertEquals(10_000L, GuideWindow.move(slots, 0, 1))
        assertEquals(GuideWindow.DURATION, slots.sumOf { it.to - it.from })
    }
    @Test fun nowMarkerMirrorsAndNeverEntersTheChannelRail() {
        assertEquals(300f, GuideWindow.marker(800f, 200f, 150f, 50f, false)!!, .01f)
        assertEquals(500f, GuideWindow.marker(800f, 200f, 150f, 50f, true)!!, .01f)
        assertNull(GuideWindow.marker(800f, 200f, 0f, 50f, false))
        assertNull(GuideWindow.marker(800f, 200f, 900f, 0f, true))
    }
    @Test fun deletedDisabledAndHiddenSelectionsRepairDeterministically() {
        val sources = listOf(IptvSource("a", "A", "m3u", "https://example.com"))
        assertEquals(LiveChoice(), LiveChoice("custom", list = "deleted").repaired(sources, LiveLibrary()))
        assertEquals(LiveChoice(), LiveChoice("source", source = "a").repaired(sources.map { it.copy(enabled = false) }, LiveLibrary()))
        assertEquals(LiveChoice("source", "a"), LiveChoice("source", "a", "News").repaired(sources,
            LiveLibrary(hiddenGroups = setOf(LiveLibrary.groupKey("a", "News")))))
        assertEquals(LiveChoice(), LiveChoice("favourites").repaired(sources, LiveLibrary(hiddenSections = setOf("favourites"))))
    }
}
