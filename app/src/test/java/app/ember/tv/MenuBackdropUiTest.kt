package app.ember.tv

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.ember.tv.ui.*
import app.ember.tv.data.Profile
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.inspector.WindowInspector

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class MenuBackdropUiTest {
 @get:Rule val compose=createComposeRule()
 private fun params()=WindowInspector.getGlobalWindowViews().mapNotNull { view ->
  view.layoutParams as? WindowManager.LayoutParams
 }
 @Test fun profileStylePopupGetsDimAndReleasesItOnClose() {
  var open by mutableStateOf(false)
  compose.setContent {
   Box(Modifier.fillMaxSize()) {
    if(open)Popup(properties=PopupProperties(focusable=true),onDismissRequest={open=false}) {
     PopupBackdrop()
     Box(Modifier.size(180.dp,120.dp).testTag("menu"))
    }
   }
  }
  compose.runOnIdle{open=true}
  compose.onNodeWithTag("menu").assertExists()
  compose.runOnIdle {
   val popup=params().single {it.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW}
   assertTrue(popup.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
   assertEquals(.38f,popup.dimAmount,.001f)
  }
  compose.runOnIdle{open=false}
  compose.onNodeWithTag("menu").assertDoesNotExist()
 }
 @Test fun profileDropdownKeepsItsAnchorFocusAfterDismissal() {
  val profile=Profile("main","Parent")
  val config=ProfileMenuConfig(listOf(profile),listOf(ProfileMenuAction.WATCHLIST),{},{},{})
  compose.setContent {
   CompositionLocalProvider(LocalProfileMenu provides config) {
    EmberTheme(0){Box(Modifier.fillMaxSize()){ProfileMenuButton(profile)}}
   }
  }
  val anchor=compose.onNodeWithContentDescription("Profile: Parent. Open profile menu")
  anchor.performSemanticsAction(SemanticsActions.RequestFocus)
  anchor.performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.onNodeWithText("Who's watching?").assertExists()
  compose.onNodeWithContentDescription("Parent, Profile").performSemanticsAction(SemanticsActions.OnClick){it()}
  compose.onNodeWithText("Who's watching?").assertDoesNotExist()
  anchor.assertIsFocused()
 }
 @Test fun dialogAndNestedPopupHaveOneBackdropOwner() {
  var nested by mutableStateOf(false)
  compose.setContent {
   TvDialog({},width=320.dp,height=220.dp) {
    Box(Modifier.size(50.dp).testTag("dialog"))
    if(nested)Popup(properties=PopupProperties(focusable=true)) {
     PopupBackdrop();Box(Modifier.size(80.dp).testTag("nested"))
    }
   }
  }
  compose.onNodeWithTag("dialog").assertExists()
  compose.runOnIdle{assertEquals(1,params().count {it.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0})}
  compose.runOnIdle{nested=true}
  compose.onNodeWithTag("nested").assertExists()
  compose.runOnIdle{assertEquals(1,params().count {it.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0})}
  compose.runOnIdle{nested=false}
  compose.onNodeWithTag("nested").assertDoesNotExist()
  compose.runOnIdle{assertEquals(1,params().count {it.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0})}
 }
 @Test fun leasePreservesFocusAndSecureFlagsAndClearsItsEffects() {
  val controller=Robolectric.buildActivity(android.app.Activity::class.java).setup()
  val activity=controller.get();val dialog=Dialog(activity);dialog.show()
  val window=dialog.window!!
  window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  val target=MenuBackdropTarget(activity.window,window.windowManager,activity,{window.attributes},{window.attributes=it})
  val lease=MenuBackdropLease(target)
  lease.update(false,20)
  assertEquals(.38f,window.attributes.dimAmount,.001f)
  assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
  lease.close();lease.close()
  assertEquals(0,window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
  assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
  dialog.dismiss();controller.pause().stop().destroy()
 }
 private class BlurManager(var allowed:Boolean) {
  var queries=0;var adds=0;var removes=0
  var listener:java.util.function.Consumer<Boolean>?=null
  val manager=java.lang.reflect.Proxy.newProxyInstance(WindowManager::class.java.classLoader,arrayOf(WindowManager::class.java)) {_,method,args->
   when(method.name){
    "isCrossWindowBlurEnabled"->{queries++;allowed}
    "addCrossWindowBlurEnabledListener"->{adds++;listener=args!!.last() as java.util.function.Consumer<Boolean>;null}
    "removeCrossWindowBlurEnabledListener"->{removes++;listener=null;null}
    else->null
   }
  } as WindowManager
 }
 @Test fun liveBlurTracksPlatformAvailabilityAndRemovesListener() {
  val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
  val manager=BlurManager(true);val attrs=WindowManager.LayoutParams()
  val target=MenuBackdropTarget(Any(),manager.manager,activity,{attrs},{})
  val lease=MenuBackdropLease(target);lease.update(true,20)
  assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
  assertEquals(20,attrs.blurBehindRadius);assertEquals(1,manager.adds)
  manager.listener!!.accept(false)
  assertEquals(0,attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
  assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
  manager.listener!!.accept(true)
  assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
  lease.close();assertEquals(1,manager.removes)
 }
 @Test fun lowPerformanceNeverQueriesOrSubscribesToBlur() {
  val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
  val manager=BlurManager(true);val attrs=WindowManager.LayoutParams()
  val lease=MenuBackdropLease(MenuBackdropTarget(Any(),manager.manager,activity,{attrs},{}))
  lease.update(false,20);lease.update(false,30)
  assertEquals(0,manager.queries);assertEquals(0,manager.adds)
  assertTrue(attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
  assertEquals(0,attrs.blurBehindRadius);lease.close()
 }
 @Test fun changingLowerMenuPerformanceCannotStealBackdropOwnership() {
  val activity=Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
  val manager=BlurManager(true);val host=Any()
  val lower=WindowManager.LayoutParams();val upper=WindowManager.LayoutParams()
  val a=MenuBackdropLease(MenuBackdropTarget(host,manager.manager,activity,{lower},{}))
  val b=MenuBackdropLease(MenuBackdropTarget(host,manager.manager,activity,{upper},{}))
  a.update(true,20)
  assertEquals(0,lower.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
  assertTrue(upper.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
  b.close()
  assertTrue(lower.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
  assertTrue(lower.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
  a.close()
 }

}
