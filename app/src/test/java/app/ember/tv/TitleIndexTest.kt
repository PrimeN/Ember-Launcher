package app.ember.tv

import app.ember.tv.data.Meta
import app.ember.tv.data.TitleIndex
import app.ember.tv.data.mergeTitlesIndexed
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TitleIndexTest {
    @Test fun aliasesTypesAndMissingYearsMatchExistingMembership() {
        val random = Random(42)
        val names = listOf("Alpha", "ALPHA", "Beta", "", "İ", "i", "ı", "Straße", "STRASSE")
        fun title() = Meta(
            id = listOf("", "tt1", "tt1:2:3", "server:one", "two").random(random),
            type = listOf("movie", "series", "anime", "").random(random), name = names.random(random),
            imdbId = listOf(null, "", "tt2", "tt3").random(random),
            tmdbId = listOf(null, 1L, 2L).random(random),
            releaseInfo = listOf(null, "", "2020", "2020–2024", "2021").random(random))
        repeat(50) {
            val saved = List(30) { title() }
            val index = TitleIndex(saved)
            repeat(80) {
                val query = title()
                assertEquals("$query", saved.any { it.sameTitle(query) }, query in index)
            }
        }
    }

    @Test fun dedupKeepsFirstWithoutMakingAliasMatchingTransitive() {
        val one = Meta("one", "movie", "A", releaseInfo = "2000")
        val discarded = Meta("other", "movie", "A", releaseInfo = null, imdbId = "tt99")
        val otherYear = Meta("three", "movie", "A", releaseInfo = "2020")
        val otherName = Meta("four", "movie", "B", imdbId = "tt99")
        assertEquals(listOf(one, otherYear, otherName), mergeTitlesIndexed(listOf(one), listOf(discarded, otherYear, otherName)))
    }

    @Test fun mergeMatchesLegacyAcrossProviderListsAndRemakes() {
        val titles = (0 until 500).map { i -> Meta("id$i", if (i % 3 == 0) "movie" else "series", "Title ${i % 19}",
            releaseInfo = if (i % 13 == 0) null else "${2000 + i % 5}", imdbId = "tt${i % 71}") }
        val expected = mutableListOf<Meta>()
        titles.forEach { if (expected.none { old -> old.sameTitle(it) }) expected += it }
        assertEquals(expected, mergeTitlesIndexed(titles.take(200), titles.drop(200)))
        assertTrue(mergeTitlesIndexed().isEmpty())
    }
}
