package app.ember.tv

import app.ember.tv.ui.UiSizes
import org.junit.Assert.*
import org.junit.Test

/** #38: Default is the former Smaller, with the former proportions on either side. */
class InterfaceSizeTest {
    @Test fun defaultIsTheFormerSmallerAndStepsKeepTheirRatios() {
        assertEquals(0.88f, UiSizes.screen("default"), 0.001f)
        assertEquals(0.88f, UiSizes.screen("unknown"), 0.001f)
        val ids = UiSizes.LABELS.map { it.first }
        assertEquals(listOf("xsmall", "small", "default", "large", "xlarge"), ids)
        val scales = ids.map(UiSizes::screen)
        assertEquals(scales.sorted(), scales)
        // Former ratios to Default: x0.76, x0.88, x1, x1.15, x1.3 (within rounding).
        listOf(.76f, .88f, 1f, 1.15f, 1.3f).zip(scales).take(4).forEach { (ratio, s) -> assertEquals(ratio * .88f, s, .015f) }
        // The former Default stays available.
        assertEquals(1f, UiSizes.screen("large"), 0.001f)
    }

    @Test fun savedSizesKeepAboutTheSameLookAndDefaultGetsTheSmallerOne() {
        val old = mapOf("xsmall" to .76f, "small" to .88f, "large" to 1.15f)
        for ((id, scale) in old) assertEquals(id, scale, UiSizes.screen(UiSizes.fromLegacy(id)), .015f)
        assertEquals("default", UiSizes.fromLegacy("default"))
        assertEquals("default", UiSizes.fromLegacy(null))
        assertEquals("xlarge", UiSizes.fromLegacy("xlarge"))
    }

    @Test fun textSizeIsUnchanged() {
        assertEquals(1f, UiSizes.text("default"), 0f)
        assertEquals(.9f, UiSizes.text("small"), 0f)
    }
}
