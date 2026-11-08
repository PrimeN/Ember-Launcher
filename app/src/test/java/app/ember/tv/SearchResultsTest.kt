package app.ember.tv

import app.ember.tv.data.*
import org.junit.Assert.*
import org.junit.Test

class SearchResultsTest {
    private val movie=Meta(id="tt123",type="movie",name="Ocean",releaseInfo="2025")
    @Test fun duplicateCatalogKeysAndRepeatedProviderItemsNeverProduceDuplicateCellKeys() {
        val row=CatalogRow("A","Movies",listOf(movie,movie),catalogKey="same")
        val results=SearchPresentation.results(listOf(row,row,row.copy(addonName="B")),emptyList(),SearchFilter.ALL,false)
        assertEquals(3,results.size);assertEquals(3,results.map {it.key}.distinct().size)
    }
    @Test fun combinedCopiesRetainEveryProviderAndTheWatchlistState() {
        val rows=listOf(CatalogRow("A","Movies",listOf(movie)),CatalogRow("B","Movies",listOf(movie)))
        val result=SearchPresentation.results(rows,listOf(movie),SearchFilter.ALL,true).single()
        assertEquals(listOf("Watchlist","A","B"),result.sources);assertTrue(result.saved)
        assertEquals(movie,result.meta)
        assertEquals(3,SearchPresentation.results(rows,listOf(movie),SearchFilter.ALL,false).size)
    }
    @Test fun sameNamesWithoutSharedIdentityStaySeparateAndEpisodesAreNotCollapsed() {
        val items=listOf(movie.copy(id="a"),movie.copy(id="b"),movie.copy(type="series",season=1,episode=1),
            movie.copy(type="series",season=1,episode=2),movie.copy(type="series"))
        val results=SearchPresentation.results(listOf(CatalogRow("A","All",items)),emptyList(),SearchFilter.ALL,true)
        assertEquals(5,results.size)
    }
    @Test fun watchlistFilterAndAppFilterDoNotLeakCatalogTitles() {
        val rows=listOf(CatalogRow("A","Movies",listOf(movie)))
        val saved=movie.copy(id="tt456")
        assertEquals(listOf(saved),SearchPresentation.results(rows,listOf(saved),SearchFilter.WATCHLIST,false).map {it.meta})
        assertTrue(SearchPresentation.results(rows,listOf(saved),SearchFilter.APPS,false).isEmpty())
    }
    @Test fun providerArrivalDoesNotChangeExistingStableKeys() {
        val a=CatalogRow("A","Movies",listOf(movie))
        val b=CatalogRow("B","Movies",listOf(movie.copy(id="tt456")))
        assertEquals(SearchPresentation.results(listOf(a),emptyList(),SearchFilter.ALL,false).first().key,
            SearchPresentation.results(listOf(a,b),emptyList(),SearchFilter.ALL,false).first().key)
        assertEquals(SearchPresentation.results(listOf(b),emptyList(),SearchFilter.ALL,false).first().key,
            SearchPresentation.results(listOf(a,b),emptyList(),SearchFilter.ALL,false).last().key)
    }
}
