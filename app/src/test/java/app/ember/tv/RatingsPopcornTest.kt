package app.ember.tv

import app.ember.tv.data.Ratings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** #200: the Rotten Tomatoes audience score (Popcornmeter) beside the critics' Tomatometer. */
class RatingsPopcornTest {
    @Test fun thePopcornmeterIsARatingSourceWithABundledIcon() {
        val source = Ratings.byId.getValue("popcorn")
        assertEquals("Popcornmeter", source.label)
        assertEquals("a blank link means the bundled picture is used, so nothing is downloaded", "", source.iconUrl)
        assertEquals(R.drawable.rating_popcorn, source.fallbackIcon)
        assertTrue(java.io.File("src/main/res/drawable-nodpi/rating_popcorn.png").length() > 1000)
        val ids = Ratings.SOURCES.map { it.id }
        assertEquals("right after the critics' score", ids.indexOf("tomatoes") + 1, ids.indexOf("popcorn"))
        assertEquals(ids.size, ids.toSet().size)
        // Someone with a saved order gets it after their own choices, as any new source.
        assertEquals(listOf("imdb", "tomatoes"), Ratings.ordered(listOf("imdb", "tomatoes")).take(2).map { it.id })
        assertTrue(Ratings.ordered(listOf("imdb", "tomatoes")).any { it.id == "popcorn" })
    }

    @Test fun mdblistGivesBothRottenTomatoesScores() {
        val page = Json.parseToJsonElement("""{"ratings":[{"source":"imdb","value":8.4,"score":84},{"source":"tomatoes","value":93,"score":93},
            {"source":"popcorn","value":88,"score":88},{"source":"metacritic","value":77},{"source":"letterboxd","value":4.1}]}""")
        val ratings = Ratings.parseMdb(page)
        assertEquals("93%", ratings["tomatoes"]); assertEquals("88%", ratings["popcorn"]); assertEquals("8.4", ratings["imdb"]); assertEquals("77", ratings["metacritic"])
        // The older name for the same score, and a title without one.
        assertEquals("61%", Ratings.parseMdb(Json.parseToJsonElement("""{"ratings":[{"source":"tomatoesaudience","value":61}]}"""))["popcorn"])
        assertNull(Ratings.parseMdb(Json.parseToJsonElement("""{"ratings":[{"source":"popcorn","value":0,"score":null}]}"""))["popcorn"])
        assertTrue(Ratings.parseMdb(null).isEmpty())
    }
}
