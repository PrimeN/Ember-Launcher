package app.ember.tv

import app.ember.tv.ui.FocusEdge
import org.junit.Assert.*
import org.junit.Test

/** #466: a focused item never grows far past its edges, so clipping lists don't cut it off. */
class FocusEdgeTest {
    @Test fun wideRowsGrowAFewPixelsSmallControlsKeepTheirZoom() {
        // A 1,800 px Settings row may grow 6 px a side: about 1.0067, not 1.04.
        val wide = FocusEdge.cap(1800, 6f)
        assertTrue(wide < 1.01f); assertEquals(1800 * (wide - 1f) / 2f, 6f, 0.01f)
        // A 140 px button can still grow 4 % (about 2.8 px a side).
        assertTrue(FocusEdge.cap(140, 6f) > 1.04f)
        // A 260 px poster with the card edge keeps 1.1.
        assertTrue(FocusEdge.cap(260, 14f) >= 1.1f)
        // Before measuring nothing is capped.
        assertEquals(Float.MAX_VALUE, FocusEdge.cap(0, 6f))
    }
}
