package app.ember.tv

import android.app.Application
import androidx.compose.foundation.layout.Row
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
        val items=LightNavigation.entries(listOf(NavTabs.DISCOVER,NavTabs.SETTINGS))
        compose.setContent {CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true) {EmberTheme(0,InterfaceStyles.LIGHT) {
            Row {items.forEach {item->LightAppTile(item,LightLayout.grid(960f),0,false,Modifier,
                {opened=LightNavigation.destination(item.pkg).orEmpty()},{menu=item.pkg})}}
        }}}
        compose.onNodeWithContentDescription("Discover").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(NavTabs.DISCOVER,opened)
        compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnLongClick){it()}
        assertEquals(LightNavigation.id(NavTabs.SETTINGS),menu);assertEquals(NavTabs.DISCOVER,opened)
    }
}
