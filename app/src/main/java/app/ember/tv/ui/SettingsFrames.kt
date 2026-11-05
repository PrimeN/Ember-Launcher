package app.ember.tv.ui
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.outlined.Settings as IcSettings
import androidx.compose.material.icons.outlined.Check

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup as IcBackup
import androidx.compose.material.icons.outlined.ClosedCaption as IcClosedCaption
import androidx.compose.material.icons.outlined.Dashboard as IcDashboard
import androidx.compose.material.icons.outlined.Description as IcDescription
import androidx.compose.material.icons.outlined.Dns as IcDns
import androidx.compose.material.icons.outlined.Extension as IcExtension
import androidx.compose.material.icons.outlined.Gavel as IcGavel
import androidx.compose.material.icons.outlined.GridView as IcGridView
import androidx.compose.material.icons.outlined.Home as IcHome
import androidx.compose.material.icons.outlined.Image as IcImage
import androidx.compose.material.icons.outlined.Info as IcInfo
import androidx.compose.material.icons.outlined.Key as IcKey
import androidx.compose.material.icons.outlined.LiveTv as IcLiveTv
import androidx.compose.material.icons.outlined.Menu as IcMenu
import androidx.compose.material.icons.outlined.Palette as IcPalette
import androidx.compose.material.icons.outlined.People as IcPeople
import androidx.compose.material.icons.outlined.PhoneAndroid as IcPhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow as IcPlayArrow
import androidx.compose.material.icons.outlined.PlayCircle as IcPlayCircle
import androidx.compose.material.icons.outlined.Subscriptions as IcSubscriptions
import androidx.compose.material.icons.outlined.Sync as IcSync
import androidx.compose.material.icons.outlined.TextFields as IcTextFields
import androidx.compose.material.icons.outlined.ToggleOn as IcToggleOn
import androidx.compose.material.icons.outlined.Search as IcSearch
import androidx.compose.material.icons.outlined.Wallpaper as IcWallpaper
import androidx.compose.material.icons.outlined.WbSunny as IcWbSunny
import androidx.compose.material.icons.outlined.FormatSize as IcFormatSize
import androidx.compose.material.icons.outlined.Speed as IcSpeed
import androidx.compose.material.icons.outlined.SettingsRemote as IcSettingsRemote
import androidx.compose.material.icons.outlined.Folder as IcFolder
import androidx.compose.material.icons.outlined.Widgets as IcWidgets
import androidx.compose.material.icons.outlined.GraphicEq as IcGraphicEq

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import app.ember.tv.screensaver.Photos
import app.ember.tv.screensaver.ScreensaverFeed
import app.ember.tv.HomeViewModel
import app.ember.tv.data.NavTabs
import app.ember.tv.data.Prefs
import app.ember.tv.tracker.SmartRows
import app.ember.tv.tracker.LinkMode
import app.ember.tv.tracker.Tracker
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.focusProperties
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import app.ember.tv.MainActivity
import app.ember.tv.data.ApiKeys
import app.ember.tv.data.Legal
import app.ember.tv.data.About
import app.ember.tv.data.MetaEnhance
import androidx.compose.ui.draw.clip
import app.ember.tv.data.Meta
import app.ember.tv.data.Posters
import app.ember.tv.data.PosterConfig
import app.ember.tv.data.Backup
import app.ember.tv.data.Profile
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState

// Settings pages, split out of SettingsScreen.kt. The full-page and floating Settings frames.

// ---------------------------------------------------------------- Modern Settings

/** Six category stops, lazy topic rows and a retained page pane. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModernSettings(
    location:SettingsLocation,onCategory:(SettingsCategory)->Unit,onCategoryClick:(SettingsCategory)->Unit,
    onPage:(SettingsTab)->Unit,onPageBack:()->Unit,backToSearch:Boolean,lastTopic:SettingsTab?,
    focusContent:Boolean,onFocusHandled:()->Unit,targetIndex:Int?=null,page:LazyListScope.()->Unit,
) {
    val target=LocalSettingsTarget.current
    androidx.compose.runtime.CompositionLocalProvider(LocalSlate provides true) {
        val sideFocus=remember {androidx.compose.ui.focus.FocusRequester()}
        val contentFocus=remember {androidx.compose.ui.focus.FocusRequester()}
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Tint.of(.18f),Tint.of(.05f))))) {
            val sideWidth=(maxWidth*.23f).coerceIn(180.dp,252.dp)
            Row(Modifier.fillMaxSize().padding(TvTokens.Space.XXL.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Column(Modifier.width(sideWidth).fillMaxHeight().focusRestorer()
                    .verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_settings),fontSize=TvTokens.Type.Page.sp,fontWeight=FontWeight.SemiBold,
                        color=Fg,modifier=Modifier.padding(start=12.dp,bottom=12.dp))
                    SettingsCategory.entries.forEach {category->key(category) {
                        SettingsCategoryButton(category,category==location.category,
                            if(category==location.category)sideFocus else null,contentFocus,
                            onFocus={if(target==null || target.fulfilled)onCategory(category)},
                            onClick={if(target==null || target.fulfilled)onCategoryClick(category)})
                    }}
                    Text(stringResource(R.string.settings_remote_hint),fontSize=TvTokens.Type.Caption.sp,color=Mute,
                        modifier=Modifier.padding(horizontal=12.dp,vertical=12.dp))
                }
                val scene=location.page?.let {"settings:${it.name}"} ?: "settings:category:${location.category.name}"
                // A page opens at its top every time: no remembered scroll position.
                key(scene) {
                    val topicFocus=remember {androidx.compose.ui.focus.FocusRequester()}
                    val list=androidx.compose.foundation.lazy.rememberLazyListState()
                    androidx.compose.runtime.CompositionLocalProvider(LocalFocusScene provides scene) {
                        Column(Modifier.weight(1f).fillMaxHeight().sceneEnter(scene)
                            .background(PanelHi.copy(alpha=.45f),RoundedCornerShape(TvTokens.Shape.Panel.dp))
                            .padding(horizontal=24.dp,vertical=20.dp)
                            .sceneContent(scene,NavTabs.SETTINGS,enabled=false)
                            .settingsPage(sideFocus,if(location.topics()!=null)onPageBack else null)) {
                            // Back on the remote returns to the category (or to search); no on-screen Back button.
                            Text(location.page?.title() ?: location.category.representative.groupTitle(),
                                fontSize=TvTokens.Type.Pane.sp,fontWeight=FontWeight.SemiBold,color=Fg)
                            Text(location.page?.summary() ?: stringResource(R.string.settings_choose_topic),
                                fontSize=13.sp,color=Mute,modifier=Modifier.padding(top=4.dp,bottom=12.dp))
                            LaunchedEffect(location,target,focusContent) {
                                if(target!=null && !target.fulfilled) {
                                    withFrameNanos { }
                                    list.scrollToItem(targetIndex ?: 0)
                                    withFrameNanos { }
                                    target.prepare()
                                    val focused=if(target.id==null)false else
                                        kotlinx.coroutines.withTimeoutOrNull(750){target.completion.await()}==true
                                    if(!focused) {
                                        requestSettingsFocus({contentFocus.requestFocus()},{withFrameNanos { }}) {
                                            runCatching {sideFocus.requestFocus()}
                                        }
                                        target.complete()
                                    }
                                    onFocusHandled()
                                } else if(focusContent) {
                                    withFrameNanos { }
                                    if(location.page==null) {
                                        val index=location.category.pages.indexOf(lastTopic).coerceAtLeast(0)
                                        list.scrollToItem(index)
                                        withFrameNanos { }
                                    }
                                    val requester=if(location.page==null)topicFocus else contentFocus
                                    requestSettingsFocus({requester.requestFocus()},{withFrameNanos { }}) {
                                        runCatching {sideFocus.requestFocus()}
                                    }
                                    onFocusHandled()
                                }
                            }
                            LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth().focusRequester(contentFocus).focusGroup().dpadScroll(list),
                                contentPadding=PaddingValues(start=4.dp,end=4.dp,top=8.dp,bottom=36.dp),
                                verticalArrangement=Arrangement.spacedBy(if(location.page==null)8.dp else 18.dp)) {
                                if(location.page==null) {
                                    val entry=lastTopic?.takeIf {it in location.category.pages} ?: location.category.pages.firstOrNull()
                                    items(location.category.pages,key={it.name}) {topic->
                                        SettingsTopicRow(topic,Modifier.let {if(topic==entry)it.focusRequester(topicFocus) else it}) {onPage(topic)}
                                    }
                                } else page()
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            if(location.page==null && !focusContent) {withFrameNanos { };runCatching {sideFocus.requestFocus()}}
        }
    }
}

@Composable
private fun SettingsCategoryButton(category:SettingsCategory,selected:Boolean,requester:androidx.compose.ui.focus.FocusRequester?,
    content:androidx.compose.ui.focus.FocusRequester,onFocus:()->Unit,onClick:()->Unit) {
    var focused by remember {mutableStateOf(false)}
    val rtl=androidx.compose.ui.platform.LocalLayoutDirection.current==androidx.compose.ui.unit.LayoutDirection.Rtl
    Focusable(onClick=onClick,modifier=Modifier.fillMaxWidth()
        .let {m->requester?.let {m.focusRequester(it)} ?: m}
        .then(Modifier.focusProperties {if(rtl)left=content else right=content})
        .semantics {this.selected=selected}
        .onFocusChanged {focused=it.isFocused;if(it.isFocused)onFocus()},
        radius=50,container=if(selected)WashHi else Color.Transparent,
        focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=1f) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=14.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            androidx.tv.material3.Icon(category.representative.icon(),null,tint=if(focused)OnPillColor else Fg,modifier=Modifier.size(22.dp))
            Text(if(category==SettingsCategory.SEARCH)category.representative.title() else category.representative.groupTitle(),
                fontSize=TvTokens.Type.Label.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Medium,
                modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
            if(selected)androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Check,null,modifier=Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsTopicRow(topic:SettingsTab,modifier:Modifier=Modifier,onClick:()->Unit) {
    var focused by remember {mutableStateOf(false)}
    val foreground=if(focused)OnPillColor else Fg
    Focusable(onClick=onClick,modifier=modifier.fillMaxWidth().onFocusChanged {focused=it.isFocused},radius=18,
        container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor,outline=false,focusedScale=1f) {
        Row(Modifier.fillMaxWidth().heightIn(min=66.dp).padding(horizontal=16.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            androidx.tv.material3.Icon(topic.icon(),null,tint=foreground,modifier=Modifier.size(24.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(topic.title(),fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(topic.summary(),fontSize=TvTokens.Type.Caption.sp,color=if(focused)OnPillColor.copy(alpha=.75f) else Mute,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowForward,null,
                tint=foreground,modifier=Modifier.size(18.dp))
        }
    }
}

/** Each Settings page's own Material icon (Material Icons, outlined). */
internal fun SettingsTab.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    SettingsTab.SEARCH -> Icons.Outlined.IcSearch
    SettingsTab.FEATURES -> Icons.Outlined.IcToggleOn
    SettingsTab.TOP_MENU -> Icons.Outlined.IcMenu
    SettingsTab.DISCOVER -> Icons.Outlined.IcGridView
    SettingsTab.HOME_TOOLS -> Icons.Outlined.IcFolder
    SettingsTab.SIZE -> Icons.Outlined.IcFormatSize
    SettingsTab.PERFORMANCE -> Icons.Outlined.IcSpeed
    SettingsTab.REMOTE -> Icons.Outlined.IcSettingsRemote
    SettingsTab.LIGHT_HOME -> Icons.Outlined.IcWallpaper
    SettingsTab.HOME_EXTRAS -> Icons.Outlined.IcWidgets
    SettingsTab.AUDIO_VIDEO -> Icons.Outlined.IcGraphicEq
    SettingsTab.HOME_ROWS -> Icons.Outlined.IcDashboard
    SettingsTab.APPEARANCE -> Icons.Outlined.IcPalette
    SettingsTab.POSTERS -> Icons.Outlined.IcImage
    SettingsTab.METADATA -> Icons.Outlined.IcDescription
    SettingsTab.WEATHER -> Icons.Outlined.IcWbSunny
    SettingsTab.PLAYER -> Icons.Outlined.IcPlayCircle
    SettingsTab.WATCH_STATUS -> Icons.Outlined.IcToggleOn
    SettingsTab.UPDATES -> Icons.Outlined.IcSync
    SettingsTab.DEBUG -> Icons.Outlined.IcDescription
    SettingsTab.PLAY_BUTTON -> Icons.Outlined.IcPlayArrow
    SettingsTab.ADDONS -> Icons.Outlined.IcExtension
    SettingsTab.CATALOGS -> Icons.Outlined.IcGridView
    SettingsTab.STREMIO -> Icons.Outlined.IcTextFields
    SettingsTab.SUBTITLES -> Icons.Outlined.IcClosedCaption
    SettingsTab.LIVE -> Icons.Outlined.IcLiveTv
    SettingsTab.STREAMING -> Icons.Outlined.IcSubscriptions
    SettingsTab.PROFILES -> Icons.Outlined.IcPeople
    SettingsTab.TRACKERS -> Icons.Outlined.IcSync
    SettingsTab.SERVERS -> Icons.Outlined.IcDns
    SettingsTab.KEYS -> Icons.Outlined.IcKey
    SettingsTab.DEFAULT_HOME -> Icons.Outlined.IcHome
    SettingsTab.DEVICE_TOOLS -> Icons.Outlined.IcSettings
    SettingsTab.PHONE -> Icons.Outlined.IcPhoneAndroid
    SettingsTab.SCREENSAVER -> Icons.Outlined.IcWallpaper
    SettingsTab.BACKUP -> Icons.Outlined.IcBackup
    SettingsTab.ABOUT -> Icons.Outlined.IcInfo
    SettingsTab.LEGAL -> Icons.Outlined.IcGavel
}

/** Modern On/Off row: the whole row is one button with a switch on the right. */
@Composable
internal fun ModernToggleRow(label: String, note: String?, on: Boolean, onToggle: () -> Unit) {
    SettingToggle(label, note, on, onToggle)
}



/** Settings > About > Crash log: the last crashes recorded on this TV, newest first. */
@Composable
internal fun CrashLogSection() {
    var entries by remember { mutableStateOf(app.ember.tv.data.CrashLog.entries()) }
    var shown by remember { mutableIntStateOf(0) }
    Section(stringResource(R.string.ui_crash_log), stringResource(R.string.ui_if_ember_closes_by_itself_the),id="crash-log") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (entries.isEmpty()) {
                Note(stringResource(R.string.ui_no_crashes_recorded))
            } else {
                ButtonRow(spacing = 10.dp) {
                    entries.forEachIndexed { i, _ -> if (i < 6) Chip(if (i == 0) "Latest" else "#${i + 1}", selected = shown == i) { shown = i } }
                    Chip(stringResource(R.string.ui_clear)) {
                        app.ember.tv.data.CrashLog.clear()
                        entries = emptyList()
                        shown = 0
                    }
                }
                val text = entries.getOrElse(shown) { entries.first() }
                // Focusable so the remote can scroll through a long trace.
                Focusable(onClick = {}, modifier = Modifier.width(760.dp), radius = 12, container = Panel) {
                    Text(
                        text.take(6_000),
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
    }
}
