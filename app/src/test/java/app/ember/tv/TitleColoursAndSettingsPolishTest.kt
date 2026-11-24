package app.ember.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.ember.tv.data.PreferenceKeys
import app.ember.tv.ui.focusTint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #417 title colours on focus and #418 settings polish. */
@RunWith(RobolectricTestRunner::class)
class TitleColoursAndSettingsPolishTest {
    private fun hsv(c: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }

    @Test fun darkArtworkIsLiftedToAReadableRing() {
        val ring = hsv(focusTint(Color(0xFF101A40)))
        assertTrue(ring[2] >= 0.88f)
        assertTrue(ring[1] in 0.35f..0.85f)
        val grey = hsv(focusTint(Color(0xFF808080)))
        assertTrue(grey[1] >= 0.35f - 1e-3f)
    }

    @Test fun titleColoursIsOffByDefaultAndBackedUp() {
        assertFalse(PreferenceKeys.TITLE_FOCUS_COLOURS.default)
        assertTrue(PreferenceKeys.TITLE_FOCUS_COLOURS in PreferenceKeys.all)
    }
}
