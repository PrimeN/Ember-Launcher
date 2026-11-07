package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.tv.material3.Text
import app.ember.tv.data.*
import kotlinx.coroutines.delay

/** Flat wordmark/header with bold selected tab, and Ember's complete launcher navigation. */
@Composable
internal fun EmberXTopBar(tabs:List<String>,current:String,onTab:(String)->Unit,profile:Profile,onProfile:()->Unit) {
    val navigation=LocalNavigationFocus.current
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    val activeTab=navigationTabForPage(current,tabs)
    val entries=tabs.filter {it !in setOf(NavTabs.SEARCH,NavTabs.SCREENSAVER,NavTabs.QUICK)}
    Row(Modifier.fillMaxWidth().height(EmberXLayout.NavHeight.dp)
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.88f),EmberXColors.Background.copy(alpha=.84f))))
        .padding(horizontal=EmberXLayout.Gutter.dp)
        .navigationFocusGroup {runCatching {navigation?.tab(activeTab)?.requestFocus()==true}.getOrDefault(false)},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("EMBER X",color=EmberXColors.Accent,fontSize=23.sp,fontWeight=FontWeight.Black,letterSpacing=1.sp,
            maxLines=1,modifier=Modifier.widthIn(max=144.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            entries.forEachIndexed {index,id->key(id) {
                val selected=activeTab==id
                Focusable(onClick={onTab(id)},modifier=Modifier.height(42.dp)
                    .let {m->navigation?.let {m.navigationTabTarget(it,id)} ?: m}
                    .focusProperties {
                        if(navigation!=null) {
                            fun neighbor(rightward:Boolean):FocusRequester {
                                val position=index+choiceStep(rightward,rtl)
                                return entries.getOrNull(position)?.let(navigation::tab)
                                    ?: if(position>=entries.size && NavTabs.SEARCH in tabs)navigation.tab(NavTabs.SEARCH) else FocusRequester.Default
                            }
                            left=neighbor(false);right=neighbor(true)
                            down=navigation.content(current)
                        }
                    }.semantics {this.selected=selected;role=Role.Tab}
                    .openNavigationTab(id,selected,enabled=id!=NavTabs.SETTINGS){onTab(id)},radius=TvTokens.Shape.Tight,container=Color.Transparent,
                    focusedContainer=EmberXColors.Selected,focusedContent=Color.White,outline=false,focusedScale=TvTokens.Focus.None) {
                    Column(Modifier.height(42.dp).padding(horizontal=9.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(tabTitle(id),fontSize=14.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium,
                            color=if(selected)Color.White else EmberXColors.Secondary,maxLines=1)
                    }
                }
            }}
        }
        if(NavTabs.SEARCH in tabs)ToolbarIcon("Search",Icons.Outlined.Search,Modifier.let {m->navigation?.let {m.navigationTabTarget(it,NavTabs.SEARCH)} ?: m}
            .focusProperties {if(navigation!=null){down=navigation.content(current);if(rtl)right=entries.lastOrNull()?.let(navigation::tab) ?: FocusRequester.Default else left=entries.lastOrNull()?.let(navigation::tab) ?: FocusRequester.Default}}
            .openNavigationTab(NavTabs.SEARCH,current==NavTabs.SEARCH){onTab(NavTabs.SEARCH)},selected=current==NavTabs.SEARCH) {onTab(NavTabs.SEARCH)}
        ProfileMenuButton(profile,size=32.dp,onFallback=onProfile)
        ToolbarIcon("Screensaver",Icons.Outlined.Image) {onTab(NavTabs.SCREENSAVER)}
        ToolbarIcon("Quick settings",Icons.Outlined.Settings) {onTab(NavTabs.QUICK)}
    }
}

/** Billboard actions retain the same data, watchlist and focus ownership as the other looks. */
@Composable
internal fun EmberXHero(items:List<Meta>,index:Int,onIndex:(Int)->Unit,height:androidx.compose.ui.unit.Dp,
    isSaved:(Meta)->Boolean,onToggleSaved:(Meta)->Unit,onOpen:(Meta)->Unit,onFocus:()->Unit,rotating:Boolean,
    presented:Meta?,onFocusState:(Boolean)->Unit) {
    if(items.isEmpty())return
    val safe=HeroBanner.index(index,items.size)
    val shown=HeroBanner.displayIndex(index,items,presented)
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    val meta=presented?.takeIf {shown->items.any {it.mediaKey()==shown.mediaKey()}} ?: items[safe]
    var focused by remember {mutableStateOf(false)}
    val play=remember {FocusRequester()};val save=remember {FocusRequester()}
    val reduced=LocalReduceMotion.current
    val background=LocalFocusBackdrop.current
    DisposableEffect(Unit){onDispose {onFocusState(false)}}
    LaunchedEffect(items,safe,rotating,focused,reduced,presented?.mediaKey()) {
        if(items.size>1 && rotating && !focused && !reduced && (presented==null || presented.mediaKey()==items[safe].mediaKey())) {
            delay(9000);onIndex(HeroBanner.next(safe,items.size))
        }
    }
    PrefetchHeroArtwork(items,safe,enabled=rotating)
    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.25f
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(if(large).86f else .53f).padding(top=24.dp,bottom=38.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Text("EMBER X",color=EmberXColors.Accent,fontWeight=FontWeight.Bold,fontSize=TvTokens.Type.Caption.sp,letterSpacing=1.sp)
                Text(if(meta.type=="movie")"FILM" else "SERIES",color=EmberXColors.Secondary,fontSize=TvTokens.Type.Caption.sp,letterSpacing=1.sp)
            }
            HeroTitle(meta,expanded=true)
            Text(listOfNotNull(meta.releaseInfo,meta.runtime,meta.ageRating,meta.genres?.take(3)?.joinToString(" · ")).joinToString("   "),
                color=EmberXColors.Secondary,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            ReadableDescription(meta.description.orEmpty(),fontSize=14.sp,lineHeight=20.sp,maxLines=3,minLines=2)
            ButtonRow(Modifier.onFocusChanged {
                focused=it.hasFocus;onFocusState(it.hasFocus)
                if(it.hasFocus){background?.clear();onFocus()}
            },spacing=8.dp) {
                DetailsActionButton("Watch now",Icons.Outlined.PlayArrow,text="Watch now",width=120.dp,
                    modifier=Modifier.focusRequester(play).focusProperties {if(rtl)left=save else right=save}.restoreFocus(meta.mediaKey()).onPreviewKeyEvent {event->
                        if(event.key!=heroEdgeKey(previous=true,rtl=rtl) || items.size<2)false else {
                            if(HeroBanner.pageOnPress(event.type==KeyEventType.KeyDown,event.nativeKeyEvent.repeatCount,items.size))onIndex(HeroBanner.previous(safe,items.size));true
                        }
                    }) {onOpen(meta)}
                DetailsActionButton(if(isSaved(meta))"Remove from watchlist" else "Add to watchlist",if(isSaved(meta))Icons.Outlined.Check else Icons.Outlined.Add,
                    selected=isSaved(meta),width=42.dp,modifier=Modifier.focusRequester(save).focusProperties {if(rtl)right=play else left=play}.onPreviewKeyEvent {event->
                        if(event.key!=heroEdgeKey(previous=false,rtl=rtl) || items.size<2)false else {
                            if(HeroBanner.pageOnPress(event.type==KeyEventType.KeyDown,event.nativeKeyEvent.repeatCount,items.size))onIndex(HeroBanner.next(safe,items.size));true
                        }
                    }) {onToggleSaved(meta)}
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(bottom=30.dp).clearAndSetSemantics {contentDescription="Featured title ${shown+1} of ${items.size}"},horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed {i,_->Box(Modifier.width(18.dp).height(2.dp).background(if(i==shown)Color.White else Color.White.copy(alpha=.3f)))}
        }
    }
}
