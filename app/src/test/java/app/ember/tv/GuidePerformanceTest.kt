package app.ember.tv

import app.ember.tv.iptv.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GuidePerformanceTest {
    @Test fun binaryLookupAndMovementMatchLegacyAtGapsOverlapsAndBoundaries() {
        val random = Random(82)
        val programmes = List(400) { i ->
            val start = random.nextLong(-3_600_000, GuideWindow.DURATION + 3_600_000)
            Programme(start, start + random.nextLong(1, 3_600_000), "Programme $i")
        }
        val slots = GuideWindow.slots(programmes, 0)
        val times = slots.flatMap { listOf(it.from - 1, it.from, it.to - 1, it.to, it.to + 1) } +
            List(1000) { random.nextLong(-3_600_000, GuideWindow.DURATION + 3_600_000) }
        times.forEach { time ->
            val expected = slots.firstOrNull { time >= it.from && time < it.to } ?: slots.lastOrNull()?.takeIf { time >= it.to }
            assertEquals(expected, GuideWindow.at(slots, time))
            for (direction in -1..1) {
                val index = slots.indexOf(expected).coerceAtLeast(0)
                assertEquals(slots[(index + direction).coerceIn(0, slots.lastIndex)].from, GuideWindow.move(slots, time, direction))
            }
        }
        assertNull(GuideWindow.at(emptyList(), 0)); assertEquals(99L, GuideWindow.move(emptyList(), 99, 1))
    }

    @Test fun cacheSharesSummaryAndRowSlotsButInvalidatesReplacementGuideAndWindow() {
        val cache = GuideSlotCache()
        val guide = listOf(Programme(0, 3_600_000, "One"))
        val first = cache.slots("a", guide, 0)
        assertSame(first, cache.slots("a", guide, 0))
        val changed = listOf(Programme(0, 3_600_000, "Corrected"))
        val replacement = cache.slots("a", changed, 0)
        assertNotSame(first, replacement); assertEquals("Corrected", replacement.first().programme?.title)
        assertNotSame(replacement, cache.slots("a", changed, GuideWindow.HALF_HOUR))
    }

    @Test fun cacheIsBoundedAndRetainsMostRecentlyUsedChannel() {
        val cache = GuideSlotCache(2)
        val guide = listOf(Programme(0, 3_600_000, "One"))
        val a = cache.slots("a", guide, 0)
        val b = cache.slots("b", guide, 0)
        assertSame(a, cache.slots("a", guide, 0))
        cache.slots("c", guide, 0)
        assertSame(a, cache.slots("a", guide, 0))
        assertNotSame(b, cache.slots("b", guide, 0))
    }
}
