package app.ember.tv

import app.ember.tv.data.CustomPerformance
import app.ember.tv.data.DevicePerformance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #51: the phone offers what the TV offers. */
class PhoneSettingLimitsTest {
    @Test fun rowsAheadRangeIsWhatTheTvKeeps() {
        val range = CustomPerformance.PREFETCH
        assertEquals(range.last, CustomPerformance(prefetch = range.last + 1).normalized().prefetch)
        assertEquals(range.first, CustomPerformance(prefetch = range.first - 1).normalized().prefetch)
        range.forEach { assertEquals(it, CustomPerformance(prefetch = it).normalized().prefetch) }
    }

    @Test fun ultraIsOnlyOfferedOnUhdDisplays() {
        assertTrue(DevicePerformance.choices(false).none { it.first == "ultra" })
        assertTrue(DevicePerformance.choices(true).any { it.first == "ultra" })
    }
}
