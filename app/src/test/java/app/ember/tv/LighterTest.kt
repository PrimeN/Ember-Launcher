package app.ember.tv

import app.ember.tv.system.Lighter
import org.junit.Assert.assertEquals
import org.junit.Test

/** #473: reading which apps may run in the background. */
class LighterTest {
    @Test fun readsDeniedApps() {
        val out = """
            @com.example.music
            No operations.
            @com.example.video
            RUN_ANY_IN_BACKGROUND: ignore; time=+2d3h ago
            @com.example.news
            RUN_ANY_IN_BACKGROUND: allow
            @com.example.store
            RUN_ANY_IN_BACKGROUND: deny
        """.trimIndent()
        assertEquals(setOf("com.example.video", "com.example.store"), Lighter.parseDenied(out))
    }

    @Test fun extrasNeverIncludeHomeOrCasting() {
        val pkgs = Lighter.EXTRAS.map { it.pkg }
        listOf("com.google.android.apps.tv.launcherx", "com.google.android.apps.mediashell", "com.google.android.tv.remote.service")
            .forEach { assert(it !in pkgs) }
    }
}
