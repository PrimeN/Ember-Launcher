package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.mediaserver.*
import app.ember.tv.player.PlaybackMetadataLoads
import app.ember.tv.state.CollectionsState
import app.ember.tv.ui.Accents
import app.ember.tv.ui.emberColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MetadataAccountAndActionMenuSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun lateMetadataFromPreviousPlaybackCannotOverwriteNewMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<String>()
        val loaded = mutableListOf<String>()
        val requests = PlaybackMetadataLoads(scope)
        try {
            requests.load({ withContext(NonCancellable) { pending.await() } }) { loaded += it }
            requests.reset()
            requests.load({ "new episode" }) { loaded += it }
            pending.complete("old episode")
            yield()
            assertEquals(listOf("new episode"), loaded)
        } finally { pending.complete("ignored"); scope.cancel() }
    }

    @Test fun metadataFailuresAreIsolatedAndDoNotCancelThePlayerScope() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = PlaybackMetadataLoads(scope)
        val loaded = mutableListOf<String>()
        try {
            requests.load<String>({ throw java.io.IOException("fixture failure") }) { loaded += it }
            requests.load({ "chapters" }) { loaded += it }
            requests.load({ "seek previews" }) { loaded += it }
            assertTrue(scope.isActive)
            assertEquals(listOf("chapters", "seek previews"), loaded)
        } finally { scope.cancel() }
    }

    @Test fun cancelledPlayerScopeDoesNotPublishLateAuxiliaryResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Int>()
        var published = false
        PlaybackMetadataLoads(scope).load({ withContext(NonCancellable) { gate.await() } }) { published = true }
        scope.cancel(); gate.complete(1); yield()
        assertFalse(published)
    }

    @Test fun runtimeTtlSupportsEarlyBootAndRejectsExpiredOrFutureEntries() {
        assertTrue(runtimeFresh(0, 0, 100))
        assertTrue(runtimeFresh(100, 199, 100))
        assertFalse(runtimeFresh(100, 200, 100))
        assertFalse(runtimeFresh(100, 99, 100))
        assertFalse(runtimeFresh(-1, 1, 100))
        assertFalse(runtimeFresh(0, 0, 0))
    }

    @Test fun collectionCacheExpiresAndDoesNotReuseFutureDatedPages() = runBlocking {
        val context = PersistenceContext(temp.newFolder())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 100L; var calls = 0
        val state = CollectionsState(context, Prefs(context), scope, AddonClient(), { emptyList() }, {})
        state.nowMillis = { now }
        state.loadPage = { _, _, _ ->
            calls++
            Result.success(CollectionPage(listOf(Meta(id = "tt$calls", type = "movie", name = "Film")), 1, true, "page$calls"))
        }
        val source = CollectionSource(provider = "tmdb", tmdbSourceType = "COLLECTION", tmdbId = 9)
        suspend fun open() {
            state.openFolder(MediaCollection(id = "collection"), CollectionFolder(id = "folder", sources = listOf(source)))
            withTimeout(5_000) { while (state.tabs.singleOrNull()?.loading != false) delay(5) }
        }
        try {
            open(); assertEquals(1, calls)
            state.closeFolder(); now = 101; open(); assertEquals(1, calls)
            state.closeFolder(); now = 300_100; open(); assertEquals(2, calls)
            state.closeFolder(); now = 50; open(); assertEquals(3, calls)
        } finally { scope.cancel() }
    }

    @Test fun expiredKidsMetadataIsRefetchedInsteadOfBypassingTheRefresh() = runBlocking {
        var now = 100L; var calls = 0
        val lookup = KidsMetadata({ "fixture-key" }, {
            calls++
            Json.parseToJsonElement("""{"adult":false,"genres":[{"name":"Animation"}],"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG"}]}]}}""")
        }).apply { nowMillis = { now } }
        val film = Meta(id = "tt9", type = "movie", name = "Film", tmdbId = 9)
        val profile = Profile("child", "Child", kids = true, maxTvRating = "TV-PG")
        assertEquals("PG", lookup.lookup(film).ageRating)
        assertEquals(1, calls)
        now = 200
        assertEquals(1, lookup.filter(profile, listOf(film)).size)
        assertEquals(1, calls)
        now = 1_800_100
        assertEquals(1, lookup.filter(profile, listOf(film)).size)
        assertEquals(2, calls)
    }

    private class Secrets : ServerSecrets {
        val values = ConcurrentHashMap<String, String>()
        val deviceWrites = AtomicInteger()
        override fun read(key: String): String? {
            val value = values[key]
            if (key == "ms.deviceId" && value == null) Thread.sleep(10)
            return value
        }
        override fun write(key: String, value: String): Boolean {
            if (key == "ms.deviceId") deviceWrites.incrementAndGet()
            values[key] = value; return true
        }
        override fun remove(key: String) { values.remove(key) }
    }

    @Test fun concurrentAccountInstancesCreateOnlyOneStableTvIdentity() {
        val secrets = Secrets(); val executor = Executors.newFixedThreadPool(8); val start = CountDownLatch(1)
        try {
            val jobs = (1..16).map { executor.submit<String> { start.await(); Accounts(secrets).deviceId() } }
            start.countDown()
            assertEquals(1, jobs.map { it.get(3, TimeUnit.SECONDS) }.toSet().size)
            assertEquals(1, secrets.deviceWrites.get())
        } finally { executor.shutdownNow() }
    }

    @Test fun playbackSnapshotKeepsOriginalUserWhenSettingsChangeAccounts() {
        val secrets = Secrets(); val live = Accounts(secrets)
        live.plexToken = "fixture-owner-a"
        live.plexHome = PlexHomeSession("child-a", "Child A", "fixture-child-a")
        live.saveServers(listOf(ServerAccount("server", "jellyfin", "Home", "https://fixture.invalid", "user-a", "fixture-a")))
        val snapshot = live.playbackSnapshot()
        live.plexToken = "fixture-owner-b"
        live.saveServers(listOf(live.servers().single().copy(userId = "user-b", token = "fixture-b")))
        assertEquals("fixture-child-a", snapshot.plexAccessToken)
        assertEquals("user-a", snapshot.servers().single().userId)
        assertEquals("fixture-a", snapshot.servers().single().token)
        assertNotEquals(snapshot.plexAccessToken, live.plexAccessToken)
    }

    @Test fun playbackSnapshotIsReadOnlyAndDoesNotWriteMissingDeviceIds() {
        val secrets = Secrets(); val live = Accounts(secrets); live.plexToken = "fixture-owner"
        val before = secrets.values.toMap()
        val snapshot = live.playbackSnapshot()
        assertEquals(snapshot.deviceId(), snapshot.deviceId())
        assertEquals(before, secrets.values.toMap())
        assertThrows(IllegalStateException::class.java) { snapshot.plexToken = "fixture-other" }
        assertThrows(IllegalStateException::class.java) { snapshot.saveServers(emptyList()) }
        assertEquals("fixture-owner", live.plexToken)
    }

    @Test fun playbackReportUsesOriginalServerUserAfterConnectionChanges() = runBlocking {
        val secrets = Secrets(); val live = Accounts(secrets)
        val a = ServerAccount("server", "jellyfin", "Home", "https://fixture.invalid", "user-a", "fixture-a")
        live.saveServers(listOf(a))
        val session = live.playbackSnapshot()
        var sent: Request? = null
        val http = ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
            sent = chain.request()
            Response.Builder().request(chain.request()).code(200).message("Fixture").protocol(Protocol.HTTP_1_1)
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = 0L
                    override fun source(): BufferedSource = Buffer()
                }).build()
        }.build())
        val media = MediaServers(session, jellyfin = JellyfinClient(session, http))
        live.saveServers(listOf(a.copy(userId = "user-b", token = "fixture-b")))
        media.report("jellyfin|server|item", ReportState.PROGRESS, 1000, 6000)
        // Jellyfin takes one token, in the Authorization header (#93).
        assertTrue(sent!!.header("Authorization")!!.contains("Token=\"fixture-a\""))
        assertNull(sent!!.header("X-Emby-Token"))
    }

    private suspend fun response(text: String, length: Long, limit: Long): Pair<String?, Boolean> {
        var closed = false
        val source = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        val http = ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("Fixture").protocol(Protocol.HTTP_1_1)
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = length
                    override fun source(): BufferedSource = source
                }).build()
        }.build(), maxResponseBytes = limit)
        return try { http.rawGet("https://fixture.invalid/meta") to closed }
        catch (_: ResponseTooLargeException) { null to closed }
    }

    @Test fun serverMetadataRejectsOversizedDeclaredAndChunkedRepliesAndClosesThem() = runBlocking {
        assertEquals(null to true, response("12345", 5, 4))
        assertEquals(null to true, response("12345", -1, 4))
    }

    @Test fun serverMetadataAcceptsExactUtf8ByteLimitAndClosesTheReply() = runBlocking {
        assertEquals("éé" to true, response("éé", 4, 4))
    }

    @Test fun boundedServerMetadataPreservesDeclaredCharsetAndBomDecoding() = runBlocking {
        suspend fun decode(bytes: ByteArray, type: String) = ServerHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("Fixture").protocol(Protocol.HTTP_1_1)
                .body(bytes.toResponseBody(type.toMediaType())).build()
        }.build(), maxResponseBytes = 4).get("https://fixture.invalid/meta")
        assertEquals(Json.parseToJsonElement("\"é\""), decode(byteArrayOf(0x22, 0xE9.toByte(), 0x22), "application/json; charset=iso-8859-1"))
        assertEquals(Json.parseToJsonElement("1"), decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x31), "application/json; charset=utf-16"))
    }

    @Test fun destructiveMenuFocusHasReadableContrastAcrossEveryThemeAndMode() {
        fun contrast(a: Color, b: Color): Double {
            val x = a.luminance().toDouble() + .05; val y = b.luminance().toDouble() + .05
            return maxOf(x, y) / minOf(x, y)
        }
        for (accent in Accents.indices) for (day in listOf(false, true)) for (oled in listOf(false, true)) {
            val colors = emberColorScheme(accent, day, oled)
            assertTrue(contrast(colors.onErrorContainer, colors.errorContainer) >= 4.5)
        }
    }
}
