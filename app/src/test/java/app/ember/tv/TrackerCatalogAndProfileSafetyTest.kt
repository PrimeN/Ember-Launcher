package app.ember.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.ember.tv.data.*
import app.ember.tv.state.CatalogsState
import app.ember.tv.state.DiscoverState
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class TrackerCatalogAndProfileSafetyTest {
    @Test fun sourceDefaultsDistinguishFreshInstallUpgradeAndExplicitHiddenTabs() {
        val tabs = NavTabs.ALL.map { it.id to it.shownByDefault }
        assertEquals(tabs, NavTabs.sourceDefaults(tabs, saved = false, live = false, servers = false))
        val migrated = NavTabs.sourceDefaults(tabs, saved = false, live = true, servers = true)
        assertTrue(migrated.first { it.first == NavTabs.LIVE }.second)
        assertTrue(migrated.first { it.first == NavTabs.LIBRARY }.second)
        assertEquals(tabs.map { it.first }, migrated.map { it.first })
        assertEquals(tabs, NavTabs.sourceDefaults(tabs, saved = true, live = true, servers = true))
    }

    private val addon = "https://example.com/manifest.json"
    private val adultKey = "$addon|movie|adult"
    private val kidsKey = "$addon|movie|kids"
    private fun row(key: String) = CatalogRow("Addon", key, listOf(Meta(id = key, name = key)), catalogKey = key)
    private val session = LinkSession("code", "https://example.com", "device", 5, 120)

    @Test fun rateLimitIncreasesAndRetainsIntervalIncludingRetryAfter() = runBlocking {
        var now = 0L
        val calls = mutableListOf<Long>()
        val linked = awaitDeviceLink(session, {
            calls += now
            when (calls.size) {
                1 -> throw HttpException(429, "", retryAfterSec = 20)
                2 -> false
                else -> true
            }
        }, now = { now }, pause = { now += it })
        assertTrue(linked)
        assertEquals(listOf(5000L, 25000L, 45000L), calls)
    }

    @Test fun expiredCodeIsNeverPolledAndNonRateLimitErrorsPropagate() = runBlocking {
        var now = 0L
        var calls = 0
        assertFalse(awaitDeviceLink(session.copy(expiresInSec = 4), { calls++; true }, { now }, { now += it }))
        assertEquals(0, calls)
        try {
            awaitDeviceLink(session, { throw HttpException(403, "denied") }, { now }, { now += it })
            fail("Authorization denial must not become pending approval")
        } catch (e: HttpException) { assertEquals(403, e.code) }
    }

    @Test fun disconnectCancelsUsernameValidationAndCannotRelinkLate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = LinkTasks(scope)
        val gate = CompletableDeferred<Unit>()
        var saved: String? = null
        try {
            tasks.start("mal") {
                linkPublicUsername("old", { withContext(NonCancellable) { gate.await() } }, { saved = it })
            }
            tasks.cancel("mal")
            gate.complete(Unit)
            yield()
            assertNull(saved)
            tasks.start("mal") { linkPublicUsername("new", {}, { saved = it }) }
            assertEquals("new", saved)
        } finally { scope.cancel() }
    }

    @Test fun aReplacementLinkCannotBeOverwrittenByAnOlderValidation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tasks = LinkTasks(scope)
        val gate = CompletableDeferred<Unit>()
        var saved: String? = null
        try {
            tasks.start("anilist") {
                linkPublicUsername("old", { withContext(NonCancellable) { gate.await() } }, { saved = it })
            }
            tasks.start("anilist") { linkPublicUsername("new", {}, { saved = it }) }
            gate.complete(Unit)
            yield()
            assertEquals("new", saved)
        } finally { scope.cancel() }
    }

    @Test fun malListsIncludeEntriesBeyondTheFirstFifty() = runBlocking {
        val first = "https://api.myanimelist.net/v2/users/test/animelist?limit=50"
        val next = "$first&offset=50"
        val visited = mutableListOf<String>()
        val rows = malListPages(first) { url ->
            visited += url
            buildJsonObject {
                put("data", JsonArray((if (url == first) 1..50 else 51..72).map { JsonPrimitive(it) }))
                if (url == first) putJsonObject("paging") { put("next", next) }
            }
        }
        assertEquals(72, rows.size)
        assertEquals(listOf(first, next), visited)
    }

    @Test fun malPaginationRejectsCredentialRedirectsAndLoops() = runBlocking {
        val first = "https://api.myanimelist.net/v2/users/test/animelist"
        for (next in listOf("https://other.example/steal", first)) {
            var calls = 0
            try {
                malListPages(first) {
                    calls++
                    buildJsonObject {
                        put("data", JsonArray(listOf(JsonPrimitive(1))))
                        putJsonObject("paging") { put("next", next) }
                    }
                }
                fail("Invalid continuation must fail")
            } catch (e: IllegalStateException) { assertEquals(1, calls) }
        }
    }

    @Test fun trackerMergeKeepsMovieAndShowWithSameNameAndYearSeparate() {
        val movie = TrackedItem("m", "Same name", 2024, Kind.MOVIE)
        val show = movie.copy(key = "s", kind = Kind.SHOW)
        assertNotEquals(movie.dedupeKey, show.dedupeKey)
        // A provider missing IMDb still uses the existing same-kind name/year merge.
        assertEquals(movie.dedupeKey, movie.copy(imdbId = "tt1").dedupeKey)
        assertEquals(show.dedupeKey, show.copy(kind = Kind.ANIME).dedupeKey)
    }

    @Test fun cachedRowsEnforceProfileAndInstalledAddonBeforeNetworkReturns() {
        val rows = listOf(row(adultKey), row(kidsKey), row("https://removed/manifest.json|movie|kids"))
        val kids = Profile("kids", "Kids", kids = true, catalogs = setOf(kidsKey), live = false)
        assertEquals(listOf(row(kidsKey)), visibleCatalogRows(rows, kids, listOf(addon), emptyMap()))
        assertTrue(visibleCatalogRows(rows, kids, listOf(addon), emptyMap(), live = true).isEmpty())
        assertTrue(visibleCatalogRows(rows, kids, emptyList(), emptyMap()).isEmpty())
    }

    @Test fun cachedRowsRespectVisibilityWhileKidsKeepTheirExplicitAllowlist() {
        val rows = listOf(row(adultKey), row(kidsKey))
        assertEquals(listOf(row(kidsKey)), visibleCatalogRows(rows, Profile("main", "Me"), listOf(addon), mapOf(adultKey to false)))
        assertTrue(visibleCatalogRows(rows, Profile("main", "Me"), listOf(addon), emptyMap(), defaultsOff = true).isEmpty())
        assertEquals(listOf(row(kidsKey)), visibleCatalogRows(rows,
            Profile("kids", "Kids", kids = true, catalogs = setOf(kidsKey)), listOf(addon), mapOf(kidsKey to false), defaultsOff = true))
    }

    @Test fun catalogStateClearsLastRemovedAddonAndKeepsOfflineSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = memoryPrefs()
        var installed = listOf(addon)
        val state = CatalogsState(prefs, scope, AddonClient(), { installed }, { Profile("main", "Me") },
            { true }, { emptyList() }, { 1 }, { _, _ -> },
            { DiscoverState(scope, AddonClient()) { emptyList() } }, {})
        try {
            state.rows = listOf(row(adultKey))
            state.liveRows = listOf(row(kidsKey))
            state.refreshRows()
            assertEquals(listOf(row(adultKey)), state.rows)
            assertEquals(listOf(row(kidsKey)), state.liveRows)
            installed = emptyList()
            state.refreshRows()
            assertTrue(state.rows.isEmpty())
            assertTrue(state.liveRows.isEmpty())
            assertTrue(state.allCatalogs.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun profileChangeFiltersCatalogStateImmediatelyWhileManifestIsBlocked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        var profile = Profile("main", "Me")
        val state = CatalogsState(memoryPrefs(), scope, AddonClient(), { listOf(addon) }, { profile },
            { profile.catalogs?.contains(it.key) ?: true }, { gate.await(); emptyList() }, { 1 }, { _, _ -> },
            { DiscoverState(scope, AddonClient()) { emptyList() } }, {})
        try {
            state.rows = listOf(row(adultKey), row(kidsKey))
            state.liveRows = state.rows
            state.refreshRows()
            profile = Profile("kids", "Kids", kids = true, catalogs = setOf(kidsKey), live = false)
            state.refreshRows(refetch = false)
            assertEquals(listOf(row(kidsKey)), state.rows)
            assertTrue(state.liveRows.isEmpty())
            gate.complete(Unit)
            yield()
            assertEquals(listOf(row(kidsKey)), state.rows)
        } finally { scope.cancel() }
    }

    /** In-memory Android preferences for state integration tests; no new test dependency. */
    private fun memoryPrefs(): Prefs {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args[0] as String] = args[1]; proxy }
                method.name == "remove" -> { values.remove(args[0]); proxy }
                method.name == "clear" -> { values.clear(); proxy }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when {
                method.name == "edit" -> editor
                method.name == "contains" -> values.containsKey(args[0])
                method.name == "getAll" -> values.toMap()
                method.name.startsWith("get") -> values[args[0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
        return Prefs(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        })
    }
}
