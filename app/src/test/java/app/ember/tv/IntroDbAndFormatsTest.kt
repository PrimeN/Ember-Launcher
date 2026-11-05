package app.ember.tv

import app.ember.tv.player.*
import app.ember.tv.mediaserver.Marker
import app.ember.tv.data.StreamInfoParser
import app.ember.tv.data.StreamParser
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.TrackedItem
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class IntroDbAndFormatsTest {
    private val response = """{"intro":{"start_sec":30,"end_sec":90},"recap":{"start_sec":0,"end_sec":20},"outro":{"start_sec":"19:00","end_sec":"20:00"}}"""
    private fun query() = IntroDbQuery("tt0944947", 1, 2)

    @Test fun queriesRequireExactIdentityAndNeverSendPlaybackOrAccountDetails() {
        val item = TrackedItem("private-url", "Release filename", kind = Kind.SHOW, imdbId = "tt0944947", season = 0, episode = 1)
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0944947&season=0&episode=1", requireNotNull(IntroDbQuery.from(item)).url())
        assertNull(IntroDbQuery.from(item.copy(episode = null)))
        assertNull(IntroDbQuery.from(item.copy(imdbId = "tt0944947&token=private")))
        assertNull(IntroDbQuery.from(item.copy(season = -1)))
        assertNull(IntroDbQuery.from(item.copy(episode = 0)))
        assertEquals("https://api.introdb.app/segments?imdb_id=tt0944947", requireNotNull(IntroDbQuery.from(item.copy(kind = Kind.MOVIE))).url())
    }

    @Test fun databaseNumbersAndClockStringsBecomeSortedMillisecondMarkers() {
        assertEquals(listOf(Marker("recap", 0, 20_000), Marker("intro", 30_000, 90_000), Marker("credits", 1_140_000, 1_200_000)), IntroDbSegments.parse(response))
        assertEquals(listOf(Marker("intro", 1500, 62_500)), IntroDbSegments.parse("""{"intro":{"start_sec":"1.5","end_sec":"01:02.5"}}"""))
    }

    @Test fun malformedHugeReversedAndOverlappingMarkersAreRejected() {
        for (value in listOf("null", "[]", "not-json", "{}", """{"intro":{"start_sec":-1,"end_sec":20}}""",
            """{"intro":{"start_sec":30,"end_sec":20}}""", """{"intro":{"start_sec":0,"end_sec":500000000000}}""",
            """{"intro":{"start_sec":"1:99","end_sec":"2:00"}}""")) assertTrue(value, IntroDbSegments.parse(value).isEmpty())
        val overlapping = """{"intro":{"start_sec":10,"end_sec":40},"recap":{"start_sec":0,"end_sec":30}}"""
        assertEquals(listOf(Marker("recap", 0, 30_000)), IntroDbSegments.parse(overlapping))
    }

    @Test fun unknownOrDifferentCutRuntimeCannotCauseAnOutOfBoundsOrWholeEpisodeSkip() {
        val markers = IntroDbSegments.parse(response)
        assertTrue(IntroDbSegments.forRuntime(markers, -1).isEmpty())
        assertEquals(markers, IntroDbSegments.forRuntime(markers, 1_200_000))
        assertFalse(IntroDbSegments.forRuntime(markers, 600_000).any { it.kind == "credits" })
        assertTrue(IntroDbSegments.forRuntime(listOf(Marker("intro", 0, 500_000)), 600_000).isEmpty())
        assertTrue(IntroDbSegments.forRuntime(listOf(Marker("credits", 10_000, 40_000)), 600_000).isEmpty())
    }

    @Test fun serverMarkersWinAndServerFailuresCanFallBack() = runBlocking {
        var reads = 0
        val result = resolveMarkers({ listOf(Marker("intro", 1, 2)) }, { reads++; IntroDbSegments.parse(response) })
        assertFalse(result.introDb); assertEquals(0, reads)
        val fallback = resolveMarkers({ throw java.io.IOException("offline") }, { reads++; IntroDbSegments.parse(response) })
        assertTrue(fallback.introDb); assertEquals(1, reads); assertEquals(3, fallback.markers.size)
    }

    @Test fun cacheIsEpisodeScopedBoundedAndRejectsFutureDatedEntries() = runBlocking {
        var reads = 0; var now = 1000L
        val client = IntroDbClient({ reads++; response }, { now })
        client.markers(query()); client.markers(query()); assertEquals(1, reads)
        client.markers(query().copy(episode = 3)); assertEquals(2, reads)
        now = 1; client.markers(query()); assertEquals(3, reads)
        for (i in 1..65) client.markers(query().copy(episode = i + 10))
        val before = reads; client.markers(query()); assertEquals(before + 1, reads)
    }

    @Test fun failedLookupsAreQuietAndCancellationDoesNotPoisonTheCache() = runBlocking {
        var reads = 0; var now = 0L
        val unavailable = IntroDbClient({ reads++; throw java.io.IOException() }, { now })
        assertTrue(unavailable.markers(query()).isEmpty()); unavailable.markers(query()); assertEquals(1, reads)
        now = 60_001; unavailable.markers(query()); assertEquals(2, reads)
        var cancel = true
        val client = IntroDbClient({ if (cancel) throw CancellationException("left player") else response }, { 0 })
        assertTrue(runCatching { client.markers(query()) }.exceptionOrNull() is CancellationException)
        cancel = false; assertEquals(3, client.markers(query()).size)
        assertTrue(runCatching { resolveMarkers({ throw CancellationException() }, { error("must not query") }) }.exceptionOrNull() is CancellationException)
    }

    @Test fun introDbPreferenceSurvivesConfigSerializationWithoutChangingTheSkipMode() {
        val config = PlaybackConfig(skipIntro = "auto", introDb = true)
        val restored = Json.decodeFromString<PlaybackConfig>(Json.encodeToString(config))
        assertTrue(restored.introDb); assertEquals("auto", restored.skipIntro)
        // Opt-in: nothing is sent to IntroDB until the user turns the fallback on.
        assertFalse(Json.decodeFromString<PlaybackConfig>("{}").introDb)
    }

    @Test fun vvcAndNewerAudioFormatsHaveNamesAndRoutesWithoutClaimingUnbundledDecoders() {
        assertTrue(PlaybackEnginePolicy.nativeFirst("https://example.com/clip.h266?token=ignored"))
        assertTrue(PlaybackFormats.nativeFirst("video/vvc")); assertFalse(PlaybackFormats.nativeFirst("video/hevc"))
        assertFalse(PlaybackFormats.nativeFirst("video/vvc", standardSupported = true))
        assertEquals("VVC/H.266", PlaybackFormats.video("video/vvc", null))
        assertEquals("VVC", StreamInfoParser.encode("Film.H.266")); assertEquals("VVC", StreamParser.codec("Film.VVC"))
        assertEquals("Dolby AC-4", PlaybackFormats.audio("audio/ac4"))
        assertEquals("MPEG-H 3D Audio", PlaybackFormats.audio("audio/mhm1")); assertEquals("IAMF", PlaybackFormats.audio("audio/iamf"))
    }

    @Test fun hdrReportingDoesNotInferDynamicMetadataOrOutputSupportFromTheFilename() {
        assertEquals("HDR10/PQ", PlaybackFormats.hdr("video/hevc", null, 6))
        assertEquals("HDR10+ metadata observed", PlaybackFormats.hdr("video/hevc", null, 6, true))
        assertEquals("HDR not reported", PlaybackFormats.hdr("video/hevc", null, null, unknown = true))
        assertEquals("Dolby Vision", PlaybackFormats.hdr("video/av01", "dav1.10.08", 6))
        assertEquals("AV1", PlaybackFormats.video("video/dolby-vision", "dav1.10.08"))
        assertEquals("H.264", PlaybackFormats.video("video/dolby-vision", "dvav.09.06"))
    }
}
