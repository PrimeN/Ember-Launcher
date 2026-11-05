package app.ember.tv

import app.ember.tv.iptv.ActiveController
import app.ember.tv.iptv.refreshActiveController
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ActiveControllerTest {
    private class Owner(@Volatile var alive:Boolean=true)
    @Test fun activeOwnerIsRetainedAndCancelledOwnerIsReleased() {
        val reference=ActiveController<Owner>{it.alive};val owner=Owner()
        assertNull(reference.get());reference.set(owner);assertSame(owner,reference.get())
        owner.alive=false;assertNull(reference.get())
        owner.alive=true;assertNull(reference.get())
    }
    @Test fun clearingAnOldOwnerCannotEraseAConcurrentReplacement() {
        val checking=CountDownLatch(1);val proceed=CountDownLatch(1)
        val old=Owner(false);val replacement=Owner()
        val reference=ActiveController<Owner>{owner->
            if(owner===old) {checking.countDown();check(proceed.await(5,TimeUnit.SECONDS))}
            owner.alive
        }
        val worker=Executors.newSingleThreadExecutor()
        try {
            reference.set(old)
            val reading=worker.submit<Owner?>{reference.get()}
            assertTrue(checking.await(5,TimeUnit.SECONDS))
            reference.set(replacement);proceed.countDown()
            assertSame(replacement,reading.get(5,TimeUnit.SECONDS));assertSame(replacement,reference.get())
        } finally {proceed.countDown();worker.shutdownNow()}
    }
    @Test fun clearingAndRegisteringAgainUsesOnlyTheLatestOwner() {
        val reference=ActiveController<Owner>{it.alive}
        val first=Owner();val second=Owner()
        reference.set(first);reference.set(null);assertNull(reference.get())
        reference.set(second);first.alive=false;assertSame(second,reference.get())
    }
    @Test fun absentOrClosedOwnersSkipRefreshAndAllowFallback()=runBlocking {
        var calls=0
        assertFalse(refreshActiveController<Owner>(null,{it.alive}){calls++})
        assertFalse(refreshActiveController(Owner(false),{it.alive}){calls++})
        assertEquals(0,calls)
    }
    @Test fun ownerClosingDuringRefreshAllowsTheWorkersFallback()=runBlocking {
        val owner=Owner();var calls=0
        assertFalse(refreshActiveController(owner,{it.alive}){calls++;it.alive=false})
        assertEquals(1,calls)
    }
    @Test fun successfulActiveRefreshNeedsNoSecondController()=runBlocking {
        var calls=0
        assertTrue(refreshActiveController(Owner(),{it.alive}){calls++})
        assertEquals(1,calls)
    }
    @Test fun callerCancellationPropagatesInsteadOfStartingFallback()=runBlocking {
        val failure=runCatching {refreshActiveController(Owner(),{it.alive}){throw CancellationException("worker stopped")}}.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
