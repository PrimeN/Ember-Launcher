package app.ember.tv

import app.ember.tv.ui.moveOptions
import app.ember.tv.ui.moved
import app.ember.tv.ui.nearEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared Move control (#107) and automatic paging (#109). */
class UiConsistencyTest {
    @Test fun moveOffersOnlyWhatMakesSense() {
        assertEquals(listOf("Down" to 1, "To the bottom" to 4), moveOptions(0, 4))
        assertEquals(listOf("To the top" to -2, "Up" to -1, "Down" to 1, "To the bottom" to 2), moveOptions(2, 4))
        assertEquals(listOf("To the front" to -4, "Left" to -1), moveOptions(4, 4, horizontal = true))
        assertEquals(listOf("Up" to -1), moveOptions(1, 1))
    }

    @Test fun movedClampsToTheList() {
        assertEquals(listOf("c", "a", "b"), listOf("a", "b", "c").moved(2, -5))
        assertEquals(listOf("b", "c", "a"), listOf("a", "b", "c").moved(0, 2))
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(5, 1))
    }

    @Test fun theNextPageLoadsNearTheEnd() {
        assertTrue(nearEnd(92, 100))
        assertFalse(nearEnd(10, 100))
        assertTrue(nearEnd(0, 3))
        assertFalse(nearEnd(5, 0))
    }
}
