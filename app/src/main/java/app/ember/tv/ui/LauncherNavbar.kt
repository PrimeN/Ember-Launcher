package app.ember.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.ember.tv.data.*
import kotlinx.coroutines.launch

/** The page occupies a stable rectangle; the expanded menu is drawn over that rectangle. */
@Composable
internal fun LauncherNavbarFrame(style:String,launcher:String,position:String,tabs:List<String>,current:String,
    weather:String?,profile:Profile,onTab:(String)->Unit,onProfile:()->Unit,modifier:Modifier=Modifier,
    content:@Composable (Boolean)->Unit) {
    val chosen=NavbarStyles.normalize(style)
    val enabled=NavbarStyles.available(launcher) && current!=NavTabs.SETTINGS
    val side=NavbarStyles.side(chosen,launcher,position,current)
    val owner=LocalNavigationFocus.current
    val active=navigationTabForPage(current,tabs)
    var expanded by remember(chosen,enabled,profile.id) {mutableStateOf(false)}
    var menuHadFocus by remember(chosen,enabled,profile.id) {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val duration=PolishMotion.duration(TvTokens.Motion.Rail,LocalRenderOptions.current,LocalReduceMotion.current)
    val reveal=remember(chosen) {Animatable(0f)}
    val latestPage by rememberUpdatedState(current)
    // Start-up and TV wake (#103) put focus on the page's tab, which for Collapsed is the handle.
    // Only focus that follows a remote press opens the menu (#127).
    var lastKey by remember {mutableLongStateOf(-1L)}
    fun pressedJustNow()=lastKey>=0 && android.os.SystemClock.uptimeMillis()-lastKey<NavbarStyles.HANDLE_PRESS_MS
    fun close(page:String=latestPage) {
        expanded=false;menuHadFocus=false
        scope.launch {
            repeat(2){withFrameNanos { }}
            if(owner==null)focus.clearFocus() else runCatching {owner.content(page).requestFocus()}
        }
    }
    LaunchedEffect(expanded) {
        if(expanded) {
            reveal.snapTo(0f)
            if(owner!=null)requestSettingsFocus({owner.tab(active).requestFocus()},{withFrameNanos { }}) {}
            reveal.animateTo(1f,tween(duration))
        } else reveal.snapTo(0f)
    }
    BackHandler(enabled=expanded){close()}
    BoxWithConstraints(modifier.fillMaxSize().onPreviewKeyEvent {
        if(it.type==KeyEventType.KeyDown)lastKey=android.os.SystemClock.uptimeMillis()
        false
    }) {
        val panelHeight=(maxHeight-76.dp).coerceAtLeast(100.dp)
        val panelWidth=minOf(220.dp*LocalDensity.current.fontScale.coerceIn(1f,1.5f),maxWidth-32.dp)
        Column(Modifier.fillMaxSize().padding(start=if(side)84.dp else 0.dp)) {
            if(enabled && !side) NavbarScope(chosen) {
                when(chosen) {
                    NavbarStyles.EMBER -> ModernTopBar(tabs,current,onTab,weather,profile,onProfile=onProfile)
                    NavbarStyles.CINEMA -> CinemaNavbar(tabs,current,onTab,weather,profile,onProfile)
                    else -> Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal=24.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        ProfileMenuButton(profile,size=36.dp,onFallback=onProfile)
                        GlassControl(tabTitle(active),null,Modifier.testTag("navbar_handle")
                            .then(if(!expanded && owner!=null)Modifier.navigationTabTarget(owner,active) else Modifier)
                            .focusProperties {canFocus=!expanded;if(owner!=null)down=owner.content(current)}
                            .onFocusChanged {if(it.isFocused && pressedJustNow())expanded=true}
                            .pointerInput(Unit){awaitPointerEventScope {while(true)if(awaitPointerEvent().type==PointerEventType.Enter)expanded=true}},
                            selected=true,dropdown=true) {expanded=true}
                        Spacer(Modifier.weight(1f))
                        NavbarStatus(weather)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("navbar_content")) {content(side)}
        }
        if(enabled && side) NavbarScope(NavbarStyles.EMBER) {
            ModernSideRail(tabs,current,onTab,weather,profile=profile,onProfile=onProfile)
        }
        if(enabled && chosen==NavbarStyles.COLLAPSED && expanded) {
            Box(Modifier.padding(top=64.dp).fillMaxSize().background(Color.Black.copy(alpha=.16f))
                .pointerInput(Unit){detectTapGestures {close()}})
            NavbarScope(chosen) {
                Column(Modifier.align(Alignment.TopStart).padding(start=24.dp,top=64.dp).width(panelWidth)
                    .heightIn(max=panelHeight).graphicsLayer {alpha=reveal.value;translationX=-20.dp.toPx()*(1f-reveal.value)}
                    .glassSurface(RoundedCornerShape(18.dp)).padding(8.dp)
                    .onFocusChanged {
                        if(it.hasFocus)menuHadFocus=true else if(menuHadFocus){expanded=false;menuHadFocus=false}
                    }
                    .navigationFocusGroup {runCatching {owner?.tab(active)?.requestFocus()==true}.getOrDefault(false)}
                    .verticalScroll(rememberScrollState()).testTag("navbar_expanded"),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    val entries=NavbarStyles.entries(tabs)
                    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
                    entries.forEachIndexed {index,id->key(id) {
                        NavbarTab(id,active==id,Modifier.fillMaxWidth().height(42.dp)
                            .then(if(owner!=null)Modifier.navigationTabTarget(owner,id) else Modifier)
                            .focusProperties {
                                if(owner!=null) {
                                    if(rtl)left=owner.content(current) else right=owner.content(current)
                                    up=entries.getOrNull(index-1)?.let(owner::tab) ?: FocusRequester.Default
                                    down=entries.getOrNull(index+1)?.let(owner::tab) ?: FocusRequester.Default
                                }
                            }.openNavigationTab(id,active==id){onTab(id)}) {onTab(id);close(id)}
                    }}
                    Spacer(Modifier.height(4.dp))
                    GlassControl("Screensaver",Icons.Outlined.Image,Modifier.fillMaxWidth()) {onTab(NavTabs.SCREENSAVER);close()}
                    GlassControl("Settings",Icons.Outlined.Settings,Modifier.fillMaxWidth()) {onTab(NavTabs.QUICK);close()}
                }
            }
        }
    }
}

@Composable
private fun NavbarScope(style:String,content:@Composable ()->Unit) {
    // Day pages are light: Collapsed and Cinema Bar keep night colours only over the dark theme (#128).
    val night=NavbarStyles.forcesNight(style,Ui.isDay)
    CompositionLocalProvider(LocalInterfaceStyle provides InterfaceStyles.MODERN,
        LocalNight provides (night || LocalNight.current)) {
        CompositionLocalProvider(LocalContentColor provides if(night)Color.White else Fg,content=content)
    }
}

@Composable
private fun NavbarStatus(weather:String?) {
    Column(Modifier.widthIn(max=170.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            EmberLogo(22.dp,Fg);Text(rememberLauncherTime(),fontSize=14.sp,color=Fg,maxLines=1)
        }
        weather?.takeIf {it.isNotBlank()}?.let {Text(it,color=Mute,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
    }
}

/** Spacious transparent cinema header; only its top fade shades the artwork. */
@Composable
private fun CinemaNavbar(tabs:List<String>,current:String,onTab:(String)->Unit,weather:String?,profile:Profile,onProfile:()->Unit) {
    val owner=LocalNavigationFocus.current
    val entries=NavbarStyles.entries(tabs)
    val active=navigationTabForPage(current,tabs)
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact=maxWidth<1000.dp
    val fade=if(Ui.isDay && !LocalNight.current)Ink.copy(alpha=.72f) else Color.Black.copy(alpha=.42f)
    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(fade,Color.Transparent)))
        .padding(horizontal=24.dp,vertical=12.dp)
        .navigationFocusGroup {runCatching {owner?.tab(active)?.requestFocus()==true}.getOrDefault(false)},
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        ProfileMenuButton(profile,size=36.dp,onFallback=onProfile)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            entries.forEachIndexed {index,id->key(id) {
                NavbarTab(id,active==id,Modifier.height(44.dp)
                    .then(if(owner!=null)Modifier.navigationTabTarget(owner,id) else Modifier)
                    .focusProperties {if(owner!=null){
                        left=entries.getOrNull(index+choiceStep(false,rtl))?.let(owner::tab) ?: FocusRequester.Default
                        right=entries.getOrNull(index+choiceStep(true,rtl))?.let(owner::tab) ?: FocusRequester.Default
                        down=owner.content(current)
                    }}.openNavigationTab(id,active==id){onTab(id)},compact=compact) {onTab(id)}
            }}
        }
        ToolbarIcon("Screensaver",Icons.Outlined.Image){onTab(NavTabs.SCREENSAVER)}
        ToolbarIcon("Quick settings",Icons.Outlined.Settings){onTab(NavTabs.QUICK)}
        NavbarStatus(weather)
    }
    }
}

@Composable
private fun NavbarTab(id:String,selected:Boolean,modifier:Modifier=Modifier,compact:Boolean=false,onClick:()->Unit) {
    val title=tabTitle(id)
    Focusable(onClick,modifier.testTag("navbar_tab_$id").semantics {this.selected=selected;role=Role.Tab;contentDescription=title},
        radius=TvTokens.Shape.Card,container=if(selected)Fg.copy(alpha=.14f) else Color.Transparent,
        focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=TvTokens.Focus.None) {
        Row(Modifier.padding(horizontal=if(compact)8.dp else 12.dp).heightIn(min=42.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(if(compact)6.dp else 9.dp)) {
            NavGlyph(id,LocalContentColor.current)
            Text(title,fontSize=if(compact)14.sp else 16.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,
                maxLines=1,softWrap=false,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("navbar_label_$id"))
        }
    }
}
