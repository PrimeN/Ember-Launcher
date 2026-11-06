package app.ember.tv

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.ember.tv.data.Meta
import app.ember.tv.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class HomeHeroScrollTest {
    @get:Rule val compose=createComposeRule()

    @Test fun movingToContentHidesWholeHeroAndUpRestoresIt() = checkScrolling()
    @Test fun emberXHeroAlsoClearsWhenMovingIntoRows() = checkScrolling(emberX=true)
    @Test fun reorderedHeroStillClearsAndRestores() = checkScrolling(leading=true)
    @Test fun oversizedHeroKeepsItsInternalScroll() = checkScrolling(height=600)
    @Test fun tvWatchlistFocusSettlesAndReturnsToTheHero() = checkScrolling(tv=true)
    @Test fun tvEmberXWatchlistFocusSettlesAndReturnsToTheHero() = checkScrolling(emberX=true,tv=true)
    @Test fun tvReorderedHeroStillSettles() = checkScrolling(leading=true,tv=true)
    @Test fun tvOversizedHeroStillScrolls() = checkScrolling(height=600,tv=true)

    @Test fun tvPolicyDoesNotPullTheHeroBackAfterTheFirstWatchlistRowIsAligned() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK,true)
        lateinit var state: LazyListState
        lateinit var normal: androidx.compose.foundation.gestures.BringIntoViewSpec
        lateinit var bring: HomeBringIntoViewSpec
        compose.setContent {
            state=rememberLazyListState()
            normal=LocalBringIntoViewSpec.current
            bring=remember {HomeBringIntoViewSpec(state,normal,{false},{true},0)}
            LazyColumn(state=state,modifier=Modifier.size(800.dp,500.dp).testTag("home"),
                contentPadding=PaddingValues(top=4.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                item(key="hero") {Box(Modifier.height(400.dp))}
                item(key="watchlist") {Box(Modifier.height(200.dp))}
                item(key="later") {Box(Modifier.height(200.dp))}
                item {Spacer(Modifier.height(600.dp))}
            }
        }
        compose.onNodeWithTag("home").performScrollToKey("watchlist")
        compose.runOnIdle {runBlocking {state.scrollToItem(1,-state.layoutInfo.viewportStartOffset)}}
        compose.runOnIdle {
            val info=state.layoutInfo
            val row=info.visibleItemsInfo.first {it.key=="watchlist"}
            val top=(row.offset-info.viewportStartOffset).toFloat()+32f
            val height=(info.viewportEndOffset-info.viewportStartOffset).toFloat()
            assertTrue("This reproduction must use the real TV pivot policy",normal.calculateScrollDistance(top,100f,height)<0f)
            assertEquals("An aligned watchlist must not pull the hidden hero back into view",0f,
                bring.calculateScrollDistance(top,100f,height),.5f)
            val withoutHero=HomeBringIntoViewSpec(state,normal,{false},{true},-1)
            assertEquals(normal.calculateScrollDistance(top,100f,height),withoutHero.calculateScrollDistance(top,100f,height),0f)
        }
        compose.onNodeWithTag("home").performScrollToKey("later")
        compose.runOnIdle {
            val info=state.layoutInfo
            val row=info.visibleItemsInfo.first {it.key=="later"}
            val top=(row.offset-info.viewportStartOffset).toFloat()+32f
            val height=(info.viewportEndOffset-info.viewportStartOffset).toFloat()
            assertEquals("Later rows keep the ordinary TV pivot",normal.calculateScrollDistance(top,100f,height),
                bring.calculateScrollDistance(top,100f,height),.5f)
        }
    }

    private fun checkScrolling(emberX: Boolean=false, leading: Boolean=false, height: Int=400, tv: Boolean=false) {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK,tv)
        lateinit var state: LazyListState
        val firstCard=FocusRequester()
        compose.setContent {
            state=rememberLazyListState()
            var focused by remember {mutableStateOf(false)}
            ReleaseHiddenHeroFocus(state,focused)
            val normal=LocalBringIntoViewSpec.current
            val bring=remember {HomeBringIntoViewSpec(state,normal,{focused},{height<=500},if(leading)1 else 0)}
            val scope=rememberCoroutineScope()
            val entered: ()->Unit = {if(height<=500)scope.launch {state.animateScrollToItem(if(leading)1 else 0)}}
            EmberTheme(0) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides bring) {
                    LazyColumn(state=state,modifier=Modifier.size(800.dp,500.dp).clipToBounds().testTag("home"),
                        verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        if(leading)item(key="before") {Spacer(Modifier.height(120.dp))}
                        item(key="hero") {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides normal) {
                                val titles=listOf(Meta(id="featured",type="movie",name="Featured title"))
                                if(emberX)EmberXHero(titles,0,{},height.dp,{false},{},{},entered,false,null,{focused=it})
                                else ModernHero(titles,0,{},height.dp,{false},{},{},entered,false,onFocusState={focused=it})
                            }
                        }
                        item(key="cards") {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides normal) {
                                LazyRow(Modifier.height(100.dp)) {
                                    items(8) { index ->
                                        Box(Modifier.size(100.dp).then(if(index==0)Modifier.focusRequester(firstCard) else Modifier)
                                            .testTag("card-$index").focusable())
                                    }
                                }
                            }
                        }
                        item {Spacer(Modifier.height(600.dp))}
                    }
                }
            }
        }
        if(tv)compose.mainClock.autoAdvance=false
        compose.onNodeWithText("Watch now").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithText("Watch now").performKeyInput {pressKey(Key.DirectionDown)}
        if(tv) {
            compose.mainClock.advanceTimeBy(1600)
            var settled=0 to 0
            compose.runOnIdle {settled=state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset}
            repeat(12) {
                compose.mainClock.advanceTimeByFrame()
                compose.runOnIdle {assertEquals("TV scrolling must converge rather than oscillate",settled,
                    state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)}
            }
        }
        compose.onNodeWithTag("card-0").assertIsFocused()
        compose.runOnIdle {
            assertTrue("No hero tail should remain when focus is in the content row",
                state.layoutInfo.visibleItemsInfo.none {it.key=="hero" && it.offset+it.size>state.layoutInfo.viewportStartOffset})
        }
        compose.onNodeWithTag("card-0").performKeyInput {pressKey(Key.DirectionRight)}
        compose.onNodeWithTag("card-1").assertIsFocused()
        compose.onNodeWithTag("card-1").performKeyInput {pressKey(Key.DirectionLeft)}
        compose.onNodeWithTag("card-0").performKeyInput {pressKey(Key.DirectionUp)}
        if(tv)compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithText("Watch now").assertIsFocused()
        compose.runOnIdle {
            val hero=state.layoutInfo.visibleItemsInfo.first {it.key=="hero"}
            val fraction=HeroLayout.visibleFraction(hero.offset,hero.size,state.layoutInfo.viewportStartOffset,state.layoutInfo.viewportEndOffset)
            if(height<=500)assertEquals(1f,fraction,0f) else assertTrue(fraction>0f)
        }
        compose.onNodeWithTag("home").performScrollToKey("cards")
        if(tv)compose.mainClock.advanceTimeBy(64)
        compose.onAllNodes(hasText("Watch now") and isFocused()).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue("Explicit scrolling must also remove the hero",
                state.layoutInfo.visibleItemsInfo.none {it.key=="hero" && it.offset+it.size>state.layoutInfo.viewportStartOffset})
        }
    }
}
