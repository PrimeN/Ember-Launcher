package app.ember.tv

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class SearchCanvasTest {
    @get:Rule val compose=createComposeRule()
    private var state by mutableStateOf(SearchCanvasState(query="Ocean"))
    private var voices=0
    private var retries=0
    private var sources=0
    private var opened=""
    private val nav=FocusRequester()
    private fun movie(id:String,name:String="Ocean $id",type:String="movie")=Meta(id=id,name=name,type=type,releaseInfo="2025")
    private fun click(tag:String)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun key(key:Key)=compose.onRoot().performKeyInput {keyDown(key);keyUp(key)}
    private fun actions()=SearchCanvasActions(
        query={state=state.copy(query=it)},submit={state=state.copy(query=it)},voice={voices++},
        clearRecent={state=state.copy(recent=emptyList())},open={opened=it.id},launch={opened=it.pkg},
        appOptions={opened="options:${it.pkg}"},sources={sources++},retry={retries++},
    )
    private fun show(width:Int=960,height:Int=540,scale:Float=1f,autoFocus:Boolean=true) {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,
                LocalDensity provides Density(1f,scale),LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(0) {
                    Column(Modifier.size(width.dp,height.dp).background(Color(0xFF101012)).testTag("scene")) {
                        Chip("Search tab",modifier=Modifier.focusRequester(nav).testTag("navigation")) {}
                        SearchCanvas(state,actions(),Modifier.weight(1f),autoFocus=autoFocus,navigationUp=nav)
                    }
                }
            }
        }
    }
    @Test fun resultsUseWholeColumnsAndKeepMoreThanOneRowAvailable() {
        state=state.copy(rows=listOf(CatalogRow("Catalog A","Movies",(1..18).map {movie("tt$it")})))
        show()
        compose.onNodeWithTag("search_count").assertTextEquals("18 results")
        val first=compose.onNodeWithTag("search_result_catalog:Catalog A:Movies:0:${movie("tt1").mediaKey()}").fetchSemanticsNode().boundsInRoot
        val sixth=compose.onNodeWithTag("search_result_catalog:Catalog A:Movies:0:${movie("tt6").mediaKey()}").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top,sixth.top,.5f);assertTrue(sixth.left>first.right)
        compose.onNodeWithTag("search_grid").performScrollToIndex(12)
        compose.onNodeWithContentDescription("Ocean tt13",substring=true).assertExists()
        save()
    }
    @Test fun movieFilterAndWatchlistOnlySelectionKeepTheRightResults() {
        state=state.copy(rows=listOf(CatalogRow("Catalog A","Mixed",listOf(movie("tt1"),movie("tt2",type="series")))),
            saved=listOf(movie("tt3")))
        show();click("search_filter_MOVIES")
        compose.onNodeWithTag("search_count").assertTextEquals("2 results")
        click("search_filters")
        compose.onNodeWithText("Watchlist").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("search_count").assertTextEquals("1 result")
        compose.onNodeWithTag("search_filters").assertIsFocused()
        compose.onNodeWithContentDescription("Ocean tt3",substring=true).assertExists()
        compose.onNodeWithContentDescription("Ocean tt1",substring=true).assertDoesNotExist()
    }
    @Test fun selectingAResultOpensItsOriginalMetadata() {
        state=state.copy(rows=listOf(CatalogRow("A","Movies",listOf(movie("tt1")))))
        show()
        compose.onNodeWithContentDescription("Ocean tt1",substring=true).performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals("tt1",opened)
    }
    @Test fun nightSearchLabelsRemainReadableOutsideOfFocusableSurfaces() {
        state=state.copy(rows=listOf(CatalogRow("A","Movies",listOf(movie("tt1")))))
        show()
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Search results").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertTrue(layouts.single().layoutInput.style.color.luminance()>.5f)
    }
    @Test fun clearReturnsToTheTextFieldAndVoiceStillWorks() {
        show();click("search_voice");assertEquals(1,voices)
        click("search_clear")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assertIsFocused();assertEquals("",state.query)
        compose.onNodeWithTag("search_clear").assertDoesNotExist()
    }
    @Test fun textFieldAndVoiceCanReturnUpToTheSearchTab() {
        show();key(Key.DirectionUp)
        compose.onNodeWithTag("navigation").assertIsFocused()
        compose.onNodeWithTag("search_voice").performSemanticsAction(SemanticsActions.RequestFocus)
        key(Key.DirectionUp)
        compose.onNodeWithTag("navigation").assertIsFocused()
    }
    @Test fun openingByNavigationHoverDoesNotStealNavigationFocus() {
        show(autoFocus=false)
        compose.runOnIdle {nav.requestFocus()}
        compose.runOnIdle {state=state.copy(searching=true)}
        compose.onNodeWithTag("navigation").assertIsFocused()
    }
    @Test fun partialFailureIsQuietWhenAnyUsefulResultExists() {
        state=state.copy(unavailable=true,note="Some sources failed",rows=listOf(CatalogRow("A","Movies",listOf(movie("tt1")))))
        show()
        compose.onNodeWithText("Some sources failed").assertDoesNotExist()
        compose.onNodeWithText("Source settings").assertDoesNotExist()
    }
    @Test fun noAvailableResultsOffersRecoveryAndRetry() {
        state=state.copy(unavailable=true,note="No sources available")
        show()
        compose.onNodeWithText("Source settings").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithText("Retry search").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(1,sources);assertEquals(1,retries)
    }
    @Test fun duplicateCombiningIsAvailableBehindFiltersAndRetainsSourceLabels() {
        val item=movie("tt1")
        state=state.copy(rows=listOf(CatalogRow("A","Movies",listOf(item)),CatalogRow("B","Movies",listOf(item))))
        show();compose.onNodeWithTag("search_count").assertTextEquals("2 results")
        click("search_filters")
        compose.onNodeWithText("Combine duplicates").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("search_count").assertTextEquals("1 result")
        compose.onNodeWithText("2025 · A · B").assertExists()
    }
    @Test fun emptyAppsFilterDoesNotOfferUnrelatedSourceSettings() {
        state=state.copy(unavailable=true,note="No sources available")
        show();click("search_filter_APPS")
        compose.onNodeWithText("Source settings").assertDoesNotExist()
        compose.onNodeWithText("Show all results").assertExists()
    }
    @Test fun hiddenLocalShortcutsAreNotCountedAsMovieMatches() {
        state=state.copy(rows=listOf(CatalogRow("A","Movies",listOf(movie("tt1")))),
            shortcuts=listOf(SearchShortcut("display","Display","Settings") {opened="setting"}))
        show();compose.onNodeWithTag("search_count").assertTextEquals("2 results")
        click("search_filter_MOVIES")
        compose.onNodeWithTag("search_count").assertTextEquals("1 result")
        compose.onNodeWithText("Display").assertDoesNotExist()
    }
    @Test fun shortQueryLocalAppLaunchAndLongPressRemainAvailable() {
        state=state.copy(query="o",apps=listOf(AppEntry("app.ocean","Ocean Player",Intent())))
        show()
        compose.onNodeWithTag("search_count").assertTextEquals("1 result")
        val app=hasAnyAncestor(hasTestTag("search_grid")) and hasClickAction()
        compose.onNode(app).performSemanticsAction(SemanticsActions.OnLongClick){it()}
        assertEquals("options:app.ocean",opened)
        compose.onNode(app).performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals("app.ocean",opened)
    }
    @Test fun largeTextKeepsSearchControlsInsideTheWindow() {
        state=state.copy(rows=listOf(CatalogRow("A","Movies",listOf(movie("tt1")))))
        show(640,400,1.5f)
        val root=compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
        val field=compose.onNodeWithTag("search_field").fetchSemanticsNode().boundsInRoot
        val voice=compose.onNodeWithTag("search_voice").fetchSemanticsNode().boundsInRoot
        val filters=compose.onNodeWithTag("search_filters").fetchSemanticsNode().boundsInRoot
        assertTrue(field.right<=voice.left);assertTrue(voice.right<=root.right)
        assertTrue(field.bottom<=filters.top);assertTrue(filters.right<=root.right)
        click("search_filters");compose.onNodeWithText("Shows").assertExists()
    }
    @Test fun aNewProfileDoesNotInheritThePreviousSearchFilter() {
        state=state.copy(rows=listOf(CatalogRow("A","Mixed",listOf(movie("tt1"),movie("tt2",type="series")))))
        show();click("search_filter_MOVIES")
        compose.runOnIdle {state=state.copy(profileId="other")}
        compose.onNodeWithTag("search_filter_ALL").assertIsSelected()
        compose.onNodeWithTag("search_count").assertTextEquals("2 results")
    }
    private fun save() {
        val file=java.io.File("build/reports/ui/search-canvas.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun field()=compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
    @Test fun aRecentSearchHandsFocusToTheFieldAsItsChipDisappears() {
        state=SearchCanvasState(recent=listOf("Ocean"));show(autoFocus=false)
        val chip=compose.onNode(hasText("Ocean") and hasClickAction() and hasAnyAncestor(hasTestTag("search_grid")))
        chip.performSemanticsAction(SemanticsActions.RequestFocus)
        chip.performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals("Ocean",state.query)
        field().assertIsFocused()
    }
    @Test fun showAllAndRetryKeepFocusOnControlsThatStay() {
        state=state.copy(rows=listOf(CatalogRow("A","Shows",listOf(movie("tt1",type="series")))))
        show();click("search_filter_MOVIES")
        val all=compose.onNodeWithText("Show all results")
        all.performSemanticsAction(SemanticsActions.RequestFocus);all.performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("search_filters").assertIsFocused()
        compose.onNodeWithTag("search_count").assertTextEquals("1 result")
        compose.runOnIdle {state=SearchCanvasState(query="Ocean",unavailable=true,note="No sources available")}
        val retry=compose.onNodeWithText("Retry search")
        retry.performSemanticsAction(SemanticsActions.RequestFocus);retry.performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(1,retries);field().assertIsFocused()
    }
}
