package app.ember.tv

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #441: HomeViewModel's early init blocks run before profiles, widgets and the lists exist. A job
 * started there with the default (immediate) dispatcher runs at once and crashes Ember on launch,
 * which on a TV shows as a black screen. Jobs there must be dispatched (Dispatchers.Main).
 */
class HomeStartupOrderTest {
    @Test fun earlyInitJobsAreDispatched() {
        val source = File("src/main/java/app/ember/tv/HomeViewModel.kt").readLines()
        val profilesAt = source.indexOfFirst { it.contains("val profiles: ProfilesState") }
        assertTrue("profiles declaration not found", profilesAt > 0)
        var depth = 0; var inInit = false
        val offenders = ArrayList<String>()
        for ((i, line) in source.take(profilesAt).withIndex()) {
            if (!inInit && line.trimStart().startsWith("init {")) { inInit = true; depth = 0 }
            if (inInit) {
                if (line.contains("viewModelScope.launch") && !line.contains("Dispatchers.Main") && !line.contains("Dispatchers.IO") && !line.contains("Dispatchers.Default"))
                    offenders += "line ${i + 1}: ${line.trim()}"
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 0) inInit = false
            }
        }
        assertTrue("Immediate jobs before profiles exist:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
