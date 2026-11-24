package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.discover.ExploreRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** #416: the person page's roles, credit count and best known titles. */
class PersonPageTest {
    private fun credit(id: Long, crew: Boolean = false, role: String? = "Hero", votes: Int = 1000, rating: Double = 7.0, year: Int = 2010, type: String = "movie") =
        ExploreRepository.PersonCredit(Meta(id = "tmdb:movie:$id", type = type, name = "T$id", tmdbId = id), LocalDate.of(year, 1, 1),
            1.0, rating, votes, role, if (crew) "Production" else "Acting", crew)

    private fun page(credits: List<ExploreRepository.PersonCredit>, knownFor: String? = "Acting") = ExploreRepository.PersonPage(
        "Name", null, null, null, null, knownFor, null, null, null, null, credits, emptyList())

    @Test fun rolesLeadWithWhatTheyAreKnownFor() {
        val credits = listOf(credit(1), credit(2, crew = true, role = "Producer"), credit(3, crew = true, role = "Producer"), credit(4, crew = true, role = "Director"))
        assertEquals(listOf("Actor", "Producer"), page(credits).roles)
        assertEquals(listOf("Director", "Actor", "Producer"), page(credits, "Directing").roles)
    }

    @Test fun titlesAreCountedOnceAndSinceTheFirstYear() {
        val p = page(listOf(credit(1, year = 1953), credit(1, crew = true, role = "Producer", year = 1953), credit(2, year = 2020)))
        assertEquals(2, p.titles.size)
        assertEquals(1953, p.since)
    }

    @Test fun knownForNeedsVotes() {
        val p = page(listOf(credit(1, votes = 10, rating = 9.9), credit(2, votes = 20_000, rating = 8.0), credit(3, votes = 500, rating = 6.0)))
        assertEquals(listOf(2L, 3L), p.knownForTitles.map { it.meta.tmdbId })
    }
}
