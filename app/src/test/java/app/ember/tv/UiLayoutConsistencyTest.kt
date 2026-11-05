package app.ember.tv

import app.ember.tv.ui.MenuSizing
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class UiLayoutConsistencyTest {
    @Test fun largeTvDialogsKeepTheirIntendedSize() {
        assertEquals(680.dp to 540.dp,MenuSizing.dialogSize(1872.dp,1032.dp,680.dp,540.dp))
        assertEquals(430.dp to 540.dp,MenuSizing.dialogSize(912.dp,1032.dp,430.dp,540.dp))
    }
    @Test fun keyboardAndSmallWindowsConstrainTheDialogInsteadOfClippingControls() {
        assertEquals(432.dp to 192.dp,MenuSizing.dialogSize(432.dp,192.dp,680.dp,540.dp))
        assertEquals(272.dp to 312.dp,MenuSizing.dialogSize(272.dp,312.dp,380.dp,540.dp))
    }
    @Test fun collapsedOrInvalidAvailableBoundsNeverBecomeNegativeSizes() {
        assertEquals(0.dp to 0.dp,MenuSizing.dialogSize((-20).dp,0.dp,680.dp,540.dp))
        assertEquals(0.dp to 0.dp,MenuSizing.dialogSize(200.dp,300.dp,(-1).dp,(-1).dp))
    }
    @Test fun quickActionsUseOneColumnForEnlargedTextAndNarrowWindows() {
        assertEquals(1,MenuSizing.quickColumns(384.dp,1.5f))
        assertEquals(1,MenuSizing.quickColumns(280.dp,1f))
    }
    @Test fun ordinaryQuickActionsKeepTheCompactTwoColumnGrid() {
        assertEquals(2,MenuSizing.quickColumns(384.dp,1f))
        assertEquals(2,MenuSizing.quickColumns(320.dp,1.2f))
    }
}
