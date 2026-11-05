package app.ember.tv

import app.ember.tv.data.Net
import app.ember.tv.player.ControlsState
import app.ember.tv.ui.rowArtworkSize
import org.junit.Assert.*
import org.junit.Test

class MenuArtworkRegressionTest {
    @Test fun imageTrafficHasIndependentBoundedSlotsButReusesConnections() {
        val api=Net.client(5,15)
        val images=Net.artworkClient(5,15)
        val moreImages=Net.artworkClient()
        assertNotSame(api.dispatcher,images.dispatcher)
        assertSame(images.dispatcher,moreImages.dispatcher)
        assertSame(api.connectionPool,images.connectionPool)
        assertSame(api.dns,images.dns)
        assertEquals(16,images.dispatcher.maxRequests)
        assertEquals(8,images.dispatcher.maxRequestsPerHost)
        assertNull(images.cache)
        assertEquals(5000,images.connectTimeoutMillis)
        assertEquals(15000,images.readTimeoutMillis)
    }
    @Test fun closingAPlayerMenuClearsItsLastTabAndFocusWithoutChangingPlayback() {
        val state=ControlsState()
        state.menu="options";state.menuTab="subtitles";state.menuRow="Language";state.menuFocus="Language"
        state.position=90_000;state.duration=600_000
        state.closeMenuSession()
        assertNull(state.menu);assertNull(state.menuRow)
        assertEquals("",state.menuFocus);assertEquals("playback",state.menuTab)
        assertEquals(90_000L,state.position);assertEquals(600_000L,state.duration)
    }
    @Test fun prefetchUsesActualCardPixelsAcrossPosterAndLandscapeSizes() {
        assertEquals(200 to 300,rowArtworkSize(200,false))
        assertEquals(480 to 720,rowArtworkSize(480,false))
        assertEquals(640 to 360,rowArtworkSize(640,true))
        assertEquals(641 to 361,rowArtworkSize(641,true))
        assertEquals(1 to 2,rowArtworkSize(0,false))
    }
}
