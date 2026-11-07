package app.ember.tv

import app.ember.tv.tracker.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WatchedFeedbackTest {
    private fun tracker(id: String, fail: Boolean) = object : Tracker {
        override val id = id
        override val name = id
        override val blurb = ""
        override val mode = LinkMode.DEVICE_CODE
        override val configured = true
        override val scrobbles = true
        override fun isConnected() = true
        override fun accountLabel(): String? = null
        override fun disconnect() {}
        override suspend fun continueWatching() = emptyList<TrackedItem>()
        override suspend fun watchlist() = emptyList<TrackedItem>()
        override suspend fun scrobble(item: TrackedItem, state: ScrobbleState, progress: Float) { if (fail) error("unavailable") }
    }
    @Test fun feedbackNamesOnlySuccessfulEnabledServices() = runBlocking {
        val hub = TrackerHub(listOf(tracker("completed", false), tracker("failed", true), tracker("quiet", false), tracker("hidden", false)))
        // Hidden from Home still reports; only "Report what I watch" off stops it (#80).
        hub.quiet = setOf("quiet")
        hub.disabled = setOf("hidden")
        assertEquals(listOf("completed", "hidden"), hub.scrobble(TrackedItem("one", "Film"), ScrobbleState.STOP, 1f))
    }
}
