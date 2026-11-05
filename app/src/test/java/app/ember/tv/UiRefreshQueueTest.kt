package app.ember.tv

import app.ember.tv.state.UiRefreshQueue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UiRefreshQueueTest {
    @Test fun cachedFirstPresentationAndVisibleWindowAreRequiredAndDuplicatesCoalesce() = runBlocking {
        withTimeout(3000) {
            var calls = 0
            val queue = UiRefreshQueue(this, { true }, { 1000L }, { })
            val first = queue.submit("weather", waitForIdle = false) { calls++ }
            assertSame(first, queue.submit("weather", waitForIdle = false) { calls += 100 })
            yield(); assertEquals(0, calls)
            queue.presented(); yield(); assertEquals(0, calls)
            queue.setActive(true); first.join(); assertEquals(1, calls)
            queue.submit("weather", waitForIdle = false) { calls++ }.join()
            assertEquals(2, calls)
        }
    }

    @Test fun lowRunsOneGroupAndHighRunsTwoWithoutDroppingPendingWork() = runBlocking {
        withTimeout(3000) {
            for (low in listOf(true, false)) {
                val queue = UiRefreshQueue(this, { low })
                queue.presented(); queue.setActive(true)
                val releases = List(3) { CompletableDeferred<Unit>() }
                val entered = mutableListOf<Int>()
                val tasks = List(3) { i -> queue.submit("task$i", waitForIdle = false) { entered += i; releases[i].await() } }
                repeat(5) { yield() }
                assertEquals(if (low) 1 else 2, entered.size)
                releases[0].complete(Unit)
                while (entered.size < if (low) 2 else 3) yield()
                releases.forEach { it.complete(Unit) }
                tasks.joinAll(); assertEquals(listOf(0, 1, 2), entered.sorted())
            }
        }
    }

    @Test fun recentInputDefersAutomaticWorkButCannotStarveFreshness() = runBlocking {
        withTimeout(3000) {
            var clock = 0L
            var pauses = 0
            lateinit var queue: UiRefreshQueue
            queue = UiRefreshQueue(this, { true }, { clock }, { ms ->
                clock += ms; pauses++; queue.interaction()
            })
            queue.presented(); queue.setActive(true)
            var ran = false
            queue.submit("trackers") { ran = true }.join()
            assertTrue(ran); assertEquals(10_000L, clock); assertTrue(pauses > 1)
        }
    }

    @Test fun idleWorkRunsWithoutAnArtificialDelayAndForegroundCatalogsBypassIdle() = runBlocking {
        withTimeout(3000) {
            var clock = 0L
            var pauses = 0
            val queue = UiRefreshQueue(this, { false }, { clock }, { clock += it; pauses++ })
            queue.presented(); queue.setActive(true)
            queue.interaction()
            queue.submit("catalogs", waitForIdle = false) { }.join()
            assertEquals(0, pauses)
            clock = 1000
            queue.submit("weather") { }.join()
            assertEquals(0, pauses)
        }
    }

    @Test fun cancellationReleasesCapacityAndHiddenWindowDoesNotStartPendingWork() = runBlocking {
        withTimeout(3000) {
            val queue = UiRefreshQueue(this, { true })
            queue.presented(); queue.setActive(true)
            val entered = CompletableDeferred<Unit>()
            val first = queue.submit("one", waitForIdle = false) { entered.complete(Unit); awaitCancellation() }
            entered.await()
            var nextRan = false
            val second = queue.submit("two", waitForIdle = false) { nextRan = true }
            queue.setActive(false); first.cancelAndJoin()
            repeat(5) { yield() }; assertFalse(nextRan)
            queue.setActive(true); second.join(); assertTrue(nextRan)
        }
    }

    @Test fun switchingToLowLimitsSubsequentWorkWhileExistingHighTasksFinish() = runBlocking {
        withTimeout(3000) {
            var low = false
            val queue = UiRefreshQueue(this, { low })
            queue.presented(); queue.setActive(true)
            val a = CompletableDeferred<Unit>(); val b = CompletableDeferred<Unit>()
            var entered = 0
            val first = queue.submit("one", waitForIdle = false) { entered++; a.await() }
            val second = queue.submit("two", waitForIdle = false) { entered++; b.await() }
            while (entered < 2) yield()
            low = true
            var thirdRan = false
            val third = queue.submit("three", waitForIdle = false) { thirdRan = true }
            a.complete(Unit); first.join(); repeat(5) { yield() }; assertFalse(thirdRan)
            b.complete(Unit); second.join(); third.join(); assertTrue(thirdRan)
        }
    }

    @Test fun returningAfterLongAbsenceGetsANewInputSettleWindow() = runBlocking {
        withTimeout(3000) {
            var clock = 0L
            var pauses = 0
            val hidden = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            lateinit var queue: UiRefreshQueue
            queue = UiRefreshQueue(this, { true }, { clock }, { ms ->
                pauses++
                if (pauses == 1) {
                    queue.setActive(false); hidden.complete(Unit); resume.await()
                } else clock += ms
            })
            queue.presented(); queue.setActive(true)
            var ran = false
            val work = queue.submit("weather") { ran = true }
            hidden.await(); clock = 60_000; assertFalse(ran)
            queue.setActive(true); resume.complete(Unit); work.join()
            assertTrue(ran); assertEquals(60_700L, clock); assertEquals(2, pauses)
        }
    }
}
