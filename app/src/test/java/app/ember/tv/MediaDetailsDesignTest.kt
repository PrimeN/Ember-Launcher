package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Test

class MediaDetailsDesignTest {
    @Test fun resumeButtonShowsTheSavedTimestampIncludingHours() {
        assertEquals("Resume 00:01:39",ResumeAction.buttonLabel(99_000L to 3_600_000L,null))
        assertEquals("Resume 01:02:03",ResumeAction.buttonLabel(3_723_000L to 7_200_000L,.2f))
    }
    @Test fun percentageOnlyProgressUsesAnHonestFallbackOrEstimatedTime() {
        assertEquals("Resume at 50%",ResumeAction.buttonLabel(null,.5f))
        assertEquals("Resume ≈ 00:30:00",ResumeAction.buttonLabel(null,.5f,3_600_000))
        assertEquals("Resume ≈ 00:30:00",ResumeAction.buttonLabel(10_000L to 3_600_000L,.5f,3_600_000))
    }
    @Test fun completedUnknownOrInvalidProgressDoesNotShowAFakeResumeTime() {
        assertEquals("Play",ResumeAction.buttonLabel(null,null))
        assertEquals("Play",ResumeAction.buttonLabel(null,1f))
        assertEquals("Play",ResumeAction.buttonLabel(null,Float.NaN))
        assertEquals("Play",ResumeAction.buttonLabel(3_590_000L to 3_600_000L,null))
    }
    @Test fun automaticPreferenceAndPendingSourcesKeepPrimaryPlaybackPredictable() {
        assertEquals(DetailsPlaybackTarget.SOURCE,detailsPlaybackTarget(true,true,1,true))
        assertEquals(DetailsPlaybackTarget.CHOOSE,detailsPlaybackTarget(false,true,1,false))
        assertEquals(DetailsPlaybackTarget.CHOOSE,detailsPlaybackTarget(true,false,1,true))
        assertEquals(DetailsPlaybackTarget.SERVICE,detailsPlaybackTarget(true,false,1,false))
        assertEquals(DetailsPlaybackTarget.CHOOSE,detailsPlaybackTarget(true,false,2,false))
        assertEquals(DetailsPlaybackTarget.CHOOSE,detailsPlaybackTarget(true,false,0,false))
        assertEquals(DetailsPlaybackTarget.CHOOSE,detailsPlaybackTarget(true,false,1,false,serviceInstalled=false))
    }
    @Test fun sourceHandoffAccountsForEpisodeLoadingAndCastSections() {
        val movie=DetailsUi(Meta(id="movie",type="movie"))
        assertEquals(1,detailsSourceHeaderIndex(movie))
        val episodes=listOf(Episode("e1",1,1,"Pilot",null,null),Episode("e2",2,1,"Return",null,null))
        val show=movie.copy(episodes=episodes,loadingEpisodes=true,addonCredits=Credits(listOf(Person("Actor","Cast")),null))
        assertEquals(5,detailsSourceHeaderIndex(show))
        assertEquals(4,detailsSourceHeaderIndex(show.copy(loadingEpisodes=false)))
    }
    @Test fun episodeSynopsisFallsBackToSeriesTextWhenTheSourceSendsBlankOverview() {
        val series=DetailsUi(Meta(description="Series synopsis"),episode=Episode("e1",1,1,"Pilot",null,null,overview=""))
        assertEquals("Series synopsis",detailsSynopsis(series))
        assertEquals("Episode synopsis",detailsSynopsis(series.copy(episode=series.episode!!.copy(overview="Episode synopsis"))))
    }
}
