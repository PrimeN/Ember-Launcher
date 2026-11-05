package app.ember.tv

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import app.ember.tv.data.AppAccess
import app.ember.tv.data.AppEntry
import org.junit.Assert.*
import org.junit.Test

class AppAccessTest {
    private fun app(pkg: String) = AppEntry(pkg, pkg, Intent(), ColorDrawable())
    private val apps = listOf(app("allowed"), app("hidden"), app("restricted"))

    @Test fun showHiddenCannotExposeAProfileRestrictedAppOrItsCount() {
        val access = AppAccess(apps, setOf("allowed", "hidden"), emptySet(), setOf("hidden", "restricted"))
        assertEquals(listOf("allowed"), access.page(false).map { it.pkg })
        assertEquals(listOf("allowed", "hidden"), access.page(true).map { it.pkg })
        assertEquals(1, access.hiddenOnPage)
        assertFalse(access.canLaunch("restricted"))
    }

    @Test fun removingFromHomeStillAllowsLaunchingFromTheAppsPage() {
        val access = AppAccess(apps, null, setOf("hidden"), emptySet())
        assertFalse(access.home.any { it.pkg == "hidden" })
        assertTrue(access.page(false).any { it.pkg == "hidden" })
        assertTrue(access.canLaunch("hidden"))
        assertFalse(access.canLaunch("no-longer-installed"))
    }

    @Test fun hiddenAppsLeaveHomeAndTheAppsPageButRemoveFromHomeOnlyLeavesHome() {
        val access = AppAccess(apps, setOf("allowed", "hidden"), emptySet(), setOf("hidden"))
        assertFalse(access.home.any { it.pkg == "hidden" })
        assertFalse(access.page(false).any { it.pkg == "hidden" })
        assertTrue(access.page(true).any { it.pkg == "hidden" })
        assertTrue(access.canLaunch("hidden"))
        val removed = AppAccess(apps, null, setOf("allowed"), emptySet())
        assertFalse(removed.home.any { it.pkg == "allowed" })
        assertTrue(removed.page(false).any { it.pkg == "allowed" })
        val locked = AppAccess(apps, emptySet(), emptySet(), emptySet())
        assertTrue(locked.home.isEmpty())
        assertTrue(locked.page(true).isEmpty())
        assertFalse(locked.canLaunch("allowed"))
    }
}
