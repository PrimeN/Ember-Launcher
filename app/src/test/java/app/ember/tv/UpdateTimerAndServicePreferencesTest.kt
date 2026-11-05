package app.ember.tv

import android.app.Application
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import app.ember.tv.data.*
import app.ember.tv.state.*
import app.ember.tv.streaming.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateTimerAndServicePreferencesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun prefs() = Prefs(PersistenceContext(temp.newFolder()))
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun release(name: String) = Updater.Release("v$name", name, "", "https://example.test/asset", "app.apk", 1, Int.MAX_VALUE)

    @Test fun changingUpdateChannelRejectsLateSuccessFromTheOldChannel() = runBlocking {
        val scope = scope(); val state = UpdatesState(Application(), prefs(), scope)
        val old = CompletableDeferred<Result<Updater.Release?>>()
        state.checkRelease = { channel -> if (channel == "builds") Result.success(release("New")) else withContext(NonCancellable) { old.await() } }
        try {
            state.checkForUpdate(true); state.updateUpdateChannel("builds")
            old.complete(Result.success(release("Old"))); yield()
            assertEquals("New", state.update?.name)
            assertEquals("New is available.", state.updateMessage)
        } finally { scope.cancel() }
    }

    @Test fun repeatedChecksOfTheSameChannelRejectLateFailure() = runBlocking {
        val scope = scope(); val state = UpdatesState(Application(), prefs(), scope)
        val old = CompletableDeferred<Result<Updater.Release?>>()
        var calls = 0
        state.checkRelease = { if (calls++ == 0) withContext(NonCancellable) { old.await() } else Result.success(release("Current")) }
        try {
            state.checkForUpdate(true); state.checkForUpdate(true)
            old.complete(Result.failure(IllegalStateException("obsolete error"))); yield()
            assertEquals("Current", state.update?.name)
            assertEquals("Current is available.", state.updateMessage)
        } finally { scope.cancel() }
    }

    @Test fun channelChangeImmediatelyRemovesThePreviousChannelsInstallOffer() {
        val scope = scope(); val state = UpdatesState(Application(), prefs(), scope)
        val pending = CompletableDeferred<Result<Updater.Release?>>()
        state.checkRelease = { if (it == "builds") pending.await() else Result.success(release("Previous")) }
        try {
            state.checkForUpdate(true); assertNotNull(state.update)
            state.updateUpdateChannel("builds")
            assertNull(state.update); assertEquals("Checking...", state.updateMessage)
        } finally { scope.cancel() }
    }

    @Test fun cancelledUpdateOwnerCannotPublishOrRecordALateCheck() = runBlocking {
        val scope = scope(); val prefs = prefs(); val state = UpdatesState(Application(), prefs, scope)
        val pending = CompletableDeferred<Result<Updater.Release?>>()
        state.checkRelease = { withContext(NonCancellable) { pending.await() } }
        state.checkForUpdate(true); scope.cancel()
        pending.complete(Result.success(release("Late"))); yield()
        assertNull(state.update); assertEquals(0L, prefs.updateCheckedAt)
    }

    @Test fun updateCheckExceptionsRemainAnErrorMessageAndAllowRetry() {
        val scope = scope(); val state = UpdatesState(Application(), prefs(), scope)
        try {
            state.checkRelease = { throw java.io.IOException("fixture failure") }
            state.checkForUpdate(true); assertEquals("fixture failure", state.updateMessage)
            state.checkRelease = { Result.success(null) }
            state.checkForUpdate(true); assertEquals("No release with an APK found on this channel.", state.updateMessage)
        } finally { scope.cancel() }
    }

    private class TimerFixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = SleepState(Application(), scope)
        var elapsed = 100_000L
        var fired = 0
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        init {
            state.nowMillis = { elapsed }
            state.pause = { delay -> CompletableDeferred<Unit>().also { waits += delay to it }.await() }
            state.onSleep = { fired++ }
        }
    }

    @Test fun sleepCountdownUsesElapsedTimeAndFiresOnce() {
        val f = TimerFixture()
        try {
            f.state.setSleepTimer(15)
            assertTrue(f.state.sleepLeftText.orEmpty().startsWith("Sleeping in 15 min"))
            assertEquals(30_000L, f.waits.single().first)
            f.elapsed += 15 * 60_000; f.waits.single().second.complete(Unit)
            assertEquals(1, f.fired); assertEquals(0, f.state.sleepMinutes); assertNull(f.state.sleepLeftText)
        } finally { f.scope.cancel() }
    }

    @Test fun replacingASleepTimerCannotFireItsCancelledPredecessor() {
        val f = TimerFixture()
        try {
            f.state.setSleepTimer(1); f.state.setSleepTimer(2)
            f.elapsed += 60_000; f.waits[0].second.complete(Unit)
            assertEquals(0, f.fired); assertEquals(2, f.state.sleepMinutes)
            f.elapsed += 60_000; f.waits[1].second.complete(Unit)
            assertEquals(1, f.fired)
        } finally { f.scope.cancel() }
    }

    @Test fun turningSleepOffCancelsItsPendingAction() {
        val f = TimerFixture()
        try {
            f.state.setSleepTimer(1); f.state.setSleepTimer(0)
            f.elapsed += 60_000; f.waits.single().second.complete(Unit)
            assertEquals(0, f.fired); assertEquals(0, f.state.sleepMinutes); assertNull(f.state.sleepLeftText)
        } finally { f.scope.cancel() }
    }

    @Test fun negativeSleepDurationIsOff() {
        val f = TimerFixture()
        try {
            f.state.setSleepTimer(-1)
            assertEquals(0, f.state.sleepMinutes); assertTrue(f.waits.isEmpty()); assertEquals(0, f.fired)
        } finally { f.scope.cancel() }
    }

    @Test fun pinLockoutUsesElapsedTimeAndDoesNotOpenDuringItsLastSecond() {
        val state = PinState(prefs()) {}; var elapsed = 500_000L; var opened = 0
        state.nowMillis = { elapsed }; state.setPin("1234"); state.withPin("fixture") { opened++ }
        repeat(5) { assertFalse(state.enterPin("0000")) }
        assertEquals(60L, state.pinWait)
        elapsed += 59_001; assertEquals(1L, state.pinWait); assertFalse(state.enterPin("1234")); assertEquals(0, opened)
        elapsed += 999; assertEquals(0L, state.pinWait); assertTrue(state.enterPin("1234")); assertEquals(1, opened)
    }

    @Test fun repeatedPinFailureExtendsLockoutAndCorrectPinClearsTheFailureCount() {
        val state = PinState(prefs()) {}; var elapsed = 100_000L
        state.nowMillis = { elapsed }; state.setPin("1234")
        repeat(5) { state.enterPin("0000") }; elapsed += 60_000
        assertFalse(state.enterPin("0000")); assertEquals(120L, state.pinWait)
        elapsed += 120_000; assertTrue(state.enterPin("1234"))
        repeat(4) { assertFalse(state.enterPin("0000")) }; assertEquals(0L, state.pinWait)
    }

    private val netflix = StreamingService(8, "Netflix", "sub")
    private val offer = Offer(8, "Netflix", "sub", null, null, null, null)
    private fun installed() = listOf(AppEntry("com.netflix.ninja", "Netflix", Intent(), ColorDrawable()))

    @Test fun missingRegionalServiceStillHonoursItsExplicitOffPreferenceAfterReload() {
        val scope = scope(); val prefs = prefs(); prefs.watchProvider = "tmdb"
        try {
            val state = WhereToWatchState(prefs, scope, ::installed) {}
            assertTrue(state.offerEnabled(offer)); state.toggleService(netflix)
            assertTrue("tmdb:8" in prefs.servicesOff); assertFalse(state.offerEnabled(offer))
            assertFalse(WhereToWatchState(prefs, scope, ::installed) {}.offerEnabled(offer))
        } finally { scope.cancel() }
    }

    @Test fun missingRegionalServiceHonoursExplicitOnWithoutAnInstalledApp() {
        val scope = scope(); val prefs = prefs(); prefs.watchProvider = "tmdb"
        try {
            val state = WhereToWatchState(prefs, scope, { emptyList() }) {}
            assertFalse(state.offerEnabled(offer)); state.toggleService(netflix)
            assertTrue("tmdb:8" in prefs.servicesOn); assertTrue(state.offerEnabled(offer))
        } finally { scope.cancel() }
    }

    @Test fun missingWatchmodeEntryDoesNotUseTmdbOptInsOrInstalledAppFallback() {
        val scope = scope(); val prefs = prefs(); prefs.watchProvider = "watchmode"; prefs.servicesOn = setOf("tmdb:8")
        try { assertFalse(WhereToWatchState(prefs, scope, ::installed) {}.offerEnabled(offer)) }
        finally { scope.cancel() }
    }
}
