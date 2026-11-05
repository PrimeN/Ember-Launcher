package app.ember.tv

import app.ember.tv.iptv.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LiveTvDesignTest {
    private fun channel(i: Int, source: String = "a", group: String = "News") = Channel("$source:$i", source, "Channel $i", "https://example.com/$i", group = group)
    @Test fun scopedListsUseOnlyVisibleChannelsAndRetainSavedOrder() {
        val a = channel(1); val b = channel(2, group = "Sport"); val c = channel(3, source = "b")
        val library = LiveLibrary(hiddenChannels = setOf(b.key), lists = listOf(ChannelList("list", "List", listOf(c.key, b.key, a.key))))
        val visible = library.visible(listOf(a, b, c), setOf("a", "b"))
        assertEquals(listOf(a), LiveChoice("source", "a", "News").channels(visible, library, emptySet(), emptyList()))
        assertEquals(listOf(c, a), LiveChoice("custom", list = "list").channels(visible, library, emptySet(), emptyList()))
        assertEquals(listOf(a), LiveChoice("favourites").channels(visible, library, setOf(a.key, b.key), emptyList()))
        assertEquals(listOf(c, a), LiveChoice("recent").channels(visible, library, emptySet(), listOf(c.key, b.key, a.key)))
    }
    @Test fun bulkMembershipAndReorderingAreBoundedAndPreserveOtherLists() {
        val library = LiveLibrary(lists = listOf(ChannelList("a", "A", listOf("1", "2")), ChannelList("b", "B", listOf("3"))))
        val added = library.setMembers("a", listOf("2", "3", "4"), true)
        assertEquals(listOf("1", "2", "3", "4"), added.lists.first().channelKeys)
        assertEquals(listOf("3"), added.lists.last().channelKeys)
        assertEquals(listOf("1", "3"), added.setMembers("a", listOf("2", "4"), false).lists.first().channelKeys)
        assertEquals(listOf("b", "a"), library.moveList("a", 1).lists.map { it.id })
        assertEquals(library, library.moveList("a", -1))
        assertEquals(listOf("2", "1"), library.moveMember("a", "2", -1).lists.first().channelKeys)
        assertEquals(library, library.moveMember("a", "missing", 1))
    }
    @Test fun bannerAndGuideReturnFollowCurrentAuthorizedChannel() {
        val a = channel(1); val b = channel(2)
        var available = listOf(a, b)
        var returned: String? = null
        val now = Programme(100, 200, "Now"); val next = Programme(200, 300, "Next")
        val session = LiveSession(available, a.key, { available }, {}, { returned = it.key }, { _, _ -> now to next }, { "Provider" })
        session.select(1)
        val banner = session.information(150)!!
        assertEquals(b, banner.channel)
        assertEquals("Provider", banner.source)
        assertEquals(now, banner.now)
        assertEquals(next, banner.next)
        assertTrue(session.openGuide())
        assertEquals(b.key, returned)
        available = emptyList()
        returned = null
        assertFalse(session.openGuide())
        assertNull(session.information(150))
        assertNull(returned)
    }
    @Test fun sameBrowsingTimeFindsTheRightProgrammeOnDifferentChannels() {
        val a = GuideWindow.slots(listOf(Programme(0, 3_600_000, "Long")), 0)
        val b = GuideWindow.slots(listOf(Programme(0, 900_000, "First"), Programme(900_000, 2_700_000, "Second")), 0)
        assertEquals("Long", GuideWindow.at(a, 1_800_000)?.programme?.title)
        assertEquals("Second", GuideWindow.at(b, 1_800_000)?.programme?.title)
    }
    @Test fun playlistFetchesAreBoundedAndPublishBeforeSlowSourcesFinish() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val slow = CompletableDeferred<Unit>()
        val results = mutableListOf<Int>()
        var active = 0; var peak = 0
        try {
            val job = scope.launch {
                ProgressiveSources.load((0..4).toList(), fetch = { id ->
                    active++; peak = maxOf(peak, active)
                    try { if (id == 0) slow.await(); if (id == 4) error("Unavailable"); id } finally { active-- }
                }) { id, result -> results += if (result.isSuccess) id else -id }
            }
            yield()
            assertTrue(1 in results)
            assertFalse(0 in results)
            assertFalse(job.isCompleted)
            slow.complete(Unit); job.join()
            assertTrue(0 in results)
            assertTrue(-4 in results)
            assertTrue(peak <= 2)
            assertEquals(5, results.size)
        } finally { slow.complete(Unit); scope.cancel() }
    }
    @Test fun canceledPlaylistFetchCannotPublishLateResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val results = mutableListOf<Int>()
        try {
            val job = scope.launch { ProgressiveSources.load(listOf(1), fetch = {
                withContext(NonCancellable) { gate.await() }; it
            }) { _, result -> results += result.getOrThrow() } }
            yield(); job.cancel(); gate.complete(Unit); job.join()
            assertTrue(results.isEmpty())
        } finally { gate.complete(Unit); scope.cancel() }
    }
}
