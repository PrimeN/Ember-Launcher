package app.ember.tv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.data.Meta
import app.ember.tv.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Focus stays put when the focused item leaves (#130); removals ask first (#131). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusKeeperTest {
    @get:Rule val compose=createComposeRule()
    private val presence=FocusPresence()
    private var items by mutableStateOf((1..6).map {Meta("tt$it","movie","Title $it")})
    private fun card(n:Int)=compose.onNode(hasContentDescription("Title $n",substring=true) and hasClickAction())
    private fun scene(content:@Composable ColumnScope.()->Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalNight provides true,LocalReduceMotion provides true,LocalRenderOptions provides RenderOptions(device="low"),
                LocalFocusPresence provides presence) {
                EmberTheme(0) {Column(Modifier.size(960.dp,540.dp).background(Color(0xFF101012)).onFocusChanged {presence.any=it.hasFocus}) {
                    Chip("Elsewhere",modifier=Modifier.testTag("elsewhere")) {}
                    content()
                }}
            }
        }
    }

    @Test fun aGridHandsFocusToTheTitleThatTakesTheRemovedOnesPlace() {
        scene {TitleGrid(items,{false},{},Modifier.fillMaxWidth().weight(1f))}
        card(3).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {items=items.filterNot {it.id=="tt3"}}
        card(4).assertIsFocused()
        // The last one: focus moves back to the new last title.
        card(6).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {items=items.filterNot {it.id=="tt6"}}
        card(5).assertIsFocused()
    }

    @Test fun aHomeRowKeepsFocusWhenContinueWatchingLosesACard() {
        scene {MediaRow(items,wide=false,isSaved={false}) {}}
        card(2).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {items=items.filterNot {it.id=="tt2"}}
        card(3).assertIsFocused()
    }

    @Test fun leavingTheRowOnPurposeIsNeverUndone() {
        scene {TitleRow(items,{false}) {}}
        card(2).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("elsewhere").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {items=items.filterNot {it.id=="tt2"}}
        compose.onNodeWithTag("elsewhere").assertIsFocused()
    }

    @Test fun aSettingsSectionTakesFocusBackWhenItsButtonDisappears() {
        var shown by mutableStateOf(true)
        scene {Section("Addons") {
            Chip("Add addon",modifier=Modifier.testTag("first")) {}
            if(shown)Chip("Clear recent searches",modifier=Modifier.testTag("clear")) {shown=false}
        }}
        compose.onNodeWithTag("clear").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("clear").performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("first").assertIsFocused()
    }

    @Test fun aSectionDoesNotPullFocusBackFromSomewhereElse() {
        var shown by mutableStateOf(true)
        scene {Section("Addons") {
            Chip("Add addon",modifier=Modifier.testTag("first")) {}
            if(shown)Chip("Remove",modifier=Modifier.testTag("remove")) {}
        }}
        compose.onNodeWithTag("remove").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("elsewhere").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.runOnIdle {shown=false}
        compose.onNodeWithTag("elsewhere").assertIsFocused()
    }

    @Test fun removalPromptsStartOnCancelAndNameTheirAction() {
        var removed=0;var open by mutableStateOf(true)
        scene {if(open)ConfirmDestructive("Remove Torrentio?","Its catalogs stop appearing.",{open=false},"Remove") {removed++}}
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Remove").performSemanticsAction(SemanticsActions.OnClick){it()}
        assertEquals(1,removed);compose.onNodeWithText("Remove Torrentio?").assertDoesNotExist()
    }
}
