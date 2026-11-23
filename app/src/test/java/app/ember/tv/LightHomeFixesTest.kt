package app.ember.tv

import androidx.compose.ui.graphics.Color
import app.ember.tv.home.PersonalHome
import app.ember.tv.light.LightMedia
import app.ember.tv.light.LightMediaProvider
import app.ember.tv.light.TvLiveEvents
import app.ember.tv.ui.lightWallpaperColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LightHomeFixesTest {
    private fun media(id: String, live: Boolean, continuing: Boolean = false) =
        LightMedia(id, "com.app", id, "", null, "intent:#Intent;end", null, 0, continuing, liveEvent = live)

    @Test fun liveSportsAndEventsStayOutUnlessAllowed() {
        // #391
        assertTrue(TvLiveEvents.isLiveEvent(1, -1, ""))
        assertTrue(TvLiveEvents.isLiveEvent(0, android.media.tv.TvContract.PreviewPrograms.TYPE_EVENT.toLong(), ""))
        assertTrue(TvLiveEvents.isLiveEvent(0, -1, "MOVIES, SPORTS"))
        assertFalse(TvLiveEvents.isLiveEvent(0, android.media.tv.TvContract.PreviewPrograms.TYPE_MOVIE.toLong(), "DRAMA"))
        val items = listOf(media("game", live = true), media("film", live = false), media("replay", live = true, continuing = true))
        assertEquals(listOf("film", "replay"), LightMediaProvider.personalized(items, PersonalHome()).map { it.id })
        assertEquals(listOf("game", "film", "replay"), LightMediaProvider.personalized(items, PersonalHome(liveEvents = true)).map { it.id })
    }

    @Test fun emberGlowUsesTheThemesOwnColours() {
        // #394: Ember glow and the default wallpaper come from the accent, not fixed navy and brown.
        val orange = Color(0xFFFF7A2F); val blue = Color(0xFF4F94FF)
        val ember = lightWallpaperColors("ember", orange)
        assertTrue(ember[1].red > ember[1].blue)
        assertNotEquals(ember, lightWallpaperColors("ember", blue))
        assertTrue(lightWallpaperColors("ember", blue)[1].blue > lightWallpaperColors("ember", blue)[1].red)
        assertEquals(lightWallpaperColors("ocean", orange), lightWallpaperColors("ocean", blue))
    }
}
