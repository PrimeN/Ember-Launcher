package app.ember.tv

import app.ember.tv.data.Prefs
import app.ember.tv.iptv.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LiveViewsTest {
    @get:Rule val temp=TemporaryFolder()
    private val channels=listOf(
        Channel("a:1","a","A","https://a.test/1",group="News"),
        Channel("a:2","a","B","https://a.test/2",group="Movies"),
        Channel("b:1","b","C","https://b.test/1",group="News"))
    @Test fun freshAndLegacyViewsOpenTheGuideAndOnlyCanvasIsAnAlternateMode() {
        listOf(null,"","grid","list","invalid").forEach {assertEquals(LiveViews.GUIDE,LiveViews.normalize(it))}
        assertEquals(LiveViews.CANVAS,LiveViews.normalize("canvas"))
    }
    @Test fun viewChoiceSurvivesReloadingPreferencesAndCannotSaveAnUnknownMode() {
        val context=PersistenceContext(temp.newFolder())
        assertEquals(LiveViews.GUIDE,Prefs(context).iptvView)
        Prefs(context).iptvView=LiveViews.CANVAS
        assertEquals(LiveViews.CANVAS,Prefs(context).iptvView)
        Prefs(context).iptvView="invalid"
        assertEquals(LiveViews.GUIDE,Prefs(context).iptvView)
    }
    @Test fun aPlaylistScopeAppliesToFavouritesAndCustomListsWithoutExpandingVisibility() {
        val library=LiveLibrary(lists=listOf(ChannelList("mine","Mine",channels.map {it.key}+"hidden")))
        val favourites=LiveChoice("favourites").channels(channels,library,channels.map {it.key}.toSet(),emptyList())
        val custom=LiveChoice("custom",list="mine").channels(channels,library,emptySet(),emptyList())
        assertEquals(channels.take(2),LiveViews.scoped(favourites,"a"))
        assertEquals(listOf(channels.last()),LiveViews.scoped(custom,"b"))
        assertTrue(LiveViews.scoped(custom,"missing").isEmpty())
    }
    @Test fun categoriesCombineOnlyTheVisibleChannelsInTheChosenPlaylist() {
        val news=LiveChoice("group",group="News").channels(channels,LiveLibrary(),emptySet(),emptyList())
        assertEquals(listOf(channels.first(),channels.last()),news)
        assertEquals(listOf(channels.first()),LiveViews.scoped(news,"a"))
        assertEquals(channels,LiveChoice("all").channels(channels,LiveLibrary(),emptySet(),emptyList()))
    }
    @Test fun changingCategoryKeepsTheSelectedPlaylistUntilAllPlaylistsIsExplicitlyChosen() {
        val current=LiveChoice("source","a")
        assertEquals("a",LiveViews.sourceAfterChoice(current,LiveChoice("favourites"),null))
        assertEquals("a",LiveViews.sourceAfterChoice(current,LiveChoice("group",group="News"),null))
        assertNull(LiveViews.sourceAfterChoice(current,LiveChoice("all"),"a"))
        assertEquals("b",LiveViews.sourceAfterChoice(current,LiveChoice("source","b"),"a"))
    }
    @Test fun staleCategoryRepairsWhenItDisappears() {
        assertEquals(LiveChoice("all"),LiveChoice("group",group="Gone").repaired(emptyList(),LiveLibrary(),channels))
    }
}
