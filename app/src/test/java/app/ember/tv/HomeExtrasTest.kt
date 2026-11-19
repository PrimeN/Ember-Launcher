package app.ember.tv

import app.ember.tv.home.LauncherExtras
import app.ember.tv.home.NotificationFeed
import app.ember.tv.home.TvNotification
import app.ember.tv.screensaver.ScreensaverDrift
import app.ember.tv.ui.SettingsReset
import app.ember.tv.ui.SettingsTab
import org.junit.Assert.*
import org.junit.Test

/** Screensaver drift, Back on Home and the notifications list. */
class HomeExtrasTest {
    @Test fun screensaverTextStaysNearItsCornerAndMovesFurtherWithoutAPicture() {
        val random = kotlin.random.Random(7)
        repeat(500) {
            val (x, y) = ScreensaverDrift.next(random, wide = false)
            assertTrue(x in -24..24 && y in -16..16)
            val (wx, wy) = ScreensaverDrift.next(random, wide = true)
            assertTrue(wx in 0..240 && wy in -160..0)
        }
    }

    @Test fun backOnHomeAcceptsOnlyKnownChoicesAndResetsWithStartup() {
        assertEquals("none", LauncherExtras().homeBack)
        assertEquals("clock", LauncherExtras(homeBack = "clock").normalized().homeBack)
        assertEquals("none", LauncherExtras(homeBack = "reboot").normalized().homeBack)
        val changed = LauncherExtras(homeBack = "screensaver", notifications = true, lockedApps = setOf("a"))
        assertEquals("none", SettingsReset.extras(SettingsTab.DEFAULT_HOME, changed)!!.homeBack)
        val extras = SettingsReset.extras(SettingsTab.HOME_EXTRAS, changed)!!
        assertFalse(extras.notifications); assertEquals(setOf("a"), extras.lockedApps)
    }

    @Test fun notificationsLeaveOutOngoingEmberAndEmptyOnesNewestFirst() {
        fun n(key: String, pkg: String = "com.app", title: String = "Title", at: Long = 0) = TvNotification(key, pkg, title, "", at, true, null)
        val shown = NotificationFeed.visible(listOf(
            n("old", at = 1) to false, n("new", at = 5) to false, n("download", at = 9) to true,
            n("own", pkg = "app.ember.tv", at = 8) to false, n("blank", title = "", at = 7) to false, n("new", at = 5) to false,
        ), "app.ember.tv")
        assertEquals(listOf("new", "old"), shown.map { it.key })
        assertEquals(NotificationFeed.MAX, NotificationFeed.visible((1..30).map { n("k$it", at = it.toLong()) to false }, "x").size)
    }
}
