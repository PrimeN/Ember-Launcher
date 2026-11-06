package app.ember.tv

import app.ember.tv.data.ExitHistory
import org.junit.Assert.*
import org.junit.Test

/** #27: exits Android records outside Ember's exception handler reach the crash log. */
class ExitHistoryTest {
    private fun exit(reason: Int, importance: Int = 100, trace: String? = null, status: Int = 0) =
        ExitHistory.Exit(reason, 1_700_000_000_000, "desc", 512_000, 640_000, importance, "app.ember.tv", status, trace)

    @Test fun normalExitsAreSkippedAndFailuresKept() {
        for (normal in listOf(0, 1, 8, 10, 11, 12, 15, 16)) assertFalse("$normal", ExitHistory.worthRecording(normal))
        for (failure in listOf(2, 3, 4, 5, 6, 7, 9, 13, 14)) assertTrue("$failure", ExitHistory.worthRecording(failure))
    }

    @Test fun anrEntryNamesTheReasonMemoryAndMainThread() {
        val trace = "----- pid 1 -----\n\"Signal Catcher\" daemon\n  at x\n\n\"main\" prio=5 tid=1 Blocked\n  at app.ember.tv.Slow.run(Slow.kt:3)\n  at android.os.Looper.loop\n\n\"other\" prio=5\n  at y"
        val e = exit(6, trace = ExitHistory.mainThread(trace))
        assertEquals("Ember stopped: Not responding (ANR): Android closed Ember", ExitHistory.title(e))
        val text = ExitHistory.details(e)
        assertTrue(text, "Ember was on screen." in text)
        assertTrue(text, "500 MB PSS, 625 MB RSS" in text)
        assertTrue(text, "Slow.run" in text)
        assertFalse(text, "Signal Catcher" in text)
        assertFalse(text, "\"other\"" in text)
    }

    @Test fun backgroundMemoryKillsAreExplainedAndSigkillIsNamed() {
        assertTrue("normal" in ExitHistory.details(exit(3, importance = 400)))
        assertFalse("normal" in ExitHistory.details(exit(3, importance = 100)))
        assertTrue("low-memory killer" in ExitHistory.details(exit(2, status = 9)))
    }

    @Test fun tombstoneStringsAreReadableAndDeduplicated() {
        val bytes = byteArrayOf(8, 1) + "signal 11 (SIGSEGV)".toByteArray() + byteArrayOf(0, 18) +
            "/vendor/lib/libGLES_mali.so".toByteArray() + byteArrayOf(0) + "signal 11 (SIGSEGV)".toByteArray() + byteArrayOf(1, 2, 'a'.code.toByte())
        assertEquals("signal 11 (SIGSEGV)\n/vendor/lib/libGLES_mali.so", ExitHistory.readableStrings(bytes))
    }
}

/** #26: a refused server image resets private artwork once per sign-in, never in a loop. */
class RefusedArtworkTest {
    @Test fun repeatedRefusalsForOneSignInResetOnce() {
        val before = app.ember.tv.data.ArtworkCaches.revision.value
        assertTrue(app.ember.tv.data.ArtworkCaches.refused("realm-test-a"))
        val afterFirst = app.ember.tv.data.ArtworkCaches.revision.value
        assertTrue(afterFirst > before)
        repeat(20) { assertFalse(app.ember.tv.data.ArtworkCaches.refused("realm-test-a")) }
        assertEquals(afterFirst, app.ember.tv.data.ArtworkCaches.revision.value)
        // Another sign-in (new token) is a new realm and gets its own reset.
        assertTrue(app.ember.tv.data.ArtworkCaches.refused("realm-test-b"))
    }
}
