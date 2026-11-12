package app.ember.tv

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.ember.tv.data.*
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.state.*
import app.ember.tv.ui.navigationGlassShape
import app.ember.tv.weather.Place
import app.ember.tv.weather.WeatherNow
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheAndUpdateLifecycleTest {
    @get:Rule val temp = TemporaryFolder()
    private fun prefs() = Prefs(PersistenceContext(temp.newFolder()))
    private fun release(name: String) = Updater.Release("v$name", name, "", "https://example.test/asset", "fixture.apk", 1, Int.MAX_VALUE)

    @Test fun roundTransportEdgesMatchSmallAndLargeButtonsInBothInterfaces() {
        for (emberX in listOf(false, true)) for (diameter in listOf(40f, 48f, 56f)) {
            val shape = navigationGlassShape(emberX, CircleShape)
            val outline = shape.createOutline(Size(diameter, diameter), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
            assertEquals(diameter / 2, outline.roundRect.topLeftCornerRadius.x)
            assertEquals(diameter / 2, outline.roundRect.bottomRightCornerRadius.y)
        }
        val capsule = navigationGlassShape(true).createOutline(Size(100f, 40f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(8f, capsule.roundRect.topLeftCornerRadius.x)
    }
    @Test fun explicitCacheInvalidationCannotBeUndoneByAnOlderRefreshFallback() = runBlocking {
        val cache = AsyncCache<String, String>()
        cache.load("addon") { "old manifest" }
        val refreshing = cache.invalidate("addon")
        cache.invalidate("addon") // An explicit source refresh/forget wins.
        assertNull(cache.restore("addon", "old manifest", refreshing))
        assertNull(cache.peek("addon"))
        assertEquals("new manifest", cache.load("addon") { "new manifest" })
    }
    @Test fun aRefreshFallbackReturnsANewerValueInsteadOfPublishingTheOldOne() = runBlocking {
        val cache = AsyncCache<String, String>()
        val revision = cache.invalidate("addon")
        cache.load("addon") { "new" }
        assertEquals("new", cache.restore("addon", "old", revision))
        assertEquals("new", cache.peek("addon"))
    }
    @Test fun invalidatedInFlightLoadsDoNotPopulateTheCache() = runBlocking {
        val cache = AsyncCache<String, String>()
        val pending = CompletableDeferred<String>()
        val load = async(start = CoroutineStart.UNDISPATCHED) { cache.load("addon") { pending.await() } }
        cache.invalidate("addon")
        pending.complete("obsolete")
        assertEquals("obsolete", load.await())
        assertNull(cache.peek("addon"))
        assertEquals("current", cache.load("addon") { "current" })
    }

    private inner class UpdateFixture {
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures += error })
        val state = UpdatesState(Application(), prefs(), scope)
        var installed = 0
        init {
            state.checkRelease = { Result.success(release(it)) }
            state.installerAllowed = { true }
            state.downloadRelease = { _, _ -> Result.success(File("fixture.apk")) }
            state.installRelease = { _, _ -> installed++; true }
            state.checkForUpdate(true)
        }
        fun close() { scope.cancel(); assertTrue(failures.toString(), failures.isEmpty()) }
    }
    @Test fun aMissingAndroidInstallerShowsRecoveryInsteadOfCrashing() {
        val f = UpdateFixture()
        try {
            f.state.installRelease = { _, _ -> throw ActivityNotFoundException("fixture") }
            f.state.installUpdate()
            assertTrue(f.state.updateMessage.orEmpty().contains("No Android package installer"))
            assertNull(f.state.updateProgress)
            assertFalse(f.state.downloading)
        } finally { f.close() }
    }
    @Test fun anInstallerPermissionDenialShowsRecoveryAndAllowsRetry() {
        val f = UpdateFixture()
        try {
            f.state.installRelease = { _, _ -> throw SecurityException("fixture") }
            f.state.installUpdate()
            assertTrue(f.state.updateMessage.orEmpty().contains("blocked"))
            assertNull(f.state.updateProgress)
            // #212: an install session usually finishes by itself and restarts Ember, so it says so and shows a bar.
            lateinit var report: (Float) -> Unit
            f.state.installRelease = { _, progress -> report = progress; f.installed++; true }
            f.state.installUpdate()
            assertEquals(1, f.installed)
            assertEquals(UpdatesState.INSTALLING, f.state.updateMessage)
            assertNull(f.state.updateProgress); assertEquals(0f, f.state.installProgress)
            report(.3f); assertEquals(.3f, f.state.installProgress); report(.2f); assertEquals("never backwards", .3f, f.state.installProgress)
            f.state.installUpdate(); assertEquals("no second download while installing", 1, f.installed)
            app.ember.tv.data.Updater.report("The update wasn't installed."); assertNull(f.state.installProgress)
            // When only Android's installer screen could open, it does ask.
            f.state.installRelease = { _, _ -> f.installed++; false }
            f.state.installUpdate()
            assertEquals(UpdatesState.ASKS, f.state.updateMessage); assertNull(f.state.installProgress)
        } finally { f.close() }
    }
    @Test fun deniedPermissionQueriesAndMissingPermissionPagesDoNotStartADownload() {
        val f = UpdateFixture()
        var downloads = 0
        try {
            f.state.downloadRelease = { _, _ -> downloads++; Result.success(File("fixture.apk")) }
            f.state.installerAllowed = { throw SecurityException("fixture") }
            f.state.installUpdate()
            assertTrue(f.state.updateMessage.orEmpty().contains("restricted"))
            f.state.installerAllowed = { false }
            f.state.requestInstallPermission = { false }
            f.state.installUpdate()
            assertTrue(f.state.updateMessage.orEmpty().contains("no permission shortcut"))
            assertEquals(0, downloads)
        } finally { f.close() }
    }
    @Test fun canceledDownloadProgressAndCleanupCannotChangeItsReplacement() = runBlocking {
        val f = UpdateFixture()
        val old = CompletableDeferred<Result<File>>()
        val current = CompletableDeferred<Result<File>>()
        lateinit var oldProgress: (Float) -> Unit
        lateinit var newProgress: (Float) -> Unit
        var calls = 0
        try {
            f.state.downloadRelease = { _, progress ->
                if (calls++ == 0) { oldProgress = progress; withContext(NonCancellable) { old.await() } }
                else { newProgress = progress; current.await() }
            }
            f.state.installUpdate(); oldProgress(.2f)
            assertEquals(.2f, f.state.updateProgress)
            f.state.cancelUpdate(); f.state.installUpdate(); newProgress(.4f)
            oldProgress(.9f)
            assertEquals(.4f, f.state.updateProgress)
            old.complete(Result.success(File("old.apk"))); yield()
            assertEquals(.4f, f.state.updateProgress)
            assertTrue(f.state.downloading)
            current.complete(Result.success(File("current.apk"))); yield()
            assertEquals(1, f.installed)
            assertNull(f.state.updateProgress)
        } finally { old.complete(Result.failure(Exception("closed"))); f.close() }
    }
    @Test fun switchingUpdateChannelsCannotInstallACompletedOldDownload() = runBlocking {
        val f = UpdateFixture()
        val pending = CompletableDeferred<Result<File>>()
        lateinit var progress: (Float) -> Unit
        try {
            f.state.downloadRelease = { _, p -> progress = p; withContext(NonCancellable) { pending.await() } }
            f.state.installUpdate(); f.state.toggleUpdateBeta()
            assertEquals("beta", f.state.activeChannel)
            assertEquals("beta", f.state.update?.name)
            val message = f.state.updateMessage
            progress(.9f); pending.complete(Result.success(File("old-channel.apk"))); yield()
            assertEquals(0, f.installed)
            assertNull(f.state.updateProgress)
            assertEquals(message, f.state.updateMessage)
        } finally { pending.complete(Result.failure(Exception("closed"))); f.close() }
    }
    @Test fun backwardsWallClockChangesDoNotSuppressAutomaticUpdateChecks() {
        val p = prefs(); p.updateCheckedAt = 2_000_000; p.updateAutoCheck = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = UpdatesState(Application(), p, scope)
        var time = 1_000_000L; var checked = 0
        state.nowMillis = { time }; state.checkRelease = { checked++; Result.success(null) }
        try {
            state.checkForUpdate(false)
            assertEquals(1, checked)
            state.checkForUpdate(false)
            assertEquals(1, checked)
            time += 20 * 3600_000L
            state.checkForUpdate(false)
            assertEquals(2, checked)
        } finally { scope.cancel() }
    }
    @Test fun weatherCacheUsesItsElapsedClockAndExpiresAfterThirtyMinutes() {
        val ctx = PersistenceContext(temp.newFolder()); val p = Prefs(ctx)
        p.weatherPlace = Place("City", 1.0, 2.0).encode()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = WeatherState(Application(), p, scope, ScreensaverFeed(ctx))
        var elapsed = 1_000L; var loads = 0
        state.nowMillis = { elapsed }; state.forecast = { _, _ -> loads++; WeatherNow(20, 0, null, null, "C") }
        try {
            state.refreshWeather(); assertEquals(1, loads)
            elapsed += 5 * 60_000L; state.refreshWeather(); assertEquals(1, loads)
            elapsed += 25 * 60_000L; state.refreshWeather(); assertEquals(2, loads)
            elapsed = 500; state.refreshWeather(); assertEquals(3, loads)
        } finally { scope.cancel() }
    }
    @Test fun failedWeatherLoadsImmediatelyRetryAfterBootInsteadOfUsingAnEmptyCache() {
        val ctx=PersistenceContext(temp.newFolder());val p=Prefs(ctx)
        p.weatherPlace=Place("City",1.0,2.0).encode()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val state=WeatherState(Application(),p,scope,ScreensaverFeed(ctx))
        var attempts=0
        state.nowMillis={1_000L}
        state.forecast={_,_->if(attempts++==0)throw java.io.IOException("offline") else WeatherNow(20,0,null,null,"C")}
        try {
            state.refreshWeather();assertNull(state.weatherText)
            state.refreshWeather();assertEquals(2,attempts);assertNotNull(state.weatherText)
            state.refreshWeather();assertEquals(2,attempts)
        } finally {scope.cancel()}
    }

}
