package app.ember.tv

import app.ember.tv.iptv.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LiveLibraryTest {
    private val news = Channel("a:1", "a", "News", "https://example.com/1", group = "News")
    private val sports = Channel("a:2", "a", "Sport", "https://example.com/2", group = "Sport")
    private val otherNews = Channel("b:1", "b", "News", "https://example.com/3", group = "News")
    private val channels = listOf(news, sports, otherNews)

    @Test fun hidingAGroupDoesNotHideTheSameNamedGroupInAnotherPlaylist() {
        val library = LiveLibrary(hiddenGroups = setOf(LiveLibrary.groupKey("a", "News")))
        assertEquals(listOf(sports, otherNews), library.visible(channels, setOf("a", "b")))
        assertEquals(channels, library.copy(hiddenGroups = emptySet()).visible(channels, setOf("a", "b")))
    }

    @Test fun hiddenChannelsAndDisabledOrRemovedSourcesAreExcludedTogether() {
        val library = LiveLibrary(hiddenChannels = setOf(sports.key))
        assertEquals(listOf(news), library.visible(channels, setOf("a")))
        assertTrue(library.visible(channels, emptySet()).isEmpty())
    }

    @Test fun customListsReferenceExistingChannelsAcrossPlaylistsInChosenOrder() {
        val library = LiveLibrary(lists = listOf(ChannelList("mine", "Evening", listOf(otherNews.key, news.key))))
        assertEquals(listOf(otherNews, news), library.listChannels("mine", channels))
        assertEquals(listOf(news), library.listChannels("mine", listOf(news)))
        // Temporarily missing channels are retained in the saved list and reappear on refresh.
        assertEquals(listOf(otherNews, news), library.listChannels("mine", channels))
        assertTrue(library.listChannels("deleted", channels).isEmpty())
    }

    @Test fun hiddenChannelsStayHiddenInCustomListsAsWellAsFavouritesAndSearch() {
        val library = LiveLibrary(hiddenChannels = setOf(news.key),
            hiddenGroups = setOf(LiveLibrary.groupKey("b", "News")),
            lists = listOf(ChannelList("mine", "Mine", channels.map { it.key })))
        val visible = library.visible(channels, setOf("a", "b"))
        assertEquals(listOf(sports), library.listChannels("mine", visible))
        assertTrue(visible.filter { it.key in setOf(news.key, otherNews.key) }.isEmpty())
        assertTrue(visible.filter { it.name.contains("News") }.isEmpty())
    }

    @Test fun membershipChangesPreserveOtherListsAndDoNotDuplicateChannels() {
        val library = LiveLibrary(lists = listOf(ChannelList("one", "One"), ChannelList("two", "Two", listOf(sports.key))))
        val added = library.toggleMember("one", news.key)
        assertEquals(listOf(news), added.listChannels("one", channels))
        assertEquals(library, added.toggleMember("one", news.key))
        assertEquals(listOf(sports), added.listChannels("two", channels))
        assertEquals(added, added.toggleMember("missing", news.key))
    }

    @Test fun preferencesRoundTripAndOldInstallationsHaveDefaults() {
        val library = LiveLibrary(setOf(news.key), setOf(LiveLibrary.groupKey("a", "Sport")),
            listOf(ChannelList("mine", "Evening", listOf(otherNews.key))), showOnNow = false,
            hiddenSections = setOf("recent", "custom:mine"))
        val encoded = Json.encodeToString(library)
        assertEquals(library, Json.decodeFromString<LiveLibrary>(encoded))
        assertEquals(LiveLibrary(), Json.decodeFromString<LiveLibrary>("{}"))
    }
}
