package app.ember.tv

import android.content.ActivityNotFoundException
import app.ember.tv.data.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PackageDiscoveryReliabilityTest {
    private data class Candidate(val pkg: String, val label: String, val broken: Boolean = false)
    @Test fun aBrokenTvQueryDoesNotLoseNormalLauncherApps() {
        val apps = discoverPackageEntries<Candidate, String>(listOf<() -> List<Candidate>>({ throw SecurityException("vendor query") },
            { listOf(Candidate("app", "Application")) }), { it.pkg }, { it.label })
        assertEquals(listOf("Application"), apps)
    }
    @Test fun brokenMetadataDoesNotSuppressAHealthyAlternateEntryForTheSamePackage() {
        val tv = listOf(Candidate("bad", "broken", true), Candidate("good", "TV entry"))
        val standard = listOf(Candidate("bad", "Normal entry"), Candidate("good", "Duplicate"))
        val apps = discoverPackageEntries(listOf({ tv }, { standard }), { it.pkg }, {
            if (it.broken) throw IOException("missing resource")
            it.label
        })
        assertEquals(listOf("TV entry", "Normal entry"), apps)
    }
    @Test fun unsuccessfulQueriesAreNotAnAuthoritativeEmptyInventory() {
        var published = listOf("Existing player")
        try {
            published = discoverPackageEntries<String, String>(listOf({ throw SecurityException("binder failure") }), { it }, { it })
            fail("The old inventory must not be replaced with an empty success")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("Existing player"), published)
        assertTrue(discoverPackageEntries<String, String>(listOf({ emptyList() }), { it }, { it }).isEmpty())
    }
    @Test fun failedAndEmptyMetadataFallsBackWithoutLoadingUnneededResources() {
        var fallbackReads = 0
        val label = firstUsableMetadata({ throw IOException("custom label unavailable") }, { null }, { "Default label" })
        assertEquals("Default label", label)
        assertEquals("Custom label", firstUsableMetadata({ "Custom label" }, { fallbackReads++; "Default" }))
        assertEquals(0, fallbackReads)
        assertNull(firstUsableMetadata<String>({ throw SecurityException("blocked") }, { null }))
    }
    @Test fun cancellationDuringQueriesAndMetadataIsNeverHidden() {
        try {
            discoverPackageEntries<String, String>(listOf({ throw CancellationException("stopped") }), { it }, { it })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
        try {
            firstUsableMetadata<String>({ throw CancellationException("stopped") }, { fail("Fallback after cancellation"); "wrong" })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun cancelingAnAppRefreshStopsBeforeMoreIconsAreLoaded() {
        var loaded = 0
        var checks = 0
        try {
            discoverPackageEntries(listOf({ listOf("first", "second", "third") }), { it }, { loaded++; it },
                checkActive = { if (++checks == 3) throw CancellationException("new package event") })
            fail("Refresh should stop")
        } catch (_: CancellationException) { }
        assertEquals(1, loaded)
    }
    @Test fun launchDenialsAreReportedOnceWithoutCrashingTheLauncher() {
        var reports = 0
        assertFalse(launchInstalledApp({ throw SecurityException("blocked by device") }, { reports++ }))
        assertFalse(launchInstalledApp({ throw ActivityNotFoundException() }, { reports++ }))
        assertEquals(2, reports)
        assertTrue(launchInstalledApp({}, { fail("Successful launch must not report a failure") }))
    }
    @Test fun unrelatedLaunchErrorsAndCancellationStillPropagate() {
        try {
            launchInstalledApp({ throw IllegalArgumentException("implementation error") }, { fail("Wrong failure handler") })
            fail("Programming error swallowed")
        } catch (_: IllegalArgumentException) { }
        try {
            launchInstalledApp({ throw CancellationException("canceled") }, { fail("Wrong failure handler") })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun inventoryFreshnessUsesElapsedTimeAndDoesNotSkipTheFirstBootLoad() {
        assertFalse(appDiscoveryFresh(null, 100))
        assertTrue(appDiscoveryFresh(100, 200))
        assertFalse(appDiscoveryFresh(100, 30_100))
        assertFalse(appDiscoveryFresh(200, 100))
    }
    @Test fun appUpdatesDoNotTemporarilyPublishAnUninstall() {
        assertFalse(appInventoryChanged(android.content.Intent.ACTION_PACKAGE_REMOVED, true))
        assertTrue(appInventoryChanged(android.content.Intent.ACTION_PACKAGE_ADDED, true))
        assertTrue(appInventoryChanged(android.content.Intent.ACTION_PACKAGE_REMOVED, false))
        assertTrue(appInventoryChanged(android.content.Intent.ACTION_PACKAGE_CHANGED, false))
        assertFalse(appInventoryChanged(null, false))
    }
    @Test fun changingLauncherAliasDoesNotReuseTheOldIconAtTheSamePackageVersion() {
        val old=app.ember.tv.ui.AppArtworkKey("example.app",10L,true,"320:night:en","example.app/OldIcon")
        val replacement=old.copy(activity="example.app/NewIcon")
        val cache=app.ember.tv.ui.WeightedArtworkCache<app.ember.tv.ui.AppArtworkKey,String>(100){it.length}
        cache.put(old,"old icon")
        assertNull(cache.get(replacement))
        cache.put(replacement,"new icon")
        assertEquals("new icon",cache.get(replacement))
    }
    @Test fun fatalErrorsAreNotTurnedIntoEmptyMetadata() {
        val failure = runCatching { firstUsableMetadata<String>({ throw AssertionError("fatal fixture") }) }.exceptionOrNull()
        assertTrue(failure is AssertionError)
        assertEquals("fatal fixture", failure?.message)
    }
}
