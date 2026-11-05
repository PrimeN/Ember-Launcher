package app.ember.tv

import app.ember.tv.ui.loadHeroArtwork
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HeroPreviewRecoveryTest {
    @Test fun failedSharpRequestKeepsThePendingPreviewAlive() = runBlocking {
        val preview = CompletableDeferred<String?>(); val shown = mutableListOf<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) { loadHeroArtwork({ preview.await() }, { null }, shown::add) }
        yield(); assertTrue(result.isActive)
        preview.complete("preview"); assertNull(result.await()); assertEquals(listOf("preview"), shown)
    }

    @Test fun sharpSuccessCancelsThePreviewAndRemainsTheFinalResult() = runBlocking {
        val started = CompletableDeferred<Unit>(); var cancelled = false; val shown = mutableListOf<String>()
        val result = loadHeroArtwork<String>(
            preview = { started.complete(Unit); try { awaitCancellation() } finally { cancelled = true } },
            sharp = { started.await(); "sharp" }, publishPreview = shown::add)
        assertEquals("sharp", result); assertTrue(cancelled); assertTrue(shown.isEmpty())
    }

    @Test fun completedPreviewCanBeDisplayedWhileSharpIsStillLoading() = runBlocking {
        val sharp = CompletableDeferred<String?>(); val shown = mutableListOf<String>()
        val result = async(start = CoroutineStart.UNDISPATCHED) { loadHeroArtwork({ "preview" }, { sharp.await() }, shown::add) }
        yield(); assertEquals(listOf("preview"), shown); assertTrue(result.isActive)
        sharp.complete("sharp"); assertEquals("sharp", result.await())
    }

    @Test fun cancelledSceneNeverPublishesALatePreview() = runBlocking {
        val started = CompletableDeferred<Unit>(); val late = CompletableDeferred<String?>(); val shown = mutableListOf<String>()
        val result = async { loadHeroArtwork(
            preview = { started.complete(Unit); withContext(NonCancellable) { late.await() } },
            sharp = { awaitCancellation() }, publishPreview = shown::add) }
        started.await(); result.cancel(); late.complete("obsolete")
        result.join(); assertTrue(result.isCancelled); assertTrue(shown.isEmpty())
    }
}
