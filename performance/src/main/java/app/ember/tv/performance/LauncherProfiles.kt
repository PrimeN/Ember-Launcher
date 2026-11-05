package app.ember.tv.performance

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

internal const val PACKAGE = "app.ember.tv.benchmark"

internal fun MacrobenchmarkScope.startLauncher() {
    startActivityAndWait(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PACKAGE, "app.ember.tv.MainActivity"))
        .addCategory(Intent.CATEGORY_HOME))
    check(device.wait(Until.hasObject(By.text("Home")), 5000)) {
        "Dismiss setup and disable the startup profile prompt before profiling."
    }
}

/** Seed an English adult profile, dismiss setup and choose the desired device mode first. */
internal fun MacrobenchmarkScope.openNavigation(label: String) {
    val target = device.wait(Until.findObject(By.desc(label)), 3000)
        ?: device.wait(Until.findObject(By.text(label)), 3000)
    checkNotNull(target) { "$label navigation is unavailable. Use an adult English profile with the tab enabled." }.click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.openSettings() {
    openNavigation("Quick settings")
    checkNotNull(device.wait(Until.findObject(By.textStartsWith("Ember settings")), 3000)) {
        "The Ember settings tile is unavailable in Quick settings."
    }.click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.browseHome() {
    device.pressDPadDown()
    repeat(8) { device.pressDPadRight() }
    repeat(5) { device.pressDPadLeft() }
    repeat(3) { device.pressDPadDown() }
    repeat(3) { device.pressDPadUp() }
    device.waitForIdle()
}

/** Optional populated-guide journey, using the same labeled overflow entry as the remote UI. */
internal fun MacrobenchmarkScope.jumpGuide(label:String) {
    openNavigation("Live TV options")
    openNavigation(label)
    device.pressDPadRight();device.pressDPadLeft()
    device.waitForIdle()
}

@RunWith(AndroidJUnit4::class)
@LargeTest
class LauncherProfiles {
    @get:Rule val profile = BaselineProfileRule()

    @Before fun requireOriginalNames() {
        check(InstrumentationRegistry.getArguments().getString("profileGeneration") == "true") {
            "Use -PgenerateStartupProfiles=true so collected rules contain original, not R8-obfuscated names."
        }
    }

    @Test fun startup() = profile.collect(PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startLauncher()
        device.waitForIdle()
    }

    @Test fun browsing() = profile.collect(PACKAGE, includeInStartupProfile = false) {
        startLauncher()
        browseHome()
        openNavigation("Discover")
        device.pressDPadDown(); device.pressDPadRight(); device.pressDPadLeft()
        openNavigation("Search")
        device.pressDPadDown(); device.pressDPadRight(); device.pressBack()
        openNavigation("Home")
        openSettings()
        repeat(4) { device.pressDPadDown() }
        repeat(4) { device.pressDPadUp() }
        device.pressBack()
        // Live needs a real seeded feed. Never generate a pretend guide journey on an empty TV.
        if (InstrumentationRegistry.getArguments().getString("includeLive") == "true") {
            openNavigation("Live")
            repeat(5) { device.pressDPadDown() }
            repeat(8) { device.pressDPadRight() }
            repeat(8) { device.pressDPadLeft() }
            jumpGuide("Tonight")
            jumpGuide("Jump to now")
        }
    }
}
