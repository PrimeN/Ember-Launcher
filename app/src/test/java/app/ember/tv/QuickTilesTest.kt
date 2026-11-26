package app.ember.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.ember.tv.ui.QuickTiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** #382: the mini panel's tiles never share the panel's own colour. */
class QuickTilesTest {
    @Test fun tilesStandApartOnDarkAndLightPanels() {
        for (panel in listOf(Color(0xFF1A1B1E), Color(0xFF0B0B0C), Color(0xFF232937), Color(0xFFE3E5E8), Color(0xFFF4F1EC), Color(0x99202020))) {
            val gap = kotlin.math.abs(QuickTiles.fill(panel).luminance() - panel.copy(alpha = 1f).luminance())
            assertTrue("panel $panel gap $gap", gap > .012f)
            assertTrue(QuickTiles.edge(panel).alpha > .1f)
        }
    }
}
