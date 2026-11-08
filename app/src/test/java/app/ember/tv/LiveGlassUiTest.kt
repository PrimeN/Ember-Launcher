package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.ember.tv.iptv.*
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
class LiveGlassUiTest {
    @get:Rule val compose=createComposeRule()
    private val clock=90_900_000L
    private var view by mutableStateOf(LiveViews.GUIDE)
    private var channels by mutableStateOf((1..32).map {Channel("a:$it","a","Channel $it","https://example.test/$it",group="News")})
    private var missingGuide=false
    private var plays=0
    private var options by mutableStateOf<Channel?>(null)
    private var selected by mutableStateOf<Channel?>(null)
    private var favourite by mutableStateOf(false)
    private var sourceRequests=0
    private var menuWindow:android.view.View?=null
    private var entryVersion by mutableIntStateOf(0)
    private var entryKey:String?=null
    private val sourceFocus=FocusRequester();private val searchFocus=FocusRequester()
    private val scopeFocus=FocusRequester();private val moreFocus=FocusRequester()
    private fun programmes(c:Channel)=if(missingGuide)emptyList() else listOf(
        Programme(clock-900_000,clock+900_000,"Now ${c.name}",description="Explore remarkable places and wildlife."),
        Programme(clock+900_000,clock+2_700_000,"Next ${c.name}"))
    private fun click(tag:String)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun key(key:Key)=compose.onRoot().performKeyInput {keyDown(key);keyUp(key)}
    private fun show(width:Int=960,height:Int=540,scale:Float=1f) {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,
                LocalDensity provides Density(1f,scale),LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(0) {LivePageTheme {
                    Column(Modifier.size(width.dp,height.dp).background(Color(0xFF101012)).padding(24.dp).testTag("scene")) {
                        EmberLogo(26.dp,Fg)
                        LiveViewToolbar("Home playlist","All channels",view==LiveViews.CANVAS,false,true,
                            sourceFocus,searchFocus,scopeFocus,moreFocus,onSource={sourceRequests++},
                            onView={view=it},onScope={},onSidebar={},onSearch={},onNow={},onMore={})
                        if(view==LiveViews.GUIDE) {
                            LiveProgrammePanel(selected ?: channels.firstOrNull(),(selected ?: channels.firstOrNull())?.let {programmes(it).firstOrNull()},
                                clock,"Home playlist","Guide unavailable",compact=true,favourite=favourite,
                                onWatch={plays++},onFavourite={favourite=!favourite})
                            Box(Modifier.weight(1f).fillMaxWidth().testTag("guide")) {
                                GuideGrid(channels,::programmes,emptySet(),clock,onPlay={plays++},onFavourite={options=it},
                                    showControls=false,showPanel=false,
                                    onSelection={c,_,_->if(c!=null)selected=c},onProgramme={c,_->options=c})
                            }
                        } else LiveOnNowCanvas(channels,{programmes(it).firstOrNull()},clock,mapOf("a" to "Home playlist"),
                            onFocus={selected=it},onOptions={options=it},onPlay={if(options==null)plays++},
                            modifier=Modifier.weight(1f),entryKey=entryKey,entryVersion=entryVersion,onEntryHandled={entryVersion=0})
                    }
                    options?.let {channel->CompactActionMenu(channel.name,listOf("Add favourite","Hide channel"),{options=null}) {first->
                        val window=LocalView.current
                        SideEffect {menuWindow=window}
                        MenuActionRow("Add favourite",Modifier.focusRequester(first)) {favourite=!favourite}
                        MenuActionRow("Hide channel") {channels=channels.filterNot {it.key==channel.key};options=null}
                    }}
                }}
            }
        }
    }
    @Test fun guideIsDefaultAndBothViewButtonsAreAlwaysAvailable() {
        show()
        compose.onNodeWithTag("live_view_guide").assertIsSelected()
        compose.onNodeWithTag("guide").assertExists()
        compose.onNodeWithTag("live_view_canvas").assertExists()
        compose.onNodeWithText("Guide").assertIsDisplayed()
        compose.onNodeWithText("On now").assertIsDisplayed()
        save("live-glass-guide")
    }
    @Test fun switchingViewsDoesNotTuneAndFocusStaysOnTheChosenControl() {
        show()
        compose.onNodeWithTag("live_view_canvas").performSemanticsAction(SemanticsActions.RequestFocus)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("live_canvas").assertExists()
        compose.onNodeWithTag("live_view_canvas").assertIsFocused().assertIsSelected()
        assertEquals(0,plays)
        click("live_view_guide")
        compose.onNodeWithTag("guide").assertExists();assertEquals(0,plays)
    }
    @Test fun canvasUsesFourWholeColumnsAndLoadsBeyondTheOldFeaturedLimit() {
        view=LiveViews.CANVAS;show()
        val a=compose.onNodeWithTag("live_card_a:1").fetchSemanticsNode().boundsInRoot
        val d=compose.onNodeWithTag("live_card_a:4").fetchSemanticsNode().boundsInRoot
        assertEquals(a.top,d.top,.5f);assertTrue(d.left>a.right)
        compose.onNodeWithTag("live_canvas_grid").performScrollToIndex(24)
        compose.onNodeWithTag("live_card_a:25").assertExists()
        save("live-on-now-canvas")
    }
    @Test fun canvasFocusDoesNotPlayAndOkPlaysOnce() {
        view=LiveViews.CANVAS;show()
        compose.onNodeWithContentDescription("Now Channel 1, Channel 1,",substring=true).performSemanticsAction(SemanticsActions.RequestFocus)
        assertEquals(0,plays);assertEquals(channels.first(),selected)
        key(Key.DirectionCenter);assertEquals(1,plays)
    }
    @Test fun longPressReleaseKeepsChannelActionsOpenWithoutTuning() {
        view=LiveViews.CANVAS;show()
        compose.onNodeWithContentDescription("Now Channel 1, Channel 1,",substring=true).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onRoot().performKeyInput {keyDown(Key.DirectionCenter)}
        compose.onNodeWithContentDescription("Now Channel 1, Channel 1,",substring=true)
            .performSemanticsAction(SemanticsActions.OnLongClick){it()}
        compose.runOnIdle {menuWindow!!.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_CENTER))}
        compose.onNodeWithContentDescription("Add favourite").assertExists()
        assertNotNull(options);assertEquals(0,plays);assertFalse(favourite)
    }
    @Test fun hiddenChannelIsRemovedFromTheVisibleCanvas() {
        view=LiveViews.CANVAS;show()
        compose.runOnIdle {channels=channels.drop(1)}
        compose.onNodeWithTag("live_card_a:1").assertDoesNotExist()
        compose.onNodeWithTag("live_card_a:2").assertExists()
    }
    @Test fun missingProgrammeGuideStillAllowsWatchingWithoutInventingRemainingTime() {
        view=LiveViews.CANVAS;missingGuide=true;show()
        compose.onNodeWithContentDescription("Channel 1, Home playlist").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(1,plays)
        compose.onAllNodesWithText("min left",substring=true).assertCountEquals(0)
    }
    @Test fun guideWatchAndFavouriteButtonsKeepTheirActions() {
        show();click("live_watch");assertEquals(1,plays)
        click("live_favourite");assertTrue(favourite)
        compose.onNodeWithTag("live_favourite").assertIsSelected()
    }
    @Test fun guideUpcomingProgrammeOpensDetailsInsteadOfTuningIt() {
        show()
        compose.onNodeWithContentDescription("Channel 1, Now Channel 1",substring=true).performSemanticsAction(SemanticsActions.RequestFocus)
        key(Key.DirectionRight);key(Key.DirectionCenter)
        assertEquals(0,plays);assertEquals(channels.first(),options)
    }
    @Test fun explicitCanvasEntryRevealsAChannelOutsideTheFirstScreen() {
        view=LiveViews.CANVAS;entryKey="a:29";entryVersion=1;show()
        compose.onNodeWithContentDescription("Now Channel 29",substring=true).assertIsFocused().assertIsDisplayed()
        assertEquals(0,entryVersion);assertEquals(0,plays)
    }
    @Test fun aMissingEntryChannelFallsBackToAVisibleChannel() {
        view=LiveViews.CANVAS;entryKey="removed";entryVersion=1;show()
        compose.onNodeWithContentDescription("Now Channel 1, Channel 1,",substring=true).assertIsFocused()
        assertEquals(0,plays)
    }
    @Test fun smallWindowsAndLargeTextKeepTheViewSwitcherUsable() {
        show(640,400,1.5f)
        compose.onNodeWithTag("live_view_canvas").performSemanticsAction(SemanticsActions.RequestFocus)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("live_canvas").assertExists()
        compose.onNodeWithTag("live_view_guide").assertExists()
        compose.onNodeWithTag("live_programme_panel").assertDoesNotExist()
        assertEquals(0,plays)
    }
    @Test fun playlistPickerRemainsAnExplicitAction() {
        show();click("live_source");assertEquals(1,sourceRequests)
        assertEquals(0,plays)
    }
    private fun save(name:String) {
        val file=java.io.File("build/reports/ui/$name.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun programmePanelKeepsOneHeightWithOrWithoutASummary() {
        var programme by mutableStateOf<Programme?>(Programme(clock-900_000,clock+900_000,"Described",
            description="Explore remarkable places and wildlife across two full lines of guide text, and then a little more."))
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(0) {LivePageTheme {Box(Modifier.size(960.dp,540.dp)) {
                    LiveProgrammePanel(channels.first(),programme,clock,"Home playlist","",compact=false,onWatch={},onFavourite={})
                }}}
            }
        }
        val described=compose.onNodeWithTag("live_programme_panel").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle {programme=Programme(clock-900_000,clock+900_000,"Plain")}
        val plain=compose.onNodeWithTag("live_programme_panel").fetchSemanticsNode().boundsInRoot.height
        assertEquals(described,plain,.5f)
    }
}
