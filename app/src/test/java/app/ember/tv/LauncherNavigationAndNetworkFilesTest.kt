package app.ember.tv

import app.ember.tv.ui.CardOrigin
import app.ember.tv.ui.FocusReturnState
import app.ember.tv.player.*
import androidx.media3.common.Player
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class LauncherNavigationAndNetworkFilesTest {
    @Test fun duplicateTitleRestoresOnlyItsOriginalRowAndProfile() {
        val state=FocusReturnState()
        val origin=CardOrigin("adult","home",42,"movie:1")
        state.focused=origin;state.open(origin.item)
        assertTrue(state.matches(origin))
        assertFalse(state.matches(origin.copy(slot=43)))
        assertFalse(state.matches(origin.copy(profile="kids")))
        assertFalse(state.matches(origin.copy(scene="search")))
        state.focused=origin.copy(scene="details")
        assertTrue(state.matches(origin))
        state.clear();assertFalse(state.matches(origin))
    }
    @Test fun listenerAdapterCanActuallyBeFoundAndRemovedAndForwardsCallbacks() {
        var playing=false
        val listener=object:Player.Listener {override fun onIsPlayingChanged(value:Boolean){playing=value}}
        val adapter=playbackListener(listener) {error("No player needed for this event")}
        assertEquals(adapter,adapter);assertNotEquals(adapter,listener)
        val registered=hashSetOf(adapter)
        adapter.onIsPlayingChanged(true);assertTrue(playing)
        assertTrue(registered.remove(adapter));assertTrue(registered.isEmpty())
    }
    @Test fun playAllSkipsSidecarsButIncludesAudioAndUppercaseVideo() {
        fun e(name:String)=NetworkEntry(name,false,100)
        listOf("movie.MKV","movie.mp4","album.FLAC","concert.mka").forEach {assertTrue(it,MediaFileTypes.playable(e(it)))}
        listOf("poster.jpg","movie.nfo","movie.srt","thumb.webp","README","sub.ass").forEach {assertFalse(it,MediaFileTypes.playable(e(it)))}
        assertFalse(MediaFileTypes.playable(NetworkEntry("folder.mp4",true,100)))
    }
    @Test fun foldersLargerThanOldLimitHaveContinuationWithNoMissingEntry() {
        var offset=0;val found=mutableListOf<String>()
        do {
            val listing=NetworkListing("",offset)
            for(i in 0 until 2501)if(!listing.add(NetworkEntry("movie$i.mkv",false,10)))break
            val page=listing.result();found+=page.entries.map {it.name};offset=page.next?:-1
        } while(offset>=0)
        assertEquals(2501,found.size);assertEquals(2501,found.distinct().size)
        assertTrue("movie2500.mkv" in found)
    }
    @Test fun davPageStaysInFolderAndIgnoresParentAndForeignOrigins() {
        val list=NetworkListing("",0,1)
        val xml="""<d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/movies/</d:href></d:response>
          <d:response><d:href>https://evil.test/movies/no.mkv</d:href></d:response>
          <d:response><d:href>/movies/a.mkv</d:href><d:propstat><d:prop><d:getcontentlength>8</d:getcontentlength></d:prop></d:propstat></d:response>
          <d:response><d:href>/movies/b.mkv</d:href></d:response>
        </d:multistatus>"""
        WebDavListing.parse(StringReader(xml),"https://server.test/movies/".toHttpUrl(),list)
        assertEquals(listOf("a.mkv"),list.result().entries.map {it.name});assertEquals(1,list.result().next)
    }
    @Test fun davRejectsExternalEntityDeclarationsEvenWhenSplitAcrossReads() {
        val xml="""<!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"/>"""
        val reader=object:StringReader(xml){override fun read(c:CharArray,o:Int,l:Int)=super.read(c,o,minOf(l,2))}
        assertThrows(Exception::class.java){WebDavListing.parse(reader,"https://server.test/".toHttpUrl(),NetworkListing("",0))}
    }
}
