package app.ember.tv

import app.ember.tv.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** #263: an addon's own details and artwork come first. */
class AddonMetadataFirstTest {
    @After fun reset() { AddonMetadata.first = true }

    private val addon = Meta(id = "tt1", type = "movie", name = "Film", background = "https://addon/bg.jpg", logo = "https://addon/logo.png", poster = "https://addon/p.jpg")
    private val tmdb = HeroArtwork(backgrounds = listOf("https://tmdb/bg.jpg"), logo = "https://tmdb/logo.png", posters = listOf("https://tmdb/p.jpg"))

    @Test fun theAddonsArtworkIsKeptAndOnlyGapsAreFilled() {
        AddonMetadata.first = true
        val kept = tmdb.applyTo(addon)
        assertEquals("https://addon/bg.jpg", kept.background); assertEquals("https://addon/logo.png", kept.logo)
        val filled = tmdb.applyTo(addon.copy(logo = null, background = null))
        assertEquals("https://tmdb/bg.jpg", filled.background); assertEquals("https://tmdb/logo.png", filled.logo)
        assertTrue("nothing to fetch", AddonMetadata.complete(addon))
        assertFalse(AddonMetadata.complete(addon.copy(logo = null)))
    }

    @Test fun turnedOffTmdbAndFanartArtworkWinAsBefore() {
        AddonMetadata.first = false
        val replaced = tmdb.applyTo(addon)
        assertEquals("https://tmdb/bg.jpg", replaced.background); assertEquals("https://tmdb/logo.png", replaced.logo)
        assertFalse(AddonMetadata.complete(addon))
    }

    @Test fun titlesRememberTheCatalogAddonThatListedThem() {
        AddonMetadata.listed("https://aio.test/manifest.json", listOf(addon, Meta(id = "tt2", type = "series", name = "Show")))
        assertEquals("https://aio.test/manifest.json", AddonMetadata.origin("movie", "tt1"))
        assertNull(AddonMetadata.origin("series", "tt1"))
        AddonMetadata.listed("https://other.test/manifest.json", listOf(addon))
        assertEquals("the last catalog to list it", "https://other.test/manifest.json", AddonMetadata.origin("movie", "tt1"))
    }

    @Test fun theAddonsCastIsKeptWhenItGivesOne() {
        val fromAddon = Credits(listOf(Person("Addon Actor", "Lead")), "yt1")
        val fromTmdb = Credits(listOf(Person("TMDB Actor", "Lead", "https://tmdb/face.jpg")), "yt2")
        val ui = DetailsUi(meta = addon, addonCredits = fromAddon, tmdbCredits = fromTmdb)
        AddonMetadata.first = true
        assertEquals(listOf("Addon Actor", "TMDB Actor"), ui.credits!!.people.map { it.name })
        AddonMetadata.first = false
        assertEquals("TMDB Actor", ui.credits!!.people.single().name)
        assertEquals("the addon's trailer either way", "yt1", ui.credits!!.trailer)
    }

    @Test fun onByDefault() { assertTrue(MetaConfig().addonFirst) }
}
