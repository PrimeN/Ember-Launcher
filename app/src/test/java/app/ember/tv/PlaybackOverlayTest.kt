package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ember.tv.player.*
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackOverlayTest {
    @get:Rule val compose=createComposeRule()
    private val seeks=mutableListOf<Long>()
    private var plays=0
    private var pickedAudio=-1
    private var pickedSubtitle=-1
    private fun state(design:String=PlayerInterfaces.MODERN)=ControlsState().apply {
        visible=true;this.design=design;title="The Last Light";seasonEpisode="S1 · E4"
        details=PlayerDetails(title,episodeTitle="Beyond the Ridge",synopsis="An explorer follows the last light into the mountains.")
        position=38*60_000L+24_000;duration=101*60_000L+8_000;buffered=duration/2
        audioTracks=listOf("English · Atmos" to true,"French · Stereo" to false)
        subtitleOptions=listOf("Off" to true,"English" to false)
    }
    private fun actions(s:ControlsState)=ControlsActions(
        playPause={plays++},seekBy={seeks+=it},skip={},
        openMenu={which,_->s.menu=which;s.visible=false},options={emptyList()},next={},playEpisode={},touched={},
        pickAudio={pickedAudio=it},pickSubtitle={pickedSubtitle=it},
    )
    private fun show(s:ControlsState,width:Int=960,height:Int=540,scale:Float=1f) {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,
                LocalDensity provides Density(1f,scale),LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(0) {Box(Modifier.size(width.dp,height.dp).background(Color(0xFF28434A)).testTag("scene")) {
                    AdvancedControls(s,actions(s))
                }}
            }
        }
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    @Test fun glassButtonsInvokeTransportOnceAndKeepACompactDock() {
        val s=state();show(s)
        compose.onNodeWithTag("player_play").assertIsFocused()
        compose.onRoot().performKeyInput {keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter)}
        assertEquals(1,plays)
        click("player_rewind");click("player_forward")
        assertEquals(listOf(-10_000L,30_000L),seeks)
        val play=compose.onNodeWithTag("player_play").fetchSemanticsNode().boundsInRoot
        assertTrue(play.height<=48f)
        compose.onNodeWithTag("player_glass").assertExists()
        save("ember-glass")
    }
    @Test fun cinemaReusesTransportAndHidesUnavailableActions() {
        val s=state(PlayerInterfaces.BASIC).apply {audioTracks=emptyList();subtitleOptions=emptyList();details=null}
        show(s)
        compose.onNodeWithTag("player_cinema").assertExists()
        compose.onNodeWithTag("player_audio").assertDoesNotExist()
        compose.onNodeWithTag("player_subtitles").assertDoesNotExist()
        compose.onNodeWithTag("player_episodes").assertDoesNotExist()
        click("player_forward");assertEquals(listOf(30_000L),seeks)
        save("ember-cinema")
    }
    @Test fun seekingPreviewsBeforeCommitAndDoesNotTogglePlayback() {
        val s=state();show(s)
        compose.onNodeWithTag("player_timeline").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)
            keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)
        }
        assertTrue(seeks.isEmpty());assertTrue(s.seekPreview>=0f)
        compose.onRoot().performKeyInput {keyDown(Key.DirectionCenter);keyUp(Key.DirectionCenter)}
        assertEquals(1,seeks.size);assertTrue(kotlin.math.abs(seeks.single()-20_000L)<=1L)
        assertEquals(-1f,s.seekPreview);assertEquals(0,plays)
    }
    @Test fun pickingAudioAppliesTheTrackAndRestoresItsButtonFocus() {
        val s=state();show(s)
        click("player_audio")
        compose.onNodeWithText("French · Stereo").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(1,pickedAudio);assertNull(s.sheet)
        compose.onNodeWithTag("player_audio").assertIsFocused()
    }
    @Test fun pickingSubtitlesFromCinemaRestoresItsButtonFocus() {
        val s=state(PlayerInterfaces.BASIC);show(s)
        click("player_subtitles")
        compose.onNodeWithText("English",substring=false).performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(1,pickedSubtitle)
        compose.onNodeWithTag("player_subtitles").assertIsFocused()
    }
    @Test fun closingAListWithoutChoosingDoesNotChangeTheTrack() {
        val s=state();show(s);click("player_audio")
        compose.runOnIdle {s.sheet=null}
        compose.onNodeWithTag("player_audio").assertIsFocused()
        assertEquals(-1,pickedAudio)
    }
    @Test fun returningFromSettingsRestoresSettingsInsteadOfPlay() {
        val s=state();show(s);click("player_settings")
        assertEquals("quick",s.menu)
        compose.runOnIdle {s.closeMenuSession();s.visible=true;s.shownAt++}
        compose.onNodeWithTag("player_settings").assertIsFocused()
    }
    @Test fun pausedPlaybackDoesNotAutomaticallyCoverTheMovieWithDetails() {
        val s=state();show(s)
        compose.onNodeWithText("An explorer",substring=true).assertDoesNotExist()
        click("player_info")
        compose.onNodeWithText("An explorer",substring=true).assertExists()
    }
    @Test fun unknownDurationDoesNotOfferSeekingAndHiddenOverlayHasNoButtons() {
        val s=state().apply {duration=0};show(s)
        compose.onNodeWithTag("player_forward").assertIsNotEnabled()
        compose.onNodeWithTag("player_rewind").assertIsNotEnabled()
        compose.runOnIdle {s.visible=false}
        compose.onNodeWithTag("player_play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ember").assertDoesNotExist()
    }
    @Test fun smallWindowsAndLargeTextKeepUtilitiesAndTransportSeparated() {
        val s=state().apply {title="A very long media title that must remain a single line in the control dock"}
        show(s,640,400,1.5f)
        val play=compose.onNodeWithTag("player_play").fetchSemanticsNode().boundsInRoot
        val audio=compose.onNodeWithTag("player_audio").fetchSemanticsNode().boundsInRoot
        assertTrue(play.bottom<=audio.top)
        val scene=compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
        for(tag in listOf("player_play","player_audio","player_settings")) {
            val bounds=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(scene.contains(bounds.topLeft));assertTrue(bounds.bottom<=scene.bottom)
        }
    }
    @Test fun audioMenuOpensAtTheCurrentTrackBeyondTheFirstScreen() {
        val s=state().apply {audioTracks=(0 until 40).map {"Track $it" to (it==35)}}
        show(s);click("player_audio")
        compose.onNodeWithText("Track 35").assertIsDisplayed().assertIsFocused()
        compose.onRoot().performKeyInput {keyDown(Key.DirectionLeft);keyUp(Key.DirectionLeft)}
        compose.onNodeWithTag("player_play").assertIsNotFocused()
    }
    @Test fun skipActionSitsAboveTheDockWithLargeText() {
        val s=state().apply {skipLabel="Skip intro"};show(s,640,400,1.5f)
        val skip=compose.onNodeWithText("Skip intro").fetchSemanticsNode().boundsInRoot
        val title=compose.onNodeWithText(s.title).fetchSemanticsNode().boundsInRoot
        assertTrue(skip.bottom<title.top)
    }

    @Test fun removingTracksWhileTheMenuIsOpenReturnsFocusToPlay() {
        val s=state();show(s);click("player_audio")
        compose.runOnIdle {s.audioTracks=emptyList();s.sheet=null}
        compose.onNodeWithTag("player_play").assertIsFocused()
        assertNull(s.returnControl)
    }

    @Test fun aTrackMenuHidesSkipActionsUntilItCloses() {
        val s=state().apply {skipLabel="Skip intro"};show(s)
        compose.onNodeWithText("Skip intro").assertExists()
        click("player_audio")
        compose.onNodeWithText("Skip intro").assertDoesNotExist()
        compose.runOnIdle {s.sheet=null}
        compose.onNodeWithText("Skip intro").assertExists()
        compose.onNodeWithTag("player_audio").assertIsFocused()
    }

    @Test fun endTimeAccountsForPlaybackSpeedAndUnknownDurations() {
        assertEquals(31_000L,playbackEndTime(1000,0,60_000,2f))
        assertEquals(playbackEndTime(1000,0,60_000,2f),playbackEndTime(11_000,20_000,60_000,2f))
        assertEquals(21_000L,playbackEndTime(1000,40_000,60_000,1f))
        assertNull(playbackEndTime(1000,0,0,1f));assertNull(playbackEndTime(1000,0,60_000,Float.NaN))
        assertEquals(Long.MAX_VALUE,playbackEndTime(Long.MAX_VALUE-100,0,60_000,1f))
    }
    private fun save(name:String) {
        val file=java.io.File("build/reports/ui/$name.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
}
