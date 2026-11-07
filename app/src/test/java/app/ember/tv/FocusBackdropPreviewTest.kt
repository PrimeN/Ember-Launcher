package app.ember.tv

import app.ember.tv.ui.widePreview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #56: only a wide tile picture stands in for the focused backdrop. */
class FocusBackdropPreviewTest {
    @Test fun postersAndSquareCoversWaitForTheWidePicture() {
        assertFalse("poster", widePreview(342, 513))
        assertFalse("square cover", widePreview(500, 500))
        assertFalse("not decoded", widePreview(-1, -1))
        assertTrue("landscape card", widePreview(780, 439))
        assertTrue("banner", widePreview(320, 180))
    }
}
