package app.ember.tv

import android.database.Cursor
import app.ember.tv.light.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class TvProgramReaderTest {
    private val now = 1_700_000_000_000L
    private val request = TvReadRequest(setOf("app.a", "app.b", "ember"), watch = true, previews = true)
    private class Source : TvProgramSource {
        var channels = emptyList<TvChannelRecord>()
        var watch = emptyList<TvProgramRecord>()
        var previews = emptyList<TvProgramRecord>()
        var channelError: Exception? = null
        var watchError: Exception? = null
        var calls = 0
        override suspend fun channels(): List<TvChannelRecord> { calls++; channelError?.let { throw it }; return channels }
        override suspend fun watchNext(): List<TvProgramRecord> { calls++; watchError?.let { throw it }; return watch }
        override suspend fun previews(): List<TvProgramRecord> { calls++; return previews }
    }
    private fun record(id: Long, owner: String = "app.a") = TvProgramRecord(id, owner, "Title $id", "app://episode/$id",
        duration = 1_200_000, position = 300_000, engagement = now, thumbnail = "https://images.test/$id")
    private fun channel(id: Long, owner: String = "app.a", name: String = "Recommended", shown: Boolean = true) =
        TvChannelRecord(id, owner, name, preview = true, browsable = shown)

    @Test fun platformRowsKeepStableDismissalIdsEpisodeDetailsAndProgress() = runBlocking {
        val source = Source().apply { watch = listOf(record(44).copy(season = "2", episode = "7", episodeTitle = "Episode",
            engagement = now / 1000, thumbnail = "file:///private/poster", poster = "content://provider/poster")) }
        val media = TvProgramReader(source, "ember").read(request, now).single()
        assertEquals("app.a:watch:44", media.id)
        assertEquals("S2 · E7 · Episode", media.description)
        assertEquals(.25f, media.progress!!, .001f)
        assertEquals(now, media.engaged)
        assertEquals(now, LightMediaProvider.engagementToken(media))
        assertEquals("content://provider/poster", media.artwork)
    }

    @Test fun excludedSelfOwnedAndChannelSpoofedRowsNeverEnterTheFeed() = runBlocking {
        val source = Source().apply {
            channels = listOf(channel(1))
            watch = listOf(record(1, "not.allowed"), record(2, "ember"), record(3))
            previews = listOf(record(4, "app.b").copy(channelId = 1), record(5).copy(channelId = 1))
        }
        val media = TvProgramReader(source, "ember").read(request, now)
        assertEquals(listOf("app.a:watch:3", "app.a:preview:5"), media.map { it.id })
    }

    @Test fun channelChoiceUsesResumeAndFirstBrowsableRecommendationWithoutMixingOwners() = runBlocking {
        val source = Source().apply {
            channels = listOf(channel(1, name = "Hidden", shown = false), channel(2), channel(3, name = "Continue Watching"),
                channel(4, "app.b"), channel(4, "app.a"))
            previews = (1L..4L).map { record(it).copy(channelId = it) }
        }
        val media = TvProgramReader(source, "ember").read(request, now)
        assertEquals(listOf(3L, 2L), media.map { it.id.substringAfterLast(':').toLong() })
        assertTrue(media.single { it.id.endsWith(":3") }.continuing)
        assertFalse(media.single { it.id.endsWith(":2") }.continuing)
    }

    @Test fun appRowsReadEveryBrowsableChannelUnderItsName() = runBlocking {
        // #62: the focused app's own rows, not just its first recommendation channel.
        val source = Source().apply {
            channels = listOf(channel(1, name = "Recently Added"), channel(2, name = "Trending"),
                channel(3, name = "Hidden", shown = false), channel(4, name = "Continue Watching"))
            watch = listOf(record(10))
            previews = listOf(record(11).copy(channelId = 1), record(12).copy(channelId = 2), record(13).copy(channelId = 3), record(14).copy(channelId = 4))
        }
        val media = TvProgramReader(source, "ember").read(TvReadRequest(setOf("app.a"), watch = true, previews = true, allChannels = true), now)
        val groups = LightMediaProvider.shelfGroups(media)
        assertEquals(listOf("Continue watching", "Recently Added", "Trending"), groups.map { it.first })
        assertEquals(listOf("app.a:preview:11"), groups[1].second.map { it.id })
        assertTrue(groups.first().second.all { it.continuing })
        assertFalse("hidden channel stays out", media.any { it.id.endsWith(":13") })
    }

    @Test fun unavailableChannelTableStillAllowsOwnedProgramFallbackAndHealthyTables() = runBlocking {
        val source = Source().apply {
            channelError = SecurityException("denied")
            watchError = IllegalStateException("unavailable")
            previews = listOf(record(7), record(8, "blocked"))
        }
        assertEquals("app.a:preview:7", TvProgramReader(source, "ember").read(request, now).single().id)
    }

    @Test fun disabledSourcesSkipQueriesAndCancellationIsNotConvertedToEmptyContent() = runBlocking {
        val source = Source()
        assertTrue(TvProgramReader(source, "ember").read(request.copy(watch = false, previews = false), now).isEmpty())
        assertTrue(TvProgramReader(source, "ember").read(request.copy(packages = emptySet()), now).isEmpty())
        assertEquals(0, source.calls)
        source.channelError = CancellationException("left page")
        try { TvProgramReader(source, "ember").read(request, now); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun resumeAndRecommendationSwitchesApplyToPreviewChannelRowsToo() = runBlocking {
        val source = Source().apply {
            channels = listOf(channel(1, name = "Resume"), channel(2))
            previews = listOf(record(1).copy(channelId = 1), record(2).copy(channelId = 2))
        }
        val reader = TvProgramReader(source, "ember")
        assertEquals(listOf(true), reader.read(request.copy(previews = false), now).map { it.continuing })
        assertEquals(listOf(false), reader.read(request.copy(watch = false), now).map { it.continuing })
    }

    @Test fun manyResumeRowsCannotConsumeTheEntireCachedRecommendationBudget() = runBlocking {
        val packages = (1..30).map { "app.$it" }.toSet()
        val source = Source().apply {
            watch = packages.flatMapIndexed { index, owner -> (1..8).map { record((index * 8 + it).toLong(), owner) } }
            previews = listOf(record(999, "app.1"))
        }
        val media = TvProgramReader(source, "ember").read(request.copy(packages = packages), now)
        assertEquals(80, media.count { it.continuing })
        assertEquals(1, media.count { !it.continuing })
    }

    @Test fun cacheSharesConcurrentLoadsExpiresAndRejectsClockRollback() = runBlocking {
        var elapsed = 100L
        var calls = 0
        val cache = TvProgramCache { elapsed }
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val loader: suspend () -> List<LightMedia> = {
            calls++; started.complete(Unit); finish.await(); listOf(media("one"))
        }
        val a = async { cache.get(request, loader) }; started.await()
        val b = async { cache.get(request, loader) }; yield(); finish.complete(Unit)
        assertEquals(a.await(), b.await()); assertEquals(1, calls)
        elapsed += 120_000
        assertEquals("expired", cache.get(request) { calls++; listOf(media("expired")) }.single().id)
        elapsed = 0
        assertEquals("clock", cache.get(request) { calls++; listOf(media("clock")) }.single().id)
        assertEquals(3, calls)
    }

    @Test fun invalidationDuringLoadCannotPublishTheOldSnapshotAndCancelledLoadsDoNotCache() = runBlocking {
        val cache = TvProgramCache { 1L }; var calls = 0
        val loaded = cache.get(request) {
            calls++
            if (calls == 1) { cache.invalidate(); listOf(media("old")) } else listOf(media("new"))
        }
        assertEquals("new", loaded.single().id); assertEquals(2, calls)
        cache.invalidate()
        val started = CompletableDeferred<Unit>()
        val job = launch { cache.get(request) { started.complete(Unit); awaitCancellation() } }
        started.await(); job.cancelAndJoin()
        assertEquals("after cancellation", cache.get(request) { listOf(media("after cancellation")) }.single().id)
    }

    @Test fun cursorMappingToleratesMissingAndMalformedOptionalColumnsWithoutLosingIdentity() {
        val values = linkedMapOf<String, Any?>("_id" to 9L, "package_name" to "app.a", "title" to "A title",
            "intent_uri" to "app://episode", "duration_millis" to "bad number", "last_playback_position_millis" to -10L,
            "watch_next_type" to Long.MAX_VALUE)
        val names = values.keys.toList(); var lookups = 0
        val cursor = Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> { lookups++; names.indexOf(args!![0]) }
                "isNull" -> values[names[args!![0] as Int]] == null
                "getString" -> values[names[args!![0] as Int]]?.toString()
                "getLong" -> values[names[args!![0] as Int]].toString().toLong()
                else -> null
            }
        } as Cursor
        val fields = TvCursorFields(cursor)
        val row = fields.program(); val indexed = lookups
        assertEquals(9L, row.id); assertEquals("app.a", row.owner)
        assertEquals(0L, row.duration); assertEquals(0L, row.position)
        assertEquals("", row.poster); assertTrue(row.browsable); assertEquals(-1, row.watchType)
        fields.program(); assertEquals(indexed, lookups)
    }

    private fun media(id: String) = LightMedia(id, "app.a", id, "", null, "app://episode", null, now, true)
}
