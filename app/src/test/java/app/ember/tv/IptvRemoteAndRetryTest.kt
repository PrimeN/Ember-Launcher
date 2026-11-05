package app.ember.tv

import app.ember.tv.iptv.*
import org.junit.Assert.*
import org.junit.Test

class IptvRemoteAndRetryTest {
    private fun channel(key: String, number: Int) = Channel(key, key.substringBefore(':'), key, "https://example.com/$key", number = number)
    @Test fun numberKeysAndNumpadMapWithoutConsumingDpadOrBack() {
        for (i in 0..9) { assertEquals("$i", LiveRemote.digit(7 + i)); assertEquals("$i", LiveRemote.digit(144 + i)) }
        assertNull(LiveRemote.digit(19)); assertNull(LiveRemote.digit(4))
    }
    @Test fun duplicateNumbersRequireChoiceAndOnlyVisibleChannelsAreCandidates() {
        val channels = listOf(channel("a:1", 7), channel("b:1", 7), channel("a:2", 8))
        assertEquals(channels.take(2), LiveRemote.matches(channels, "007"))
        assertEquals(listOf(channels[1]), LiveRemote.matches(channels.drop(1), "7"))
        assertTrue(LiveRemote.matches(channels, "9999999999999").isEmpty())
        assertTrue(LiveRemote.matches(channels, "").isEmpty())
    }
    @Test fun lastChannelTogglesAndHistoryNeverRestoresHiddenOrRemovedChannels() {
        val list = listOf(channel("a:1", 1), channel("a:2", 2), channel("a:3", 3))
        var visible = list
        var history = listOf("hidden:1", "a:3", "a:2")
        val session = LiveSession(list, "a:1", { visible }, {}, history = { history })
        assertEquals(list[2], session.previous())
        session.selectKey("a:2")
        assertEquals(list[0], session.previous())
        session.selectKey(session.previous()!!.key)
        assertEquals(list[1], session.previous())
        visible = listOf(list[0], list[2]); history = listOf("a:2", "a:3")
        assertEquals(list[2], session.previous())
        assertNull(session.selectKey("a:2"))
        assertEquals(listOf(list[2], list[0]), session.recent())
    }
    @Test fun authMissingAndInvalidRequestsAreNotRetried() {
        listOf(400, 401, 403, 404, 405, 422).forEach { assertFalse(LiveRetryPolicy.retryable(it)) }
        listOf(null, 408, 429, 500, 502, 503, 599).forEach { assertTrue(LiveRetryPolicy.retryable(it)) }
        assertEquals(500L, LiveRetryPolicy.delayMillis(0))
        assertEquals(1000L, LiveRetryPolicy.delayMillis(1))
        assertEquals(12_000L, LiveRetryPolicy.delayMillis(1, 12))
        assertEquals(60_000L, LiveRetryPolicy.delayMillis(100, Long.MAX_VALUE))
    }
    @Test fun retryAfterSupportsSecondsAndHttpDatesAndRejectsGarbage() {
        assertEquals(20L, LiveRetryPolicy.retryAfterSeconds("20"))
        assertEquals(10L, LiveRetryPolicy.retryAfterSeconds("Wed, 21 Oct 2015 07:28:10 GMT", 1_445_412_480_000L))
        assertEquals(0L, LiveRetryPolicy.retryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT", 1_445_412_500_000L))
        assertNull(LiveRetryPolicy.retryAfterSeconds("invalid"))
    }
    @Test fun budgetEnforcesRollingWindowCooldownAndInteractivePriority() {
        val budget = ProviderRequestBudget(windowMillis = 1000, maximum = 2)
        assertEquals(0L, budget.reserve(0, false)); assertEquals(0L, budget.reserve(10, false))
        assertEquals(980L, budget.reserve(20, false))
        assertEquals(0L, budget.reserve(1000, false))
        budget.coolDown(1000, 3000)
        budget.coolDown(1001, 100) // A shorter response cannot shorten an existing cooldown.
        assertEquals(2500L, budget.reserve(1500, true))
        budget.priority(1)
        assertEquals(100L, budget.reserve(4000, false))
        assertEquals(0L, budget.reserve(4000, true))
        budget.priority(-1)
        assertEquals(0L, budget.reserve(4000, false))
    }
    @Test fun duplicateXtreamAccountsShareBudgetButDifferentAccountsDoNot() {
        val first = IptvSource("one", "One", "xtream", "https://example.com", "user", "secret")
        assertEquals(Xtream.budgetKey(first), Xtream.budgetKey(first.copy(id = "two", password = "updated")))
        assertNotEquals(Xtream.budgetKey(first), Xtream.budgetKey(first.copy(username = "other")))
        assertFalse(Xtream.budgetKey(first).contains("secret"))
    }
    @Test fun sustainedPlaybackRearmsRetriesEvenWithoutAnotherReadyEvent() {
        val budget = LiveRetryBudget()
        assertEquals(0, budget.next(0)); assertEquals(1, budget.next(1000)); assertNull(budget.next(2000))
        budget.playback(true, 3000)
        assertEquals(0, budget.next(63_000))
    }
    @Test fun repeatedBriefReconnectsAndSlowFailuresCannotCreateInfiniteRetries() {
        val budget = LiveRetryBudget()
        assertEquals(0, budget.next(0))
        budget.playback(true, 1000); budget.playback(false, 2000)
        assertEquals(1, budget.next(100_000))
        budget.playback(true, 101_000); budget.playback(false, 102_000)
        assertNull(budget.next(200_000))
    }
    @Test fun switchingChannelsGetsAFreshBudgetAndStablePlaybackBeforeErrorRearmsIt() {
        val budget = LiveRetryBudget()
        budget.next(0); budget.next(1000)
        budget.playback(true, 2000); budget.playback(false, 62_000)
        assertEquals(0, budget.next(62_001))
        budget.next(62_002); budget.reset()
        assertEquals(0, budget.next(63_000))
    }

}
