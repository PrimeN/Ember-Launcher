package app.ember.tv.player

import app.ember.tv.mediaserver.Chapter
import app.ember.tv.mediaserver.Marker
import org.junit.Assert.*
import org.junit.Test

class ChapterMarkersTest {
    @Test fun namedChaptersProduceLocalRangesWithoutSkippingPostCreditScenes() {
        val chapters=listOf(Chapter("Opening",0),Chapter("Episode",90_000),Chapter("ED",1_100_000),Chapter("Bonus scene",1_180_000))
        assertEquals(listOf(Marker("intro",0,90_000),Marker("credits",1_100_000,1_180_000)),ChapterMarkers.from(chapters,1_200_000))
        assertTrue(ChapterMarkers.from(listOf(Chapter("Intro",0)),90_000).isEmpty())
        assertTrue(ChapterMarkers.from(listOf(Chapter("Operation",0),Chapter("Other",90_000)),1_200_000).isEmpty())
    }
    @Test fun markersRequireValidBoundariesAndDoNotGuessUnknownDurations() {
        val chapters=listOf(Chapter("OP1",0),Chapter("Body",100_000),Chapter("Credits",1_100_000))
        assertEquals(2,ChapterMarkers.from(chapters,1_200_000).size)
        assertTrue(ChapterMarkers.from(chapters,-1).isEmpty())
        assertTrue(ChapterMarkers.from(listOf(Chapter("Opening",0),Chapter("Body",600_000)),1_200_000).isEmpty())
    }
}
