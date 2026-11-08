package app.ember.tv

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
class NavbarUiTest {
    @get:Rule val compose=createComposeRule()
    private val navigation=NavigationFocus()
    private var style by mutableStateOf(NavbarStyles.COLLAPSED)
    private var launcher by mutableStateOf(InterfaceStyles.MODERN)
    private var current by mutableStateOf(NavTabs.HOME)
    private var tabs by mutableStateOf(listOf(NavTabs.HOME,NavTabs.DISCOVER,NavTabs.LIVE,NavTabs.APPS,NavTabs.LIBRARY,NavTabs.SEARCH))
    private var actions=mutableListOf<String>()
    private var profiles=0
    private lateinit var back:OnBackPressedDispatcher
    private fun key(key:Key)=compose.onRoot().performKeyInput {pressKey(key)}
    private fun click(tag:String)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick){it()}
    private fun up() {compose.runOnIdle {navigation.content(current).requestFocus()};key(Key.DirectionUp)}
    private fun snapshot(name:String) {
        val file=java.io.File("build/reports/ui/navbar-$name.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun show(width:Int=960,scale:Float=1f,night:Boolean=true,page:Color=Color(0xFF101012)) {
        compose.setContent {
            back=LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            CompositionLocalProvider(LocalNavigationFocus provides navigation,LocalNight provides night,LocalReduceMotion provides true,
                LocalRenderOptions provides RenderOptions(device="low"),LocalDensity provides Density(1f,scale)) {
                EmberTheme(0,launcher) {
                    LauncherNavbarFrame(style,launcher,"top",tabs,current,"18°",Profile(id="main",name="Alex"),
                        onTab={if(it in setOf(NavTabs.QUICK,NavTabs.SCREENSAVER))actions.add(it) else current=it},onProfile={profiles++},
                        modifier=Modifier.size(width.dp,540.dp).background(page)) {side->
                        Box(Modifier.fillMaxSize().sceneContent(current,navigationTabForPage(current,tabs),side)) {
                            Chip("Page action",modifier=Modifier.testTag("page_action")) {}
                        }
                    }
                }
            }
        }
        compose.runOnIdle {navigation.content(current).requestFocus()}
    }
    @Test fun collapsedShowsCurrentPageAndExpandsOnUpWithoutRequiringAnExtraClick() {
        current=NavTabs.LIBRARY;show()
        compose.onNodeWithTag("navbar_expanded").assertDoesNotExist()
        compose.onNodeWithTag("navbar_handle").assertContentDescriptionEquals("Library")
        up();compose.onNodeWithTag("navbar_tab_library").assertIsFocused()
        assertEquals(NavTabs.LIBRARY,current)
    }
    @Test fun expandedMenuNeverMovesThePageAndBackRestoresContent() {
        show();val before=compose.onNodeWithTag("navbar_content").fetchSemanticsNode().boundsInRoot
        up();val after=compose.onNodeWithTag("navbar_content").fetchSemanticsNode().boundsInRoot
        snapshot("collapsed-open")
        assertEquals(before,after)
        compose.runOnIdle {back.onBackPressed()}
        compose.onNodeWithTag("navbar_expanded").assertDoesNotExist()
        compose.onNodeWithTag("page_action").assertIsFocused()
    }
    @Test fun rightLeavesExpandedNavigationForTheCurrentPage() {
        show();up();key(Key.DirectionRight)
        compose.onNodeWithTag("navbar_expanded").assertDoesNotExist()
        compose.onNodeWithTag("page_action").assertIsFocused()
    }
    @Test fun downActivatesTheNextTabAndOkReturnsToItsContent() {
        show();up();key(Key.DirectionDown)
        assertEquals(NavTabs.DISCOVER,current)
        compose.onNodeWithTag("navbar_tab_discover").assertIsFocused()
        click("navbar_tab_discover")
        compose.onNodeWithTag("page_action").assertIsFocused()
        assertEquals(NavTabs.DISCOVER,current)
    }
    @Test fun cinemaBarUsesTheRelevantTabAndBothHorizontalPresses() {
        style=NavbarStyles.CINEMA;launcher=InterfaceStyles.EMBER_X;show();up()
        assertEquals(NavTabs.HOME,navigation.focusedTab)
        val text=compose.onNodeWithTag("navbar_label_library",useUnmergedTree=true)
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertTrue(text.fetchSemanticsNode().boundsInRoot.width>=layouts.single().size.width-.5f)
        snapshot("cinema-bar")
        key(Key.DirectionRight);assertEquals(NavTabs.DISCOVER,current)
        key(Key.DirectionRight);assertEquals(NavTabs.LIVE,current)
        key(Key.DirectionDown);compose.onNodeWithTag("page_action").assertIsFocused()
    }
    @Test fun currentEmberAndCinemaBarsWorkUnderTheOtherHomeInterface() {
        style=NavbarStyles.EMBER;launcher=InterfaceStyles.EMBER_X;show();up()
        assertEquals(NavTabs.HOME,navigation.focusedTab)
        key(Key.DirectionRight);assertEquals(NavTabs.DISCOVER,current)
        compose.runOnIdle {style=NavbarStyles.CINEMA;launcher=InterfaceStyles.MODERN;current=NavTabs.HOME}
        up();assertEquals(NavTabs.HOME,navigation.focusedTab)
        key(Key.DirectionRight);assertEquals(NavTabs.DISCOVER,current)
    }
    @Test fun searchCanLeaveForHomeAndHiddenTabsAreNotAdded() {
        tabs=listOf(NavTabs.SEARCH,NavTabs.HOME,NavTabs.APPS);current=NavTabs.SEARCH;show();up()
        compose.onNodeWithTag("navbar_tab_live").assertDoesNotExist()
        key(Key.DirectionDown);assertEquals(NavTabs.HOME,current)
        key(Key.DirectionRight);compose.onNodeWithTag("page_action").assertIsFocused()
    }
    @Test fun lightNeverDisplaysAnyOfTheAlternativeBars() {
        launcher=InterfaceStyles.LIGHT;show()
        compose.onNodeWithTag("navbar_handle").assertDoesNotExist()
        compose.onNodeWithTag("navbar_tab_home").assertDoesNotExist()
        compose.onNodeWithContentDescription("Profile: Alex. Open profile menu").assertDoesNotExist()
        compose.onNodeWithTag("page_action").assertIsFocused()
    }
    @Test fun collapsedActionsKeepTheirExistingRouting() {
        show();up()
        compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(listOf(NavTabs.QUICK),actions);assertEquals(NavTabs.HOME,current)
        up();compose.onNodeWithContentDescription("Screensaver").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(listOf(NavTabs.QUICK,NavTabs.SCREENSAVER),actions)
    }
    @Test fun largeTextStillFitsTheExpandablePanelAndCanReturnToContent() {
        show(640,1.5f);up()
        val menu=compose.onNodeWithTag("navbar_expanded").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.right<=640);assertTrue(menu.bottom<=540)
        compose.runOnIdle {back.onBackPressed()};compose.onNodeWithTag("page_action").assertIsFocused()
    }
    @After fun resetDay() {Ui.day=false}
    @Test fun focusPlacedByTheAppLandsOnTheHandleWithoutOpeningTheMenu() {
        // Start-up and TV wake focus the current page's tab (#127).
        current=NavTabs.LIBRARY;show()
        compose.runOnIdle {navigation.tab(NavTabs.LIBRARY).requestFocus()}
        compose.onNodeWithTag("navbar_handle").assertIsFocused()
        compose.onNodeWithTag("navbar_expanded").assertDoesNotExist()
        key(Key.DirectionDown);compose.onNodeWithTag("page_action").assertIsFocused()
        compose.runOnIdle {navigation.tab(NavTabs.LIBRARY).requestFocus()}
        click("navbar_handle");compose.onNodeWithTag("navbar_tab_library").assertIsFocused()
    }
    @Test fun dayPagesKeepTheCollapsedStatusAndCinemaTabsReadable() {
        Ui.day=true;current=NavTabs.DISCOVER;show(night=false,page=Color(0xFFF1F3F4))
        for(chosen in listOf(NavbarStyles.COLLAPSED,NavbarStyles.CINEMA)) {
            compose.runOnIdle {style=chosen}
            val image=compose.onRoot().captureToImage().asAndroidBitmap()
            // The clock and logo at the top right must be drawn in a dark colour on the light page.
            var darkest=1f
            for(x in 780 until 940 step 2)for(y in 8 until 64 step 2) {
                val c=image.getPixel(x,y)
                darkest=minOf(darkest,(.2126f*android.graphics.Color.red(c)+.7152f*android.graphics.Color.green(c)+.0722f*android.graphics.Color.blue(c))/255f)
            }
            assertTrue("$chosen status is unreadable on a Day page ($darkest)",darkest<.35f)
            snapshot("day-$chosen")
        }
    }
}
