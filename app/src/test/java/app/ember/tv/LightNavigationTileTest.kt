package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class LightNavigationTileTest {
    @get:Rule val compose=createComposeRule()
    @Test fun navigationUsesTheAppTilesClickAndLongPressContract() {
        var opened="";var menu=""
        val items=LightNavigation.entries(listOf(NavTabs.DISCOVER,NavTabs.LIVE))
        compose.setContent {CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true) {EmberTheme(0,InterfaceStyles.LIGHT) {
            Row {items.forEach {item->LightAppTile(item,LightLayout.grid(960f),0,false,Modifier,
                {opened=LightNavigation.destination(item.pkg).orEmpty()},{menu=item.pkg})}}
        }}}
        compose.onNodeWithContentDescription("Discover").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(NavTabs.DISCOVER,opened)
        compose.onNodeWithContentDescription(items[1].label).performSemanticsAction(SemanticsActions.OnLongClick){it()}
        assertEquals(LightNavigation.id(NavTabs.LIVE),menu);assertEquals(NavTabs.DISCOVER,opened)
    }

    @Test fun pageTilesCarryTheirNameAndLiveItsBadge() {
        val items=LightNavigation.entries(listOf(NavTabs.DISCOVER,NavTabs.LIVE,NavTabs.LIBRARY,NavTabs.WATCHLIST))
        compose.setContent {CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true) {EmberTheme(0,InterfaceStyles.LIGHT) {
            Row(Modifier.background(androidx.compose.ui.graphics.Color(0xFF0E1423)).testTag("tiles")) {
                items.forEach {item->LightAppTile(item,LightLayout.grid(960f),0,false,Modifier,{},{})}
            }
        }}}
        // The tile reads as its page; the name and LIVE badge are drawn on it (see the snapshot).
        compose.onNodeWithTag("light_page_live").assertContentDescriptionEquals(items[1].label)
        compose.onNodeWithTag("light_page_discover").assertContentDescriptionEquals("Discover")
        // Each page has its own colours.
        assertEquals(4,listOf(NavTabs.DISCOVER,NavTabs.LIVE,NavTabs.LIBRARY,NavTabs.WATCHLIST).map {lightPageLook(it).from}.distinct().size)
        val image=compose.onNodeWithTag("tiles").captureToImage().asAndroidBitmap()
        val file=java.io.File("build/reports/ui/light-page-tiles.png").apply {parentFile!!.mkdirs()}
        file.outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun recommendationRowsReturnToTheCardLastFocused() {
        val first=androidx.compose.ui.focus.FocusRequester()
        val cards=(1..6).map {app.ember.tv.light.ShelfCard("c$it","Card $it","",null,null)}
        compose.setContent {CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true) {EmberTheme(0,InterfaceStyles.LIGHT) {
            androidx.compose.foundation.layout.Column {
                LightAppShelf(listOf("Continue watching" to cards),first) {}
                Chip("Dock app",modifier=Modifier.testTag("dock")) {}
            }
        }}}
        compose.onNode(hasText("Card 3",substring=true) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("dock").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {first.requestFocus()}
        compose.onNode(hasText("Card 3",substring=true) and hasClickAction()).assertIsFocused()
    }
}
