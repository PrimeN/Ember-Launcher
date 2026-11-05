package app.ember.tv

import app.ember.tv.data.*
import org.junit.Assert.*
import org.junit.Test

class CollectionIdentityFixesTest {
    private fun movie(id: String, imdb: String? = null, tmdb: Long? = null) = Meta(id, "movie", "Film", imdbId = imdb, tmdbId = tmdb)
    @Test fun knownImdbAndTmdbAliasesMergeAndKeepTheFirstCard() {
        val first = movie("tt1234567", tmdb = 100)
        val other = movie("tmdb:100").copy(name = "Other provider name")
        assertEquals(listOf(first), distinctCollectionTitles(listOf(first, other)))
    }
    @Test fun aLateIdentityBridgeMergesEarlierAliasesAndKeepsIdsForLaterPages() {
        val imdb = movie("tt1234567")
        val tmdb = movie("tmdb:100")
        val bridge = movie("provider:1", "tt1234567", 100)
        val merged = distinctCollectionTitles(listOf(imdb, tmdb, bridge))
        assertEquals(1, merged.size); assertEquals(imdb.id, merged.single().id); assertEquals(100L, merged.single().tmdbId)
        assertEquals(1, distinctCollectionTitles(merged + tmdb).size)
    }
    @Test fun moviesShowsEpisodesAndDifferentEpisodesNeverCollapse() {
        val film = movie("tmdb:100", tmdb = 100)
        val show = film.copy(type = "series")
        val episode = show.copy(season = 1, episode = 1)
        assertEquals(4, distinctCollectionTitles(listOf(film, show, episode, episode.copy(episode = 2))).size)
        val rawEpisode = Meta("tt1234567:1:1", "series", "Episode")
        assertEquals(1, distinctCollectionTitles(listOf(rawEpisode, rawEpisode.copy(id = "other", imdbId = "tt1234567", season = 1, episode = 1))).size)
    }
    @Test fun matchingNamesAndYearsCannotMergeUnrelatedTitles() {
        assertEquals(2, distinctCollectionTitles(listOf(movie("local:1"), movie("local:2"))).size)
        assertEquals(2, distinctCollectionTitles(listOf(movie(""), movie(""))).size)
    }
    @Test fun animeAndTvAliasesShareSeriesIdentityWithoutLosingTheChosenCardType() {
        val anime = Meta("tt1234567", "anime", "Series", tmdbId = 100)
        val tv = anime.copy(id = "tmdb:100", type = "tv", imdbId = null)
        assertEquals(listOf(anime), distinctCollectionTitles(listOf(anime, tv)))
    }
}
