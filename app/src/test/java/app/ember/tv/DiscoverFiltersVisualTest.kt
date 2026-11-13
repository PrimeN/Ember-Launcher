package app.ember.tv

import app.ember.tv.discover.*
import app.ember.tv.ui.FilterLook
import app.ember.tv.ui.exploreTags
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** #251–#253: Discover filters with pictures, more awards and bigger pickers. */
class DiscoverFiltersVisualTest {
    @Test fun everyAwardHasAKnownWikidataItemAndAType() {
        val keys = AwardBodies.all.map { it.key }
        assertEquals(keys.distinct(), keys)
        assertTrue(listOf("oscars", "globes", "bafta", "cannes", "venice", "berlin", "emmys", "actors").all { it in keys })
        AwardBodies.all.forEach { assertTrue(it.qid.matches(Regex("Q\\d+"))); assertTrue(it.films || it.shows) }
        assertEquals("Q1011547", AwardBodies.of("globes").qid)
        assertEquals(AwardBodies.of("oscars"), AwardBodies.of("unknown"))
    }

    @Test fun awardQueriesUseTheChosenAwardAndTheRightTmdbId() {
        val globes = ExploreRepository.awardQuery(listOf(1), ExploreQuery(oscars = OscarMode.WIN, award = "globes"), ExploreKind.TV)
        assertTrue(globes.contains("wd:Q1011547")); assertTrue(globes.contains("wdt:P4983")); assertFalse(globes.contains("Q19020"))
        val cannes = ExploreRepository.awardQuery(listOf(1), ExploreQuery(oscars = OscarMode.WIN, award = "cannes", bestPicture = true))
        assertTrue(cannes.contains("wd:Q179808")); assertTrue(cannes.contains("wdt:P4947")); assertFalse("no Best Picture for Cannes", cannes.contains("Q102427"))
    }

    @Test fun awardsLimitTitleTypes() {
        assertEquals(listOf(ExploreKind.TV), ExploreQuery(oscars = OscarMode.WIN, award = "emmys").kinds())
        assertEquals(listOf(ExploreKind.MOVIE, ExploreKind.TV), ExploreQuery(oscars = OscarMode.WIN, award = "globes").kinds())
        assertNotNull(ExploreQuery(kind = ExploreKind.MOVIE, oscars = OscarMode.WIN, award = "emmys").error())
        assertNull(ExploreQuery(kind = ExploreKind.TV, oscars = OscarMode.WIN, award = "globes").error())
        // Choosing a film award while on Shows moves to Movies; switching type drops an award that does not fit.
        assertEquals(ExploreKind.MOVIE, FilterLook.withAward(ExploreQuery(kind = ExploreKind.TV), "cannes").kind)
        assertEquals(OscarMode.WIN, FilterLook.withAward(ExploreQuery(), "emmys").oscars)
        assertEquals(OscarMode.ANY, FilterLook.withKind(ExploreQuery(oscars = OscarMode.WIN, award = "oscars"), ExploreKind.TV).oscars)
        assertEquals(OscarMode.WIN, FilterLook.withKind(ExploreQuery(oscars = OscarMode.WIN, award = "globes"), ExploreKind.TV).oscars)
    }

    @Test fun tagsShowTypeExactYearAndAward() {
        val tags = exploreTags(ExploreQuery(kind = ExploreKind.TV, fromYear = 1994, toYear = 1994, oscars = OscarMode.NOMINATED, award = "emmys")).map { it.first }
        assertTrue(tags.contains("Shows")); assertTrue(tags.contains("1994")); assertTrue(tags.contains("Emmys winners & nominees"))
        assertEquals(1, FilterLook.yearMode(ExploreQuery(fromYear = 1994, toYear = 1994)))
        assertEquals(2, FilterLook.yearMode(ExploreQuery(fromYear = 1994)))
        assertEquals(1994, FilterLook.typedYear("1994")); assertNull(FilterLook.typedYear("199")); assertNull(FilterLook.typedYear("1700"))
    }

    @Test fun pickerNotesAreNeverSavedAndRankByRole() {
        val ref = ExploreRef(5, "Name", null, note = "Directing · Film")
        assertFalse(Json.encodeToString(ref).contains("Directing"))
        val ranked = FilterLook.ranked(listOf(ExploreRef(1, "A", note = "Acting"), ExploreRef(2, "B", note = "Directing · X")), "Directors")
        assertEquals(2L, ranked.first().id)
        assertEquals("JD", FilterLook.initials("Jane Doe"))
    }

    @Test fun savedSearchesWithoutAnAwardKeepWorking() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<ExploreQuery>("""{"oscars":"WIN"}""")
        assertEquals("oscars", old.award)
    }

    @Test fun remoteFillsTheInstalledScreenAndTheRemoteTabFits() {
        val page = app.ember.tv.remote.RemotePage.app("TV", "#FF7A2F")
        assertTrue("#250: no gap under the tabs when installed", page.contains(".app{position:fixed;inset:0"))
        assertTrue(page.contains("classList.toggle('fit',v==='remote')") && page.contains("main.fit .pad{flex:1"))
        assertTrue("the title page fades at the top", page.contains(".ph:after{content:\"\";position:absolute;inset:0;background:linear-gradient(180deg,var(--bg) 0%"))
        assertTrue(page.contains("clip-path:inset(0 round 24px)"))
    }
}
