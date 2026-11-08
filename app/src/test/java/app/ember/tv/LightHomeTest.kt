package app.ember.tv

import android.content.Context
import app.ember.tv.data.*
import app.ember.tv.light.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LightHomeTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun sixMeasuredTilesFitExactlyAndRetainFiveToThreeProportions() {
        listOf(320f, 640f, 960f, 1280f, 1920f).forEach { window ->
            val g = LightLayout.grid(window)
            assertEquals(window, 2 * (g.outer + g.inset) + 5 * g.gap + 6 * g.width, .01f)
            assertEquals(3f / 5f, g.height / g.width, .001f)
            assertTrue(g.width > 0); assertTrue(g.radius < g.height / 2)
        }
        assertEquals(6, LightLayout.COLUMNS)
        assertEquals(5, LightLayout.backgrounds.distinctBy { it.first }.size)
    }
    @Test fun gridKeepsVisualMetricsAndHandlesInvalidOrTinyViewportSizes() {
        val normal = LightLayout.grid(960f)
        assertEquals(24f, normal.outer, .001f)
        assertEquals(21f, normal.inset, .001f)
        assertEquals(25f, normal.gap, .001f)
        for (size in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -100f, .01f)) {
            val grid = LightLayout.grid(size)
            assertTrue(grid.width.isFinite() && grid.width > 0f)
            assertTrue(grid.height.isFinite() && grid.radius.isFinite())
        }
    }
    @Test fun keepsLightIdentityAndNormalizesInvalidIdleChoices() {
        assertEquals("light", InterfaceStyles.normalize("light"))
        assertTrue(InterfaceStyles.options.contains("light" to "Ember Light"))
        assertEquals(30, LightHomeOptions(idleSeconds = -1).normalized().idleSeconds)
        assertEquals(0, LightHomeOptions(idleSeconds = 0).normalized().idleSeconds)
        assertEquals(120, LightHomeOptions(idleSeconds = 120).normalized().idleSeconds)
    }
    @Test fun lightChoicesPersistAndPortableBackupRestoresThem() {
        val source = PersistenceContext(temp.newFolder())
        val prefs = Prefs(source)
        prefs.lightWallpaper = "ocean"
        prefs.lightHome = LightHomeOptions(watchNext = false, recommendations = false, idleSeconds = 60, motion = false)
        assertEquals(prefs.lightHome, Prefs(source).lightHome)
        assertTrue(PreferenceKeys.LIGHT_HOME.portable)
        val target = PersistenceContext(temp.newFolder())
        BackupPersistence(target, FixtureSecrets()).restore(BackupPersistence(source, FixtureSecrets()).snapshot())
        assertEquals(prefs.lightHome, Prefs(target).lightHome)
        assertEquals("ocean", Prefs(target).lightWallpaper)
        source.getSharedPreferences("ember", Context.MODE_PRIVATE).edit().putString("lightHome", "broken json").commit()
        assertEquals(LightHomeOptions(), Prefs(source).lightHome)
        assertEquals(LightHomeOptions(), Json.decodeFromString<LightHomeOptions>("{}"))
    }
    @Test fun idleRequiresForegroundNoOverlayAndAtLeastOneSlide() {
        assertTrue(LightLayout.idleAllowed(30, true, false, 1))
        assertFalse(LightLayout.idleAllowed(0, true, false, 1))
        assertFalse(LightLayout.idleAllowed(30, false, false, 1))
        assertFalse(LightLayout.idleAllowed(30, true, true, 1))
        assertFalse(LightLayout.idleAllowed(30, true, false, 0))
    }
    private fun external(id: String, continuing: Boolean) = LightMedia(id, "app", id, "Episode", "https://image.test/$id", "app://episode/$id", .4f, 100, continuing)
    private fun meta(id: String) = Meta(id = id, type = "movie", name = id, background = "https://image.test/$id")
    @Test fun continueWatchingPrecedesRecommendationsAndResultsAreBounded() {
        val result = LightCarousel.compose(listOf(meta("own")), listOf(external("continue", true), external("preview", false)),
            (1..50).map { meta("recommended$it") })
        assertEquals(listOf("continue", "own"), result.take(2).map { it.title })
        assertTrue(result.take(2).all { it.continuing })
        assertEquals("preview", result[2].title)
        assertEquals(24, result.size)
        assertEquals(24, result.map { it.id }.distinct().size)
        assertEquals(1, LightCarousel.compose(emptyList(), emptyList(), listOf(meta("Same"), meta("Same"))).size)
    }
    @Test fun identitiesKeepSameNamedMoviesShowsAndEpisodesDistinct() {
        val movie = meta("same").copy(name = "The Same")
        val series = movie.copy(type = "series")
        val episodes = (1..2).map { series.copy(season = 1, episode = it) }
        val result = LightCarousel.compose(episodes, emptyList(), listOf(movie, series, movie))
        assertEquals(4, result.size)
        assertEquals(4, result.map { it.id }.distinct().size)
    }
    @Test fun continueWatchingCannotCrowdOutRecommendations() {
        val result = LightCarousel.compose((1..30).map { meta("continue$it") }, emptyList(), listOf(meta("recommended")))
        assertEquals(8, result.count { it.continuing })
        assertTrue(result.any { it.title == "recommended" })
    }
    @Test fun renderTimeGatesImmediatelyHideExternalChildAndExcludedAppContent() {
        val items = listOf(external("continue", true), external("preview", false), external("blocked", true).copy(pkg = "blocked"))
        assertTrue(LightMediaProvider.visible(items, setOf("app"), true, true, true).isEmpty())
        assertEquals(listOf("preview"), LightMediaProvider.visible(items, setOf("app"), false, false, true).map { it.id })
        assertEquals(listOf("continue"), LightMediaProvider.visible(items, setOf("app"), false, true, false).map { it.id })
        assertTrue(LightMediaProvider.visible(items, emptySet(), false, true, true).isEmpty())
    }
    @Test fun completedStaleFutureAndWatchlistEntriesAreNotResumeCards() {
        val now = 100L * 86_400_000L
        assertTrue(LightMediaProvider.eligible(0, true, 50, 100, now, now))
        assertFalse(LightMediaProvider.eligible(0, true, 95, 100, now, now))
        assertFalse(LightMediaProvider.eligible(0, true, 50, 100, now - 61L * 86_400_000L, now))
        assertFalse(LightMediaProvider.eligible(1, true, 0, 0, now + 2L * 86_400_000L, now))
        assertFalse(LightMediaProvider.eligible(3, true, 0, 0, now, now))
        assertFalse(LightMediaProvider.eligible(0, false, 0, 0, now, now))
    }
    @Test fun artworkSchemesRejectExecutableAndPrivateFiles() {
        assertEquals("content://tv/poster/1", LightMediaProvider.validArtwork("content://tv/poster/1"))
        listOf("javascript:alert(1)", "file:///data/private", "data:image/png,test", "relative.jpg").forEach { assertNull(LightMediaProvider.validArtwork(it)) }
    }
    @Test fun forecastParsesFiveDaysAndKeepsMissingValuesEmpty() {
        val json = Json.parseToJsonElement("""{"current":{"temperature_2m":12.4,"weather_code":2},"daily":{"time":["2026-11-04","2026-11-05","2026-11-06","2026-11-07","2026-11-08","2026-11-09"],"weather_code":[0,2,3,61,71,0],"temperature_2m_max":[16.5,17,null,18,19,20],"temperature_2m_min":[4,5,6,7,8,9]}}""")
        val weather = app.ember.tv.weather.Weather.parse(json, false)
        assertEquals(12, weather.temp); assertEquals("C", weather.unit)
        assertEquals(5, weather.days.size); assertEquals("2026-11-08", weather.days.last().date)
        assertEquals(17, weather.days.first().high); assertNull(weather.days[2].high)
        assertEquals(61, weather.days[3].code)
        assertEquals("F", app.ember.tv.weather.Weather.parse(json, true).unit)
    }
}
