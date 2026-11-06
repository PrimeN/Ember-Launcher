package app.ember.tv

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import app.ember.tv.ui.*
import app.ember.tv.data.NavTabs
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class StartupComposeFocusTest {
 @get:Rule val compose=createComposeRule()
 @Test fun firstRightFollowsHomeInsteadOfEnteringAtSearch() = checkFirst(Key.DirectionRight,"live")
 @Test fun delayedNavigationStillUsesFirstPress() = checkFirst(Key.DirectionRight,"live",delayed=true)
 @Test fun immediateFirstPressIsReplayedFromHome() = checkFirst(Key.DirectionRight,"live",immediate=true)
 @Test fun firstLeftGoesToSearch() = checkFirst(Key.DirectionLeft,"search")
 @Test fun firstDownEntersContent() = checkFirst(Key.DirectionDown,"content")
 @Test fun firstUpKeepsHome() = checkFirst(Key.DirectionUp,"home")
 @Test fun rtlFirstLeftFollowsVisualOrder() = checkFirst(Key.DirectionLeft,"live",rtl=true)
 private fun checkFirst(key:Key,expected:String,delayed:Boolean=false,immediate:Boolean=false,rtl:Boolean=false) {
  val nav=NavigationFocus()
  var selected by mutableStateOf("home")
  var attached by mutableStateOf(!delayed)
  var ready by mutableStateOf(true)
  if(immediate)compose.mainClock.autoAdvance=false
  compose.setContent {
   CompositionLocalProvider(LocalLayoutDirection provides if(rtl)LayoutDirection.Rtl else LayoutDirection.Ltr) {
   Column(Modifier.fillMaxSize().initialNavigationFocus(nav,"home",ready)) {
    if(attached)Row(Modifier.navigationFocusGroup{nav.tab(selected).requestFocus()}) {
     for(id in listOf("search","home","live","apps")) {
      Box(Modifier.size(60.dp).navigationTabTarget(nav,id)
       .focusProperties {
        val tabs=listOf("search","home","live","apps");val index=tabs.indexOf(id)
        left=tabs.getOrNull(index+choiceStep(false,rtl))?.let(nav::tab) ?: FocusRequester.Default
        right=tabs.getOrNull(index+choiceStep(true,rtl))?.let(nav::tab) ?: FocusRequester.Default
       }.openNavigationTab(id,selected==id){selected=id}.testTag(id).focusable())
     }
    }
    Box(Modifier.size(60.dp).testTag("content").focusable())
   }
   }
  }
  if(delayed){compose.mainClock.advanceTimeBy(500);compose.runOnIdle{attached=true}}
  compose.onRoot().performKeyInput {keyDown(key);keyUp(key)}
  if(immediate){compose.mainClock.advanceTimeBy(500);compose.mainClock.autoAdvance=true}
  compose.onNodeWithTag(expected).assertIsFocused()
  if(expected!="content")assertEquals(expected,selected)
  compose.runOnIdle{ready=false};compose.runOnIdle{ready=true}
  compose.onNodeWithTag(expected).assertIsFocused()
 }
 @Test fun earlyDirectionWaitsForInteractiveWindowWithoutBeingLost() {
  val nav=NavigationFocus();var ready by mutableStateOf(false)
  compose.setContent {
   Row(Modifier.fillMaxSize().initialNavigationFocus(nav,"home",ready)) {
    for(id in listOf("search","home","live"))
     Box(Modifier.size(60.dp).navigationTabTarget(nav,id).testTag(id).focusable())
   }
  }
  compose.onRoot().performKeyInput{keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)}
  compose.runOnIdle{ready=true}
  compose.onNodeWithTag("live").assertIsFocused()
 }
 @Test fun unavailableNavigationDoesNotCaptureLightOrSettingsInput() {
  val nav=NavigationFocus()
  compose.setContent {
   Row(Modifier.fillMaxSize().initialNavigationFocus(nav,"home",false,available=false)) {
    Box(Modifier.size(60.dp).navigationTabTarget(nav,"home").testTag("home").focusable())
    Box(Modifier.size(60.dp).navigationTabTarget(nav,"live").testTag("live").focusable())
   }
  }
  compose.runOnIdle{nav.tab("home").requestFocus()}
  compose.onRoot().performKeyInput{keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)}
  compose.onNodeWithTag("live").assertIsFocused();assertFalse(nav.startup.established)
 }
 @Test fun realModernTopBarUsesBothFirstAndSecondRightPresses() = modernTopBar(false)
 @Test fun twoQueuedStartupDirectionsAreBothApplied() = modernTopBar(true)
 private fun modernTopBar(queued: Boolean) {
  val nav=NavigationFocus();var selected by mutableStateOf(NavTabs.HOME)
  compose.mainClock.autoAdvance=false
  compose.setContent {
   CompositionLocalProvider(LocalNavigationFocus provides nav) {
    EmberTheme(0) {
     androidx.tv.material3.Surface(modifier=Modifier.fillMaxSize()) {
     Column(Modifier.fillMaxSize().initialNavigationFocus(nav,selected,true)) {
      ModernTopBar(listOf(NavTabs.HOME,NavTabs.LIVE,NavTabs.APPS,NavTabs.SEARCH),selected,{selected=it},null,openOnFocus=true)
     }
     }
    }
   }
  }
  compose.onRoot().performKeyInput {
   keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)
   if(queued){keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)}
  }
  compose.mainClock.advanceTimeBy(500);compose.mainClock.autoAdvance=true
  if(!queued) {
   compose.runOnIdle{assertEquals(NavTabs.LIVE,selected);assertEquals(NavTabs.LIVE,nav.focusedTab)}
   compose.onRoot().performKeyInput {keyDown(Key.DirectionRight);keyUp(Key.DirectionRight)}
  }
  compose.runOnIdle{assertEquals(NavTabs.APPS,selected);assertEquals(NavTabs.APPS,nav.focusedTab)}
 }
}
