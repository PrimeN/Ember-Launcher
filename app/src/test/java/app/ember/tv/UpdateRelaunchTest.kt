package app.ember.tv

import android.app.Application
import android.content.Intent
import app.ember.tv.home.LauncherBootReceiver
import app.ember.tv.home.StartupEvents
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #465: after Ember updates itself, it opens Home again instead of leaving Android's Settings showing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateRelaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun anUpdateEmberStartedReopensHomeOnce() {
        StartupEvents.updateStarted(context)
        LauncherBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val opened = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened?.component?.className)
        assertTrue(opened!!.hasCategory(Intent.CATEGORY_HOME))
        assertTrue("the service also knows", StartupEvents.pendingUpdate(context))
        assertFalse("only once", StartupEvents.takeUpdate(context))
    }

    @Test fun anUpdateFromElsewhereLeavesTheScreenAlone() {
        LauncherBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(context).nextStartedActivity)
        assertFalse(StartupEvents.pendingUpdate(context))
    }

    @Test fun anOldUpdateRequestDoesNotCount() {
        context.getSharedPreferences("home_runtime", 0).edit().putLong("update_at", System.currentTimeMillis() - StartupEvents.UPDATE_WINDOW_MS - 1000).commit()
        assertFalse(StartupEvents.takeUpdate(context))
    }
}
