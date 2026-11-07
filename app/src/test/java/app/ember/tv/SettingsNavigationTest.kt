package app.ember.tv

import app.ember.tv.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun categoryTopicsRetainEveryExistingPageExactlyOnce() {
        val pages=SettingsCategory.entries.flatMap {it.pages}+SettingsTab.SEARCH
        assertEquals(SettingsTab.entries.toSet(),pages.toSet())
        assertEquals(pages.size,pages.distinct().size)
        assertEquals(8,SettingsCategory.entries.size)
    }
    @Test fun externalDestinationsOpenTheirPageInTheCorrectCategory() {
        SettingsTab.entries.forEach {page->
            val location=SettingsLocation.jump(page.name)!!
            assertEquals(page,location.page)
            assertEquals(page.group,location.category.representative.group)
        }
        assertNull(SettingsLocation.jump("removed-page"))
    }
    @Test fun compactAllSettingsOpensTheOverviewAndSearchOpensItsPage() {
        assertEquals(SettingsLocation(),SettingsLocation.jump(SettingsLocation.OVERVIEW))
        assertEquals(SettingsTab.SEARCH,SettingsLocation.category(SettingsCategory.SEARCH).page)
        assertNull(SettingsLocation.category(SettingsCategory.LOOK).page)
    }
    @Test fun backFromEveryTopicReturnsToItsOwnCategoryWithoutLosingThePageIdentity() {
        SettingsTab.entries.filter {it!=SettingsTab.SEARCH}.forEach {page->
            val location=SettingsLocation.open(page)
            assertEquals(SettingsLocation(location.category),location.topics())
            assertTrue(page in location.topics()!!.category.pages)
        }
        assertNull(SettingsLocation.open(SettingsTab.SEARCH).topics())
        assertNull(SettingsLocation().topics())
    }
    @Test fun restoredPageDeterminesCategoryAndInvalidSavedRoutesHaveASafeFallback() {
        assertEquals(SettingsLocation.open(SettingsTab.SERVERS),SettingsLocation.restore("GENERAL","SERVERS"))
        assertEquals(SettingsLocation(SettingsCategory.PLAYBACK),SettingsLocation.restore("PLAYBACK","old-page"))
        assertEquals(SettingsLocation(),SettingsLocation.restore("old-category","old-page"))
        assertEquals(SettingsLocation.open(SettingsTab.SEARCH),SettingsLocation.restore("SEARCH",""))
    }
    @Test fun relatedSettingsShareACategory() {
        assertEquals(listOf(SettingsTab.APPEARANCE,SettingsTab.SIZE,SettingsTab.PERFORMANCE,SettingsTab.REMOTE),SettingsCategory.LOOK.pages)
        assertEquals(SettingsCategory.HOME,SettingsCategory.of(SettingsTab.DEFAULT_HOME))
        assertEquals(SettingsCategory.HOME,SettingsCategory.of(SettingsTab.WEATHER))
        assertEquals(SettingsCategory.HOME,SettingsCategory.of(SettingsTab.SCREENSAVER))
        assertEquals(SettingsCategory.SOURCES,SettingsCategory.of(SettingsTab.POSTERS))
        assertEquals(SettingsCategory.PLAYBACK,SettingsCategory.of(SettingsTab.AUDIO_VIDEO))
        // About and Legal have their own section (#67).
        assertEquals(SettingsCategory.ABOUT,SettingsCategory.of(SettingsTab.LEGAL))
        assertEquals(SettingsCategory.ABOUT,SettingsCategory.of(SettingsTab.ABOUT))
        assertEquals(listOf(SettingsTab.ABOUT,SettingsTab.LEGAL),SettingsCategory.ABOUT.pages)
        // Every page is in the search index.
        assertEquals(SettingsTab.entries.toSet()-SettingsTab.SEARCH,SETTINGS_INDEX.map {it.first}.toSet())
    }
    @Test fun advancedIptvSearchTargetsTheDisclosureInsteadOfOnlyThePageTop() {
        val hit=SettingDefinitions.matching("user agent").single()
        assertEquals(SettingsTab.LIVE,hit.tab)
        assertEquals("iptv-advanced",hit.id)
    }
    @Test fun delayedLazyFocusRetriesAfterLayoutWithoutInvokingTheFallback()=runBlocking {
        var attempts=0;var frames=0;var fallback=false
        assertTrue(requestSettingsFocus({++attempts==2},{frames++},{fallback=true}))
        assertEquals(2,attempts);assertEquals(2,frames);assertFalse(fallback)
    }
    @Test fun unavailableFocusHasABoundedRetryAndReturnsToAnExistingControl()=runBlocking {
        var attempts=0;var fallback=false
        assertFalse(requestSettingsFocus({attempts++;error("not attached")},{},{fallback=true}))
        assertEquals(3,attempts);assertTrue(fallback)
    }
    @Test fun staleSidebarRestorationCannotUndoPageOrTopicFocusHandoffs() {
        val detail=SettingsLocation.open(SettingsTab.APPEARANCE)
        val overview=detail.topics()!!
        assertEquals(detail,detail.fromSidebar(SettingsCategory.SEARCH,true))
        assertEquals(overview,overview.fromSidebar(SettingsCategory.SEARCH,true))
        assertEquals(detail,detail.fromSidebar(SettingsCategory.LOOK,false))
        assertEquals(SettingsLocation.open(SettingsTab.SEARCH),overview.fromSidebar(SettingsCategory.SEARCH,false))
    }
}
