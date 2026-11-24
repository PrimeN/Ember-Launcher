package app.ember.tv

import androidx.compose.ui.graphics.Color
import app.ember.tv.data.PreferenceKeys
import app.ember.tv.ui.focusTint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #417 title colours on focus and #418 settings polish. */
class TitleColoursAndSettingsPolishTest {
    private fun hsv(c: Color): FloatArray {
        val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue)
        return floatArrayOf(0f, if (max == 0f) 0f else (max - min) / max, max)
    }

    @Test fun darkArtworkIsLiftedToAReadableRing() {
        val ring = hsv(focusTint(Color(0xFF101A40)))
        assertTrue(ring[2] >= 0.88f)
        assertTrue(ring[1] in 0.34f..0.86f)
        val grey = hsv(focusTint(Color(0xFF808080)))
        assertTrue(grey[1] >= 0.35f - 1e-3f)
    }

    @Test fun titleColoursIsOffByDefaultAndBackedUp() {
        assertFalse(PreferenceKeys.TITLE_FOCUS_COLOURS.default)
        assertTrue(PreferenceKeys.TITLE_FOCUS_COLOURS in PreferenceKeys.all)
    }
}
