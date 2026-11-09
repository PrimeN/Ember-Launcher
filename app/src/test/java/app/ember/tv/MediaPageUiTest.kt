package app.ember.tv

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import app.ember.tv.data.*
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
class MediaPageUiTest {
    @get:Rule val compose=createComposeRule()
    private val episodes=(1..12).map {Episode("e$it",1,it,"Episode $it",null,null,progress=if(it==2).5f else null)}
    private val stream=StreamOption("Provider","Source","Episode file", "https://example.invalid/video",null,null,
        "4K","HDR10","HEVC","5.1","WEB-DL","",8.4,null,true)
    private var layout by mutableStateOf(MediaPageLayouts.MODERN)
    private var launcher by mutableStateOf(InterfaceStyles.MODERN)
    private var automatic by mutableStateOf(true)
    private var playedBest=0
    private var details by mutableStateOf(DetailsUi(Meta(id="show",type="series",name="The Last Light",description="Explorers follow a signal through the mountains."),
        episodes=episodes,season=1,episode=episodes[0],streams=listOf(stream),addonCredits=Credits(emptyList(),"trailer")))
    private var played=0;private var trailered=0;private var saved=0;private var watched=0
    private lateinit var back:OnBackPressedDispatcher
    private fun action(label:String,substring:Boolean=false)=compose.onNodeWithContentDescription(label,substring=substring)
    private fun click(label:String,substring:Boolean=false)=action(label,substring).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun show(scale:Float=1f,width:Int=960) {
        compose.setContent {
            back=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalDensity provides Density(1f,scale),LocalReduceMotion provides true,
                LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(0,launcher) {MediaPageLayoutScope(layout) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        Box(Modifier.size(width.dp,540.dp).background(Color(0xFF101012)).testTag("scene")) {
                            DetailsScreen(d=details,listState=rememberLazyListState(),saved=false,isContinue=false,
                                onToggleSaved={saved++},onRemoveContinue={},onSeason={details=details.copy(season=it)},
                                onEpisode={details=details.copy(episode=it)},onMoreEpisodes={},onPlay={played++},onPlayWith={},
                                onPlayTrailer={trailered++},playButton=automatic,best=details.streams.firstOrNull(),
                                playbackLabel="Resume 24:18",onMarkWatched={watched++},onPlayBest={playedBest++})
                        }
                    }
                }}
            }
        }
    }
    @Test fun modernLayoutDoesNotUseTheRightPanelEvenUnderEmberXHome() {
        launcher=InterfaceStyles.EMBER_X;show()
        action("Episodes and playback sources").assertDoesNotExist()
        click("Resume 24:18",true);assertEquals(1,played)
        click("Play trailer");assertEquals(1,trailered)
        click("Add to watchlist");assertEquals(1,saved)
        click("Mark as watched");assertEquals(1,watched)
    }
    @Test fun modernKeepsEqualHeightActionsAndSourceChoiceUnderLightHome() {
        launcher=InterfaceStyles.LIGHT;layout=MediaPageLayouts.MODERN;automatic=false;show()
        val play=action("Resume 24:18",true).fetchSemanticsNode().boundsInRoot
        val trailer=action("Play trailer").fetchSemanticsNode().boundsInRoot
        assertEquals(play.height,trailer.height,.5f)
        action("Episodes and playback sources").assertDoesNotExist()
        click("Resume 24:18",true)
        compose.onNodeWithText("Sources · 1 found").assertIsFocused()
    }
    @Test fun emberXEpisodeSourceAndBackRestoreTheSelectedEpisode() {
        layout=MediaPageLayouts.EMBER_X;show()
        action("Episodes and playback sources").assertExists()
        click("Season 1, episode 2, Episode 2")
        compose.onNodeWithText("Sources · 1 found").assertExists()
        // Play best plays and leaves the Play button setting alone (#140).
        click("Play best");assertEquals(1,playedBest);assertTrue(automatic)
        compose.runOnIdle {back.onBackPressed()}
        action("Season 1, episode 2, Episode 2").assertIsFocused()
        assertEquals("e2",details.episode?.id)
    }
    @Test fun nextEpisodeUpdatesSourceContextWithoutStartingPlayback() {
        layout=MediaPageLayouts.EMBER_X;show();click("Season 1, episode 2, Episode 2")
        click("Next");assertEquals("e3",details.episode?.id);assertEquals(0,played)
        click("Previous");assertEquals("e2",details.episode?.id)
        compose.runOnIdle {back.onBackPressed()}
        action("Season 1, episode 2, Episode 2").assertIsFocused()
    }
    @Test fun noSourcesKeepPlayDisabledAndWatchlistAccessible() {
        layout=MediaPageLayouts.MODERN;details=details.copy(streams=emptyList());show()
        action("No source available").assertIsNotEnabled()
        action("Add to watchlist").assertIsFocused()
        click("Add to watchlist");assertEquals(1,saved)
    }
    @Test fun returningAfterNextEpisodeDoesNotLeaveItHiddenByAnOldSearch() {
        layout=MediaPageLayouts.EMBER_X;show()
        val field=compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performKeyInput {pressKey(Key.DirectionCenter)}
        field.performTextReplacement("Episode 2")
        click("Season 1, episode 2, Episode 2");click("Next")
        compose.runOnIdle {back.onBackPressed()}
        action("Season 1, episode 3, Episode 3").assertIsFocused()
    }
    @Test fun largeTextFallsBackToAReadableSingleColumn() {
        layout=MediaPageLayouts.EMBER_X;show(1.5f,640)
        action("Episodes and playback sources").assertDoesNotExist()
        action("Resume 24:18",true).assertExists()
    }
    @Test fun movieSourcesAreImmediatelyAvailableInEmberX() {
        layout=MediaPageLayouts.EMBER_X;details=details.copy(meta=details.meta.copy(type="movie"),episodes=emptyList(),season=null,episode=null)
        show();compose.onNodeWithText("Sources · 1 found").assertExists()
        action("Episodes").assertDoesNotExist()
        val file=java.io.File("build/reports/ui/media-page-ember-x.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun previousAndNextKeepFocusUntilTheyRunOut() {
        layout=MediaPageLayouts.EMBER_X;details=details.copy(episode=episodes[10]);show()
        click("Sources")
        val next=compose.onNodeWithTag("episode_next")
        next.performSemanticsAction(SemanticsActions.RequestFocus)
        next.performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals("e12",details.episode?.id)
        compose.onNodeWithTag("episode_next").assertDoesNotExist()
        compose.onNodeWithTag("episode_previous").assertIsFocused()
        compose.onNodeWithTag("episode_previous").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals("e11",details.episode?.id)
        compose.onNodeWithTag("episode_previous").assertIsFocused()
        assertEquals(0,played)
    }
}
