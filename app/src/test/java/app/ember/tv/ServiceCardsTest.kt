package app.ember.tv

import app.ember.tv.data.PersonalListSources
import app.ember.tv.ui.ServiceBrands
import app.ember.tv.ui.ServiceCards
import app.ember.tv.ui.ServiceState
import app.ember.tv.ui.MediaServerTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #447: the Trackers page's Sources choice and the brand rows on the service pages. */
class ServiceCardsTest {
    @Test fun sourceChoiceStartsAtAllAndPicksOneTracker() {
        var off = emptySet<String>()
        assertEquals(PersonalListSources.ALL, PersonalListSources.trackerChoice(off, "main", "continue"))
        off = PersonalListSources.withTrackerChoice(off, "main", "continue", "trakt")
        assertEquals("trakt", PersonalListSources.trackerChoice(off, "main", "continue"))
        // Other lists, other profiles, Ember and servers are untouched.
        assertEquals(PersonalListSources.ALL, PersonalListSources.trackerChoice(off, "main", "watchlist"))
        assertEquals(PersonalListSources.ALL, PersonalListSources.trackerChoice(off, "kids", "continue"))
        assertTrue(PersonalListSources.enabled(off, "main", "continue", "ember"))
        assertTrue(PersonalListSources.enabled(off, "main", "continue", "plex"))
        assertTrue(!PersonalListSources.enabled(off, "main", "continue", "simkl"))
        off = PersonalListSources.withTrackerChoice(off, "main", "continue", PersonalListSources.ALL)
        assertEquals(PersonalListSources.ALL, PersonalListSources.trackerChoice(off, "main", "continue"))
    }

    @Test fun aHandMadeMixReadsAsMixed() {
        val off = setOf(PersonalListSources.key("main", "watchlist", "simkl"))
        assertEquals(PersonalListSources.MIXED, PersonalListSources.trackerChoice(off, "main", "watchlist"))
    }

    @Test fun brandsAndInitials() {
        assertNotNull(ServiceBrands.of("Trakt")?.logo)
        assertNotNull(ServiceBrands.of("plex")?.logo)
        assertNotNull(ServiceBrands.of("Claude (Anthropic)")?.logo)
        assertNull(ServiceBrands.of("TMDB")?.logo)
        assertNull(ServiceBrands.of("Some new addon"))
        assertEquals("SA", ServiceBrands.initials("Streaming Availability"))
        assertEquals("F", ServiceBrands.initials("Fanart.tv"))
        // Dark text on light brand fills (Plex yellow), white on dark ones.
        assertEquals(androidx.compose.ui.graphics.Color(0xFF141414), ServiceCards.ink(ServiceBrands.of("plex")!!.from))
        assertEquals(androidx.compose.ui.graphics.Color.White, ServiceCards.ink(ServiceBrands.of("trakt")!!.from))
    }

    @Test fun mediaServerRowsSayWhoAndHow() {
        val cards = MediaServerTiles.cards(plex = true, plexSignedOut = false, plexUser = "ann",
            servers = listOf(MediaServerTiles.Server("k1", "Den", "emby", "", signedOut = true, failing = false)))
        assertEquals("Connected as ann", cards[0].status)
        assertEquals(ServiceState.READY, cards[0].state)
        assertEquals("Signed out · sign in again", cards[1].status)
        assertEquals(ServiceState.PROBLEM, cards[1].state)
        assertEquals(ServiceState.ADD, cards.last().state)
        assertNotNull(cards[1].brand)
    }

    /** #451: a title on Trakt's and Simkl's watchlists still shows under "Only Trakt" whichever copy was kept. */
    @Test fun aTitleOnTwoTrackersKeepsBoth() {
        assertEquals("Simkl + Trakt", app.ember.tv.tracker.TrackedSources.join("Simkl", "Trakt"))
        assertEquals("Simkl + Trakt", app.ember.tv.tracker.TrackedSources.join("Simkl + Trakt", "Trakt"))
        val meta = app.ember.tv.data.Meta(id = "tt1", type = "movie", name = "Charade", progressSource = "Simkl + Trakt")
        assertEquals(listOf("simkl", "trakt"), PersonalListSources.sources(meta, "other"))
        val off = PersonalListSources.withTrackerChoice(emptySet(), "main", "watchlist", "trakt")
        assertTrue(PersonalListSources.sources(meta, "other").any { PersonalListSources.enabled(off, "main", "watchlist", it) })
        val single = meta.copy(progressSource = "Trakt")
        assertEquals(listOf("trakt"), PersonalListSources.sources(single, "other"))
    }
}
