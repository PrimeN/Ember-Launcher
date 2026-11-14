package app.ember.tv

import android.app.Application
import app.ember.tv.data.*
import app.ember.tv.state.UpdatesState
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.IOException

class UpdateRetryTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun offlineFailureUsesShortBackoffAndSuccessRestoresTheDailyInterval() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));prefs.updateAutoCheck=true
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var now=100000000L;var calls=0
            val state=UpdatesState(Application(),prefs,scope)
            state.nowMillis={now};state.checkRelease={calls++;Result.failure(IOException("Offline"))}
            state.checkForUpdate(false);assertEquals(1,calls);assertEquals(0L,prefs.updateCheckedAt)
            now+=60_000L;state.checkForUpdate(false);assertEquals(1,calls)
            now+=15*60_000L;state.checkRelease={calls++;Result.success(null)}
            state.checkForUpdate(false);assertEquals(2,calls);assertEquals(now,prefs.updateCheckedAt)
            now+=3600_000L;state.checkForUpdate(false);assertEquals(2,calls)
            state.checkForUpdate(true);assertEquals(3,calls)
        } finally {scope.cancel()}
    }
    @Test fun aFailedManualCheckRetriesSoonEvenAfterAnEarlierSuccessfulCheck() {
        val prefs=Prefs(PersistenceContext(temp.newFolder()));prefs.updateAutoCheck=true
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var now=100000000L;var calls=0
            val state=UpdatesState(Application(),prefs,scope)
            state.nowMillis={now};state.checkRelease={calls++;Result.success(null)}
            state.checkForUpdate(false);assertEquals(1,calls)
            now+=60_000L;state.checkRelease={calls++;Result.failure(IOException("Offline"))}
            state.checkForUpdate(true);assertEquals(2,calls)
            now+=16*60_000L;state.checkRelease={calls++;Result.success(null)}
            state.checkForUpdate(false);assertEquals(3,calls)
        } finally {scope.cancel()}
    }

}
