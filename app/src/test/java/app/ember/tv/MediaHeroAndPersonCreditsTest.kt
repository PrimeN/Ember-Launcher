package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.*
import androidx.compose.ui.input.key.Key
import org.junit.Test
import org.junit.Assert.*

class MediaHeroAndPersonCreditsTest {
    private fun title(id:String,name:String="Film",year:String?="2020",type:String="movie",tmdb:Long?=null)=Meta(id=id,name=name,type=type,releaseInfo=year,tmdbId=tmdb)
    @Test fun delayedArtworkIndicatorFollowsTheShownTitleInsteadOfTheRequestedIndex() {
        val items=listOf(title("a"),title("b"),title("c"))
        assertEquals(0,HeroBanner.displayIndex(2,items,items[0]))
        assertEquals(2,HeroBanner.displayIndex(2,items,items[2].copy(description="Enriched")))
        assertEquals(2,HeroBanner.displayIndex(2,items,title("not-in-this-profile")))
        assertEquals(0,HeroBanner.displayIndex(10,emptyList(),null))
        assertEquals(2,HeroBanner.displayIndex(99,items,null))
    }
    @Test fun onlyTheOuterActionEdgesPageInBothLayoutDirections() {
        assertEquals(Key.DirectionLeft,heroEdgeKey(previous=true,rtl=false))
        assertEquals(Key.DirectionRight,heroEdgeKey(previous=false,rtl=false))
        assertEquals(Key.DirectionRight,heroEdgeKey(previous=true,rtl=true))
        assertEquals(Key.DirectionLeft,heroEdgeKey(previous=false,rtl=true))
    }
    @Test fun imdbOnlyCurrentFilmDoesNotReappearAmongOtherPublicCredits() {
        val current=title("tt123")
        val credits=listOf(title("tmdb:movie:42",tmdb=42),title("tmdb:movie:43",year="2024",tmdb=43),
            title("tmdb:tv:42",type="series",tmdb=42),title("different",name="Another",tmdb=44))
        assertEquals(credits.drop(1),otherPersonTitles(current,credits))
    }
    @Test fun conflictingStableIdsAndUnknownDatesCannotHideUnrelatedSameNameTitles() {
        val current=title("tt123",tmdb=12)
        val other=title("tmdb:movie:34",tmdb=34)
        assertEquals(listOf(other),otherPersonTitles(current,listOf(current.copy(id="native:1"),other)))
        assertEquals(listOf(other),otherPersonTitles(current.copy(tmdbId=null,releaseInfo=null),listOf(other)))
        val conflicting=title("tt999")
        assertEquals(listOf(conflicting),otherPersonTitles(current,listOf(conflicting)))
    }
}
