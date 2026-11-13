package app.ember.tv

import app.ember.tv.data.*
import app.ember.tv.ui.fitHeroPortrait
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HeroArtworkSelectionTest {
    private fun parse(value: String) = Json.parseToJsonElement(value)

    @Test fun prefersAdequateTextFreeLandscapeAndRejectsPortraitOrUnsafePaths() {
        val art = HeroArtworkSelection.tmdb(parse("""{"backdrops":[
          {"file_path":"/portrait.jpg","width":2000,"height":3000},
          {"file_path":"https://other.test/a.jpg","width":1920,"height":1080},
          {"file_path":"/tiny.jpg","width":400,"height":200},
          {"file_path":"/french.jpg","width":3840,"height":2160,"iso_639_1":"fr","vote_average":10,"vote_count":500},
          {"file_path":"/clean.jpg","width":1920,"height":1080,"iso_639_1":null,"vote_average":8,"vote_count":50}
        ]}"""), "en-CA")
        assertTrue(art.backgrounds.first().endsWith("/clean.jpg"))
        assertEquals(2, art.backgrounds.size)
        assertTrue(art.backgrounds.none { it.contains("portrait") || it.contains("other.test") || it.contains("tiny") })
    }

    @Test fun logosPreferRequestedLanguageAndUseBoundedPngWithEnglishFallback() {
        val json = parse("""{"images":{"logos":[
          {"file_path":"/en.png","width":1200,"height":300,"iso_639_1":"en"},
          {"file_path":"/fr.jpg","width":1200,"height":300,"iso_639_1":"fr"},
          {"file_path":"/fr.png","width":800,"height":200,"iso_639_1":"fr"},
          {"file_path":"/bad.svg","width":2000,"height":500,"iso_639_1":"fr"}
        ]}}""")
        assertEquals("https://image.tmdb.org/t/p/w500/fr.png", HeroArtworkSelection.tmdb(json,"fr-CA").logo)
        assertEquals("https://image.tmdb.org/t/p/w500/en.png", HeroArtworkSelection.tmdb(json,"de-DE").logo)
        assertEquals("en", HeroArtworkSelection.language(""))
        assertEquals("pt", HeroArtworkSelection.language("pt_BR"))
    }

    @Test fun fanartUsesCorrectMediaFieldsLanguageAndPopularityWithoutForwardingCredentials() {
        val json = parse("""{"moviebackground":[
          {"url":"http://assets.fanart.tv/a.jpg","lang":"00","likes":"2"},
          {"url":"https://assets.fanart.tv/b.jpg","lang":"00","likes":"12"},
          {"url":"https://evil.test/a.jpg","lang":"00","likes":"999"}],
          "hdmovielogo":[{"url":"https://assets.fanart.tv/fr.png","lang":"fr","likes":"1"},
          {"url":"https://assets.fanart.tv/en.png","lang":"en","likes":"50"}],
          "showbackground":[{"url":"https://assets.fanart.tv/tv.jpg"}],
          "hdtvlogo":[{"url":"https://user:password@assets.fanart.tv/no.png"}] }""")
        val movie = HeroArtworkSelection.fanart(json,true,"fr")
        assertEquals(listOf("https://assets.fanart.tv/b.jpg","https://assets.fanart.tv/a.jpg"), movie.backgrounds)
        assertEquals("https://assets.fanart.tv/fr.png", movie.logo)
        val tv = HeroArtworkSelection.fanart(json,false,"en")
        assertEquals(listOf("https://assets.fanart.tv/tv.jpg"), tv.backgrounds); assertNull(tv.logo)
    }

    @Test fun matchesStrongIdsAndNeverGuessesByTitleOrCrossesMediaTypes() {
        assertNull(HeroIdentity.of(Meta(name="A remake",type="movie")))
        assertNull(HeroIdentity.of(Meta(id="tt1/../../x",type="movie")))
        assertNull(HeroIdentity.of(Meta(id="tmdb:movie:42",type="series")))
        assertNull(HeroIdentity.of(Meta(type="podcast",tmdbId=42)))
        assertEquals(42L, HeroIdentity.of(Meta(id="tmdb:tv:42",type="series"))?.tmdb)
        assertEquals("tt123", HeroIdentity.of(Meta(id="tt123:2:3",type="series"))?.imdb)
        assertEquals(42L, HeroIdentity.of(Meta(id="tmdb:42",type="movie",tmdbId=0))?.tmdb)
    }

    @Test fun enrichmentPreservesCatalogPlaybackProgressAndEpisodeIdentity() {
        val original = Meta(id="server:one",type="series",name="Title",poster="poster",background="old",description="Plot",
            tmdbId=1,season=2,episode=3,progress=.4f,serverRef="native-reference",serverName="My server")
        // #263: with the addon's metadata first turned off, public artwork replaces the catalog's.
        val enriched = try { app.ember.tv.data.AddonMetadata.first = false; HeroArtwork(listOf("new"),"logo").applyTo(original) } finally { app.ember.tv.data.AddonMetadata.first = true }
        assertEquals(original.copy(background="new",logo="logo"), enriched)
        assertEquals(original, HeroArtwork().applyTo(original))
    }

    @Test fun portraitFallbackFitsRatherThanBeingZoomedAndUnknownSizesDoNotForceFit() {
        assertTrue(fitHeroPortrait(600,900)); assertTrue(fitHeroPortrait(800,800))
        assertFalse(fitHeroPortrait(1920,1080)); assertFalse(fitHeroPortrait(0,0)); assertFalse(fitHeroPortrait(-1,100))
    }
}
