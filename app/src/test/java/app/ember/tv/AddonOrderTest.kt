package app.ember.tv

import org.junit.Assert.*
import org.junit.Test

/** #267: addons move to change which is asked first. */
class AddonOrderTest {
    private val list = listOf("a", "b", "c", "d")

    @Test fun anAddonMovesByTheStepGiven() {
        assertEquals(listOf("c", "a", "b", "d"), AddonOrder.moved(list, "c", -2))
        assertEquals(listOf("b", "c", "d", "a"), AddonOrder.moved(list, "a", 10))
        assertEquals(listOf("d", "a", "b", "c"), AddonOrder.moved(list, "d", -3))
    }

    @Test fun nothingMovesPastTheEndsOrForAnUnknownAddon() {
        assertNull(AddonOrder.moved(list, "a", -1))
        assertNull(AddonOrder.moved(list, "d", 1))
        assertNull(AddonOrder.moved(list, "x", 1))
    }
}
