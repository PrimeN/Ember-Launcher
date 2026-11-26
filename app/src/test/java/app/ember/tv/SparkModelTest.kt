package app.ember.tv

import app.ember.tv.data.DiscoverVisibility
import app.ember.tv.data.Meta
import app.ember.tv.spark.SparkCandidate
import app.ember.tv.spark.SparkFacts
import app.ember.tv.spark.SparkModel
import app.ember.tv.spark.SparkNames
import app.ember.tv.spark.SparkSeed
import app.ember.tv.state.DiscoverSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #435 Spark. */
class SparkModelTest {
    @Test fun weightsFavourFinishedRecentAndLongShows() {
        val film = SparkModel.seedWeight(true, 1f, 1, 0.0)
        assertEquals(1.0, film, 0.001)
        assertTrue(SparkModel.seedWeight(false, 0.6f, 20, 0.0) > film)
        assertTrue(SparkModel.seedWeight(true, 1f, 1, 365.0) < film)
        assertTrue(SparkModel.seedWeight(false, 0.05f, 1, 10.0) < 0)
        assertTrue(SparkModel.seedWeight(false, 0.05f, 1, 1.0) > 0)
    }

    private fun c(id: Int, genres: Set<Int>, lang: String = "en", rating: Double = 7.5, votes: Int = 3000, from: List<String> = emptyList()) =
        SparkCandidate(Meta(id = "tmdb:movie:$id", type = "movie", name = "T$id", tmdbId = id.toLong()), "movie:$id", genres, lang, 2015, rating, votes, from, if (from.isEmpty()) 0.0 else 1.0)

    @Test fun tasteRowsAndReasons() {
        val facts = mapOf("movie:1" to SparkFacts("movie:1", "Arrival", listOf(878, 18), 2016, "en"),
            "movie:2" to SparkFacts("movie:2", "Parasite", listOf(53, 18), 2019, "ko"),
            "tv:3" to SparkFacts("tv:3", "Kingdom", listOf(18, 9648), 2019, "ko"))
        val seeds = listOf(SparkSeed("movie:1", "Arrival", 1.0, 3), SparkSeed("movie:2", "Parasite", 1.0, 2), SparkSeed("tv:3", "Kingdom", 2.0, 1))
        val taste = SparkModel.taste(seeds, facts)
        assertEquals(18, taste.topGenres(1).first())
        assertEquals("ko", taste.otherLanguage)
        assertEquals(2010, taste.topDecade)
        val pool = (10..30).map { c(it, setOf(18, 878)) } + (40..50).map { c(it, setOf(18), lang = "ko") } +
            listOf(c(60, setOf(878), from = listOf("Arrival")), c(1, setOf(878)))
        val rows = SparkModel.rows(pool, taste, seeds, excluded = setOf("movie:1"))
        assertEquals("Top picks for you", rows.first().title)
        assertTrue(rows.none { r -> r.items.any { it.tmdbId == 1L } })
        assertTrue(rows.any { it.title == "Gripping dramas" })
        assertTrue(rows.any { it.title == "More in Korean" })
        val all = rows.flatMap { it.items }
        assertTrue(all.groupBy { it.tmdbId }.values.all { it.size <= 2 })
        assertEquals("Because you watched Arrival · ★ 7.5", SparkModel.reason(c(60, setOf(878), from = listOf("Arrival")), taste))
        assertTrue(SparkModel.summary(taste)!!.contains("Drama"))
        assertEquals("Korean", SparkNames.language("ko"))
    }

    @Test fun sparkShowsWithATmdbKeyUnlessTurnedOff() {
        assertEquals(listOf(DiscoverSection.EXPLORE, DiscoverSection.SPARK), DiscoverVisibility.visible(emptyMap(), false, false, false, spark = true))
        assertEquals(listOf(DiscoverSection.EXPLORE), DiscoverVisibility.visible(mapOf("SPARK" to false), false, false, false, spark = true))
        assertEquals(emptyList<DiscoverSection>(), DiscoverVisibility.visible(emptyMap(), false, false, true, spark = true))
    }
}

/** #436 history brought in from trackers. */
class SparkHistoryTest {
    @Test fun ratingsCountMostAndLowOnesCountAgainst() {
        val plain = SparkModel.importedWeight(1, null, true, show = false, ageDays = 0.0)
        assertEquals(1.0, plain, 0.001)
        assertTrue(SparkModel.importedWeight(1, 10, true, false, 0.0) > SparkModel.importedWeight(1, 7, true, false, 0.0))
        assertTrue(SparkModel.importedWeight(1, 7, true, false, 0.0) > plain)
        assertEquals(-1.0, SparkModel.importedWeight(1, 3, true, false, 0.0), 0.001)
        assertEquals(0.0, SparkModel.importedWeight(0, 6, false, false, 0.0), 0.001)
        assertTrue(SparkModel.importedWeight(0, 9, false, false, 0.0) > 0)
        assertTrue(SparkModel.importedWeight(40, null, true, show = true, ageDays = 0.0) > SparkModel.importedWeight(40, null, true, show = false, ageDays = 0.0))
        assertTrue(SparkModel.importedWeight(1, null, true, false, 1000.0) < plain)
    }

    @Test fun syncedEpisodesBecomeShowsWithRatings() {
        val watched = listOf(app.ember.tv.tracker.SyncItem(true, "tt1", 1L),
            app.ember.tv.tracker.SyncItem(false, "tt2", 2L, 1, 1), app.ember.tv.tracker.SyncItem(false, "tt2", 2L, 1, 2))
        val ratings = mapOf(app.ember.tv.tracker.SyncItem(true, "tt1", 1L) to 9, app.ember.tv.tracker.SyncItem(true, "tt3", 3L) to 2)
        val h = app.ember.tv.tracker.HistoryTitle.fromSync(watched, ratings)
        assertEquals(3, h.size)
        assertEquals(9, h.first { it.tmdb == 1L }.rating)
        assertEquals(2, h.first { it.tmdb == 2L }.plays)
        assertEquals(false, h.first { it.tmdb == 3L }.watched)
    }
}

/** #437 keeping everything, and Spark rows as Home widgets. */
class SparkKeepAndWidgetsTest {
    @Test fun traktGenresBecomeTmdbIds() {
        assertEquals(listOf(878, 53), SparkNames.traktGenres(listOf("science-fiction", "thriller", "indie"), tv = false))
        assertEquals(listOf(10765, 9648), SparkNames.traktGenres(listOf("science-fiction", "thriller", "mystery"), tv = true))
    }

    @Test fun traktFullDetailsAreRead() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement("""{"title":"Arrival","year":2016,"overview":"Linguist.","genres":["drama","science-fiction"],
            "runtime":116,"certification":"PG-13","language":"en","country":"us","rating":7.8,"votes":40000,"released":"2016-11-11"}""")
        val d = app.ember.tv.tracker.TitleDetails.trakt(o)!!
        assertEquals(listOf("drama", "science-fiction"), d.genres)
        assertEquals(116, d.runtime); assertEquals("en", d.language); assertEquals(7.8, d.score!!, 0.001)
        assertEquals(null, app.ember.tv.tracker.TitleDetails.trakt(kotlinx.serialization.json.Json.parseToJsonElement("""{"title":"X"}""")))
    }

    @Test fun sparkListsAreValidWidgetSources() {
        val ok = app.ember.tv.data.CollectionSource(provider = app.ember.tv.data.HomeWidgets.LIST, catalogId = "spark:taste2")
        assertEquals(null, app.ember.tv.data.HomeWidgets.sourceError(ok))
        assertTrue(app.ember.tv.data.HomeWidgets.sparkLists.size == 10)
    }
}
