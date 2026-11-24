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
