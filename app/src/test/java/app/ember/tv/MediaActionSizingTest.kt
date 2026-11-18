package app.ember.tv

import androidx.compose.ui.unit.dp
import app.ember.tv.ui.MenuSizing
import app.ember.tv.ui.inlineDetailsActionWidth
import org.junit.Assert.*
import org.junit.Test

class MediaActionSizingTest {
    @Test fun inlinePrimaryLabelsAlwaysFitEvenWhenResumeTextIsLarge() {
        for (label in listOf(30, 80, 160, 320, 640)) for (detail in listOf(0, 100, 1000)) for (icon in listOf(false, true)) {
            val primary = maxOf(label.dp + if (icon) 54.dp else 24.dp, 72.dp)
            val width = inlineDetailsActionWidth(label.dp, detail.dp, icon)
            assertTrue("The primary label must not wrap to accommodate source details", width >= primary)
            if (primary >= app.ember.tv.ui.INLINE_ACTION_MAX || detail == 0) assertEquals(primary, width)
            if (primary < app.ember.tv.ui.INLINE_ACTION_MAX) assertTrue(width <= app.ember.tv.ui.INLINE_ACTION_MAX)
        }
    }

    /** #332: the Play button only grows into free space, so the last action is never pushed off screen. */
    @Test fun playButtonLeavesRoomForTheOtherActions() {
        assertEquals(224.dp, app.ember.tv.ui.playActionCap(500.dp, 72.dp + 52.dp * 3, 4))
        assertEquals(app.ember.tv.ui.INLINE_ACTION_MAX, app.ember.tv.ui.playActionCap(1200.dp, 72.dp + 52.dp * 3, 4))
        assertEquals(282.dp, app.ember.tv.ui.playActionCap(550.dp, 72.dp + 52.dp * 3, 4))
    }

    @Test fun menusFitLongActionsInsteadOfTruncatingAtFourHundredDp() {
        assertEquals(594.dp, MenuSizing.actionMenuWidth(520.dp, 60.dp, 900.dp))
        assertEquals(874.dp, MenuSizing.actionMenuWidth(800.dp, 60.dp, 1000.dp))
        assertEquals(320.dp, MenuSizing.actionMenuWidth(520.dp, 60.dp, 320.dp))
        assertEquals(0.dp, MenuSizing.actionMenuWidth(520.dp, 60.dp, 0.dp))
        assertEquals(MenuSizing.actionMenuWidth(520.dp, 200.dp, 900.dp),
            MenuSizing.actionMenuWidth(520.dp, 2000.dp, 900.dp))
    }

    @Test fun actionsUseOneLineAndReserveTheirActualIconAndPaddingSpace() {
        assertEquals("Remove from Continue Watching", MenuSizing.actionLabel("Remove from\nContinue\tWatching"))
        val label = 400.dp
        val width = MenuSizing.actionMenuWidth(label, 60.dp, 900.dp)
        assertTrue(width - MenuSizing.ACTION_DIALOG_PADDING * 2 - MenuSizing.ACTION_PADDING * 2 -
            MenuSizing.ACTION_ICON - MenuSizing.ACTION_GAP >= label)
    }
}
