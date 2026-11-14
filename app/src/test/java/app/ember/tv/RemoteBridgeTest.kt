package app.ember.tv

import android.app.Application
import android.os.Looper
import app.ember.tv.remote.RemoteBridge
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RemoteBridgeTest {
    @Test fun timedOutCommandsCannotRunWhenTheMainQueueResumes() {
        val ran = AtomicInteger()
        val result = AtomicReference<Boolean?>()
        val worker = thread { result.set(RemoteBridge.onMain(50) { ran.incrementAndGet(); true }) }
        worker.join(2000)
        assertFalse(worker.isAlive); assertNull(result.get()); assertEquals(0, ran.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The expired command must stay cancelled", 0, ran.get())
    }

    private fun awaitPosted(worker: Thread) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (worker.isAlive && worker.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(Thread.State.TIMED_WAITING, worker.state)
    }

    @Test fun interruptedRemoteWorkersCancelTheirPendingCommands() {
        RemoteBridge.onMain { Unit }
        val ran = AtomicInteger(); val interrupted = AtomicReference<Boolean>()
        val worker = thread {
            try { RemoteBridge.onMain(5000) { ran.incrementAndGet() } }
            catch (_: InterruptedException) { interrupted.set(Thread.currentThread().isInterrupted) }
        }
        awaitPosted(worker); worker.interrupt(); worker.join(2000)
        assertFalse(worker.isAlive); assertEquals(true, interrupted.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, ran.get())
    }

    @Test fun commandsThatRunInTimeStillDeliverTheirResults() {
        RemoteBridge.onMain { Unit }
        val result = AtomicReference<Int?>()
        val worker = thread { result.set(RemoteBridge.onMain(5000) { 42 }) }
        awaitPosted(worker)
        shadowOf(Looper.getMainLooper()).idle(); worker.join(2000)
        assertFalse(worker.isAlive); assertEquals(42, result.get())
    }
}
