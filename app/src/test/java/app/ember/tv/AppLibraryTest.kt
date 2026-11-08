package app.ember.tv

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLibraryTest {
    @get:Rule val compose=createComposeRule()
    private fun app(i:Int,name:String="App $i")=AppEntry("app.$i",name,Intent(),BitmapDrawable(
        RuntimeEnvironment.getApplication().resources,
        Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.rgb(30+i*5%180,60+i*8%150,100+i*11%130))}))
    private var apps by mutableStateOf((1..24).map {app(it)})
    private var hidden by mutableStateOf(emptySet<String>())
    private var profile by mutableStateOf("owner")
    private var launched=""
    private var options=""
    private var refreshed=0
    private val navigation=FocusRequester()
    private fun click(tag:String)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun choose(label:String)=compose.onNodeWithContentDescription(label).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun field()=compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
    private fun type(query:String) {
        field().performSemanticsAction(SemanticsActions.RequestFocus)
        field().performKeyInput { pressKey(Key.DirectionCenter) }
        field().performTextReplacement(query)
    }
    private fun show(width:Int=960,height:Int=540,scale:Float=1f) {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,
                LocalDensity provides Density(1f,scale),LocalRenderOptions provides RenderOptions(device="low")) {
                EmberTheme(0) {Column(Modifier.size(width.dp,height.dp).background(Color(0xFF101012)).testTag("scene")) {
                    Chip("Apps tab",modifier=Modifier.focusRequester(navigation).testTag("navigation")) {}
                    AppLibraryCanvas(apps,hidden,profile,onLaunch={launched=it.pkg},onOptions={options=it.pkg},onRefresh={refreshed++},
                        modifier=Modifier.weight(1f))
                }}
            }
        }
    }
    @Test fun bannerGridUsesSixWholeColumnsAndShowsLabels() {
        show();compose.onNodeWithTag("app_library_count").assertTextEquals("24 apps")
        val first=compose.onNodeWithTag("app_library_app.1").fetchSemanticsNode().boundsInRoot
        val sixth=compose.onNodeWithTag("app_library_app.6").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top,sixth.top,.5f);assertTrue(sixth.left>first.right)
        compose.onNodeWithText("App 1").assertExists()
        val file=java.io.File("build/reports/ui/unified-app-library.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onNodeWithTag("scene").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun aTileLaunchesAndItsLongPressKeepsTheExistingAppOptions() {
        show();choose("App 1");assertEquals("app.1",launched)
        compose.onNodeWithContentDescription("App 1").performSemanticsAction(SemanticsActions.OnLongClick){it()}
        assertEquals("app.1",options)
    }
    @Test fun searchFiltersNamesAndPackagesAndClearRestoresTheFullLibrary() {
        show();type("APP.24")
        compose.onNodeWithTag("app_library_count").assertTextEquals("1 app")
        compose.onNodeWithTag("app_library_app.24").assertExists()
        click("app_library_clear")
        field().assertIsFocused();compose.onNodeWithTag("app_library_count").assertTextEquals("24 apps")
    }
    @Test fun emptySearchOffersClearWithoutChangingCustomOrder() {
        show();type("missing name")
        compose.onNodeWithText("No matching apps. Try another name.").assertExists()
        compose.onNodeWithText("Clear search").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("app_library_app.1").assertExists()
    }
    @Test fun sortingIsNonDestructiveAndClosingItRestoresItsButtonFocus() {
        apps=listOf(app(1,"Zulu"),app(2,"Alpha"),app(3,"Beta"));show()
        click("app_library_sort");choose("Name A–Z")
        compose.onNodeWithTag("app_library_sort").assertIsFocused()
        val a=compose.onNodeWithTag("app_library_app.2").fetchSemanticsNode().boundsInRoot
        val z=compose.onNodeWithTag("app_library_app.1").fetchSemanticsNode().boundsInRoot
        assertTrue(a.left<z.left)
        click("app_library_sort");choose("Custom order")
        assertTrue(compose.onNodeWithTag("app_library_app.1").fetchSemanticsNode().boundsInRoot.left<
            compose.onNodeWithTag("app_library_app.2").fetchSemanticsNode().boundsInRoot.left)
        assertEquals(listOf("Zulu","Alpha","Beta"),apps.map {it.label})
    }
    @Test fun hiddenAppsAreOptInAndTheMoreButtonRetainsFocus() {
        hidden=setOf("app.1");show()
        compose.onNodeWithTag("app_library_app.1").assertDoesNotExist()
        click("app_library_more");choose("Show 1 hidden app")
        compose.onNodeWithTag("app_library_app.1").assertExists()
        compose.onNodeWithTag("app_library_more").assertIsFocused()
        click("app_library_more");choose("Hide hidden apps")
        compose.onNodeWithTag("app_library_app.1").assertDoesNotExist()
    }
    @Test fun showingHiddenAppsNeverExposesAProfileForbiddenPackage() {
        val permitted=AppAccess(apps,setOf("app.2","app.3"),emptySet(),setOf("app.1","app.2"))
        apps=permitted.page(true);hidden=setOf("app.1","app.2");show()
        click("app_library_more");choose("Show 1 hidden app")
        compose.onNodeWithTag("app_library_app.2").assertExists()
        compose.onNodeWithTag("app_library_app.1").assertDoesNotExist()
        compose.onNodeWithTag("app_library_count").assertTextEquals("2 apps")
    }
    @Test fun profilesDoNotInheritAnotherProfilesSearchOrHiddenView() {
        hidden=setOf("app.1");show();type("App 24")
        compose.runOnIdle {profile="child";apps=listOf(app(2))}
        compose.onNodeWithTag("app_library_count").assertTextEquals("1 app")
        compose.onNodeWithTag("app_library_app.2").assertExists()
        compose.onNodeWithTag("app_library_clear").assertDoesNotExist()
    }
    @Test fun largeTextStillKeepsSortAndMoreOnTheSameRowAsSearch() {
        show(640,400,1.5f)
        val field=compose.onNodeWithTag("app_library_search").fetchSemanticsNode().boundsInRoot
        val sort=compose.onNodeWithTag("app_library_sort").fetchSemanticsNode().boundsInRoot
        val more=compose.onNodeWithTag("app_library_more").fetchSemanticsNode().boundsInRoot
        assertTrue(field.right<=sort.left);assertTrue(sort.right<=more.left)
        assertTrue(more.right<=640);compose.onNodeWithText("Custom order").assertExists()
    }
    @Test fun openingAppsByHoverDoesNotMoveFocusOutOfNavigation() {
        show();compose.runOnIdle {navigation.requestFocus()}
        compose.runOnIdle {apps=apps+app(25)}
        compose.onNodeWithTag("navigation").assertIsFocused()
    }
    @Test fun emptyPermittedLibraryKeepsARefreshAction() {
        apps=emptyList();show()
        compose.onNodeWithText("No apps available for this profile.").assertExists()
        click("app_library_more");choose("Refresh apps");assertEquals(1,refreshed)
    }
    @Test fun presentationKeepsCustomOrderAndDeduplicatesPackagesWithoutEditingInput() {
        val input=listOf(app(1,"Zulu"),app(2,"alpha"),app(1,"Duplicate"))
        assertEquals(listOf("app.1","app.2"),AppLibraryPresentation.items(input,"",AppLibrarySort.CUSTOM).map {it.pkg})
        assertEquals(listOf("app.2","app.1"),AppLibraryPresentation.items(input,"",AppLibrarySort.NAME).map {it.pkg})
        assertEquals(3,input.size)
    }
    @Test fun aScrollRevealsAppsOutsideTheFirstScreen() {
        show();compose.onNodeWithTag("app_library_grid").performScrollToIndex(23)
        compose.onNodeWithTag("app_library_app.24").assertExists()
    }
}
