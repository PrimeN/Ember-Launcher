package app.ember.tv

import app.ember.tv.ui.*
import org.junit.Assert.*
import org.junit.Test

class UiArtworkBudgetTest {
    @Test fun lowMotionBudgetRetainsFocusAndDoesNotResizeHero() {
        val low = RenderOptions(device = "low", quality = "economy")
        val high = RenderOptions(device = "high", quality = "high")
        assertEquals(120, low.animationMillis(400, false))
        assertEquals(80, low.animationMillis(80, false))
        assertEquals(0, high.animationMillis(400, true))
        assertEquals(400, high.animationMillis(400, false))
        assertEquals(1.02f, low.focusScale(1.05f, false), .001f)
        assertEquals(1f, low.focusScale(1f, false), .001f)
        assertEquals(1f, high.focusScale(1.05f, true), .001f)
        assertFalse(low.expandHeroOnFocus); assertFalse(low.progressiveHero)
        assertTrue(high.expandHeroOnFocus); assertTrue(high.progressiveHero)
        assertFalse(RenderOptions(quality = "economy").progressiveHero)
    }

    @Test fun knownProviderSizesPreserveRawQueriesAndPosterFallbacks() {
        val url = "https://image.tmdb.org/t/p/original/a%20b.jpg?x=a%2Fb&api_key=test#preview"
        assertEquals(url.replace("/original/", "/w780/"), cardArt(url, wide = true, uhd = false))
        assertEquals(url.replace("/original/", "/w1280/"), heroArtworkUrl(url, RenderOptions(device = "low", quality = "economy")))
        assertEquals(url, heroArtworkUrl(url, RenderOptions()))
        assertEquals(url, heroArtworkUrl(url, RenderOptions(quality = "high")))
        assertEquals(url.replace("/original/", "/w780/"), heroArtworkUrl(url, RenderOptions(device = "low"), wide = false))
        assertEquals(url.replace("/original/", "/w342/"), cardArt(url, wide = false, uhd = false))
        val poster = "https://images.metahub.space/poster/small/tt1/img"
        assertEquals(poster.replace("/small/", "/large/"), heroArtworkUrl(poster, RenderOptions()))
        assertEquals("https://images.metahub.space/background/medium/tt1/img", heroArtworkUrl("https://images.metahub.space/background/large/tt1/img", RenderOptions(device = "low")))
    }

    @Test fun arbitraryOrMisleadingUrlsAreNotRewritten() {
        listOf("https://server.example/background/large/tt1/img?token=secret",
            "https://image.tmdb.org.evil/t/p/original/a.jpg",
            "https://example.com/t/p/w1280/a.jpg?url=image.tmdb.org/t/p/original/",
            "content://images.metahub.space/background/large/tt1/img",
            "not a URL", "https://image.tmdb.org/other/original/a.jpg").forEach {
            assertEquals(it, sizedArtworkUrl(it, true, 780))
        }
    }
}
