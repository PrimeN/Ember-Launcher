package app.ember.tv

import androidx.compose.ui.unit.dp
import app.ember.tv.ui.MenuSizing
import org.junit.Assert.assertEquals
import org.junit.Test

class MenuSizingTest {
    @Test fun theProfileMenuFitsItsContentWithinBounds() {
        assertEquals(236.dp, MenuSizing.profileMenuWidth(236.dp, 960.dp, 1f))
        assertEquals(200.dp, MenuSizing.profileMenuWidth(150.dp, 960.dp, 1f))
        assertEquals(288.dp, MenuSizing.profileMenuWidth(400.dp, 960.dp, 1f))
        assertEquals(328.dp, MenuSizing.profileMenuWidth(400.dp, 960.dp, 1.3f))
        assertEquals(180.dp, MenuSizing.profileMenuWidth(236.dp, 180.dp, 1f))
    }

    @Test fun quickSettingsTilesShareOneHeightThatGrowsWithText() {
        assertEquals(104.dp, MenuSizing.quickTileHeight(1f))
        assertEquals(104.dp, MenuSizing.quickTileHeight(0.9f))
        assertEquals(156.dp, MenuSizing.quickTileHeight(1.5f))
    }
}
