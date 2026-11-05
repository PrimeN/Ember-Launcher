package app.ember.tv

import app.ember.tv.screensaver.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScreensaverTest {
    @Test fun launchKeyUpDoesNotDismissButAFreshNavigationPressDoes() {
        val exit = ScreensaverExit(setOf(1, 2))
        assertFalse(exit.handle(1, false))
        assertFalse(exit.handle(1, true))
        assertFalse(exit.handle(1, true))
        assertTrue(exit.handle(1, false))
        assertFalse(exit.handle(1, false))
        assertFalse(exit.handle(2, true))
        assertFalse(exit.handle(2, false, cancelled = true))
        assertFalse(exit.handle(99, true))
        assertFalse(exit.handle(99, false))
    }
    @Test fun disabledSourcesNeverLoadAndOneFailingSourceDoesNotHideOthers() {
        val loaded = mutableListOf<String>()
        val result = ScreensaverSlides.select(setOf("home", "photos", "art", "unknown")) { source ->
            loaded += source
            when (source) {
                "photos" -> error("Permission revoked")
                "art" -> listOf(Slide("image", "Duplicate"), Slide("", "Invalid"), Slide("art", "Art"))
                else -> listOf(Slide("image", "Home"))
            }
        }
        assertEquals(listOf("home", "art", "photos"), loaded)
        assertEquals(listOf("image", "art"), result.map { it.image })
        assertTrue(ScreensaverSlides.select(emptySet()) { error("Disabled source was loaded") }.isEmpty())
    }
    @Test fun stoppedLateLoaderCannotPresentDetachedViews() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val frames = mutableListOf<Slide?>()
        val session = ScreensaverSession(scope, {
            withContext(NonCancellable) { gate.await() }; listOf(Slide("late", "Late"))
        }, { frames += it })
        try {
            session.start(); yield(); session.stop(); gate.complete(Unit); yield()
            assertTrue(frames.isEmpty())
        } finally { gate.complete(Unit); session.stop(); scope.cancel() }
    }
    @Test fun restartingSessionRejectsThePreviousLoadAndPresentsTheNewOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val frames = mutableListOf<Slide?>()
        val session = ScreensaverSession(scope, {
            if (++calls == 1) { withContext(NonCancellable) { gate.await() }; listOf(Slide("old", "Old")) }
            else listOf(Slide("new", "New"))
        }, { frames += it })
        try {
            session.start(); yield(); session.start(); yield(); gate.complete(Unit); yield()
            assertEquals(listOf("new"), frames.map { it?.image })
        } finally { gate.complete(Unit); session.stop(); scope.cancel() }
    }
    @Test fun emptyAndFailedFeedStillPresentClockMode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val frames = mutableListOf<Slide?>()
        var fail = false
        val session = ScreensaverSession(scope, { if (fail) error("Bad saved data"); emptyList() }, { frames += it })
        try {
            session.start(); yield(); assertEquals(listOf<Slide?>(null), frames)
            session.stop(); frames.clear(); fail = true
            session.start(); yield(); assertEquals(listOf<Slide?>(null), frames)
        } finally { session.stop(); scope.cancel() }
    }
    @Test fun rotationIsSingleAndStopsUntilExplicitlyRestarted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val frames = mutableListOf<String?>()
        val session = ScreensaverSession(scope, { listOf(Slide("one", "One"), Slide("two", "Two")) }, {
            frames += it?.image
        }, waitNext = { ticks.receive() })
        try {
            session.start(); yield()
            assertEquals(listOf("one"), frames)
            ticks.send(Unit); yield()
            assertEquals(listOf("one", "two"), frames)
            session.stop(); ticks.send(Unit); yield()
            assertEquals(2, frames.size)
            ticks.tryReceive()
            session.start(); yield()
            assertEquals("one", frames.last())
        } finally { session.stop(); ticks.close(); scope.cancel() }
    }
    @Test fun emptyFeedIsRetriedSoLatePicturesAppearWithoutReopening() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticks = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var pictures = emptyList<Slide>()
        val frames = mutableListOf<Slide?>()
        val session = ScreensaverSession(scope, { pictures }, { frames += it }, waitNext = { ticks.receive() })
        try {
            session.start(); yield()
            assertNull(frames.single())
            pictures = listOf(Slide("ready", "Ready"))
            ticks.send(Unit); yield()
            assertEquals("ready", frames.last()?.image)
        } finally { session.stop(); ticks.close(); scope.cancel() }
    }
}
