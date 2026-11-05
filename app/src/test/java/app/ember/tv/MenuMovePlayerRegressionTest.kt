package app.ember.tv

import app.ember.tv.data.AppOrder
import app.ember.tv.player.playerActivityAllowed
import app.ember.tv.player.usablePlayerLabel
import app.ember.tv.ui.AppMoveDraft
import org.junit.Assert.*
import org.junit.Test

class MenuMovePlayerRegressionTest {
    @Test fun movingAnAppAcrossRowsStaysInsideRealGridSlots() {
        assertEquals(3, AppOrder.target(3, 10, 4, horizontal = 1))
        assertEquals(4, AppOrder.target(4, 10, 4, horizontal = -1))
        assertEquals(2, AppOrder.target(2, 10, 4, vertical = -1))
        assertEquals(7, AppOrder.target(7, 10, 4, vertical = 1))
        assertEquals(9, AppOrder.target(5, 10, 4, vertical = 1))
        assertEquals(5, AppOrder.target(9, 10, 4, vertical = -1))
        assertEquals(8, AppOrder.target(9, 10, 4, horizontal = -1))
        assertEquals(0, AppOrder.target(0, 1, 4, horizontal = 1))
        assertEquals(-1, AppOrder.target(-1, 0, 4, vertical = 1))
    }
    @Test fun rapidMovesUseTheLatestDraftAndCancelDoesNotMutateTheOriginal() {
        val saved = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i")
        val draft = AppMoveDraft(saved, "b")
        assertTrue(draft.move(4, horizontal = 1))
        assertTrue(draft.move(4, horizontal = 1))
        assertFalse(draft.move(4, horizontal = 1))
        assertEquals(3, draft.index)
        assertTrue(draft.move(4, vertical = 1))
        assertEquals(7, draft.index)
        assertEquals(listOf("a", "c", "d", "e", "f", "g", "h", "b", "i"), draft.order)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"), saved)
    }
    @Test fun movingReconcilesInstallAndPermissionChangesWithoutRestoringRemovedApps() {
        val draft = AppMoveDraft(listOf("a", "b", "restricted", "removed"), "b")
        draft.move(4, horizontal = -1)
        draft.reconcile(listOf("a", "b", "new", "new"))
        assertEquals(listOf("b", "a", "new"), draft.order)
        draft.reconcile(listOf("a", "new"))
        assertEquals(-1, draft.index)
        assertFalse(draft.move(4, horizontal = 1))
    }
    @Test fun savingAMovedSubsetPreservesHiddenAndRestrictedSlots() {
        val persisted = listOf("a", "restricted", "b", "hidden", "c")
        val draft = AppMoveDraft(listOf("a", "b", "c"), "a")
        draft.move(4, horizontal = 1)
        draft.move(4, horizontal = 1)
        assertEquals(listOf("b", "restricted", "c", "hidden", "a"), AppOrder.merge(persisted, draft.order))
    }
    @Test fun playerInventoryRejectsRoutingActivitiesAndInaccessibleTargets() {
        fun allowed(pkg: String?, activity: String? = "sample.Player", exported: Boolean = true, enabled: Boolean = true, appEnabled: Boolean = true) =
            playerActivityAllowed(pkg, activity, "app.ember.tv", exported, enabled, appEnabled)
        assertTrue(allowed("org.videolan.vlc"))
        assertTrue(allowed("com.vendor.player"))
        assertFalse(allowed("android"))
        assertFalse(allowed("com.android.intentresolver"))
        assertFalse(allowed("app.ember.tv"))
        assertFalse(allowed("com.vendor", "com.android.internal.app.ResolverActivity"))
        assertFalse(allowed("com.vendor", "com.android.internal.app.ChooserActivity"))
        assertFalse(allowed("com.vendor", exported = false))
        assertFalse(allowed("com.vendor", enabled = false))
        assertFalse(allowed("com.vendor", appEnabled = false))
        assertFalse(allowed(null))
        assertFalse(allowed("com.vendor", activity = ""))
    }
    @Test fun placeholderPlayerLabelsFallBackButActualPlayerNamesAreRetained() {
        assertFalse(usablePlayerLabel(" None "))
        assertFalse(usablePlayerLabel("NONE"))
        assertFalse(usablePlayerLabel("null"))
        assertFalse(usablePlayerLabel(" "))
        assertTrue(usablePlayerLabel("External video player"))
        assertTrue(usablePlayerLabel("None Player"))
    }
}
