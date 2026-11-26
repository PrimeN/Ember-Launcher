package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.spark.SparkCandidate
import app.ember.tv.spark.SparkModel
import app.ember.tv.spark.SparkMood
import app.ember.tv.spark.SparkNames
import app.ember.tv.spark.SparkTaste
import app.ember.tv.spark.SparkVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #452–#454: votes, Mixes, Quick watch, moods and couch mode. */
class SparkStepsTest {
    @Test fun votesCountForAndAgainst() {
        assertTrue(SparkModel.voteWeight(SparkVote.LOVE) > SparkModel.voteWeight(SparkVote.LIKE))
        assertTrue(SparkModel.voteWeight(SparkVote.LIKE) > 0)
        assertTrue(SparkModel.voteWeight(SparkVote.NOT) < 0)
    }

    @Test fun couchModeLeansToWhatYouShare() {
        val you = mapOf(878 to .6, 18 to .3, 27 to .1)
        val them = mapOf(18 to .5, 35 to .4, 878 to .1)
        val both = SparkModel.blend(you, them)
        assertEquals(setOf(878, 18), both.keys)
        assertTrue(both.getValue(18) > both.getValue(878))
        assertEquals(1.0, both.values.sum(), 1e-9)
        assertTrue(SparkModel.blend(mapOf(27 to 1.0), mapOf(35 to 1.0)).isEmpty())
    }

    @Test fun mixesPairYourFavouriteKinds() {
        val taste = SparkTaste(mapOf(878 to .5, 53 to .3, 18 to .2), emptyMap(), emptyMap())
        assertEquals(listOf(878 to 53, 878 to 18), SparkModel.mixes(taste))
        assertEquals("Sci-fi thrillers", SparkNames.mix(53, 878))
        assertEquals("Drama meets western", SparkNames.mix(18, 37))
    }

    @Test fun mixAndQuickRowsAreBuilt() {
        val taste = SparkTaste(mapOf(878 to .5, 53 to .3, 18 to .2), emptyMap(), emptyMap())
        val pool = (1..20).map { i ->
            SparkCandidate(Meta(id = "tmdb:movie:$i", type = "movie", name = "Film $i", tmdbId = i.toLong()), "movie:$i",
                if (i <= 8) setOf(878, 53) else setOf(18), "en", 2015, 7.5, 3000, short = i > 12)
        }
        val rows = SparkModel.rows(pool, taste, emptyList(), emptySet())
        val mix = rows.first { it.id == "mix:878-53" }
        assertEquals("Sci-fi thrillers", mix.title)
        assertTrue(rows.any { it.id == "quick" })
        // Picks carry their kinds by name, for the moods.
        assertTrue(mix.items.all { "Sci-fi" in it.genres.orEmpty() })
    }

    @Test fun moodsKeepTheirKindsAndDropTheOnesTheyRuleOut() {
        val comedy = Meta(id = "1", type = "movie", name = "A", genres = listOf("Comedy"))
        val darkComedy = Meta(id = "2", type = "movie", name = "B", genres = listOf("Comedy", "Horror"))
        val thriller = Meta(id = "3", type = "movie", name = "C", genres = listOf("Thriller"))
        assertTrue(SparkMood.FEEL_GOOD.matches(comedy))
        assertFalse(SparkMood.FEEL_GOOD.matches(darkComedy))
        assertTrue(SparkMood.THRILLING.matches(thriller))
        assertTrue(SparkMood.ANY.matches(darkComedy))
    }
}
