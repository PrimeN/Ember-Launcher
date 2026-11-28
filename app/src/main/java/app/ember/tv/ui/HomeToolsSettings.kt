package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import app.ember.tv.data.AppEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.toggleAppsPage
import app.ember.tv.toggleHidden
import app.ember.tv.toggleLightNavigation
import app.ember.tv.updateHomeAppRows
import app.ember.tv.home.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home tools: Apps & folders and Extras under Settings > Home; Startup under Settings > System (#407). */
internal enum class HomeToolsPart { APPS, FOLDERS, STARTUP, EXTRAS }

/** Wording for the app pickers on Apps & folders (#190). */
/** One app in the picker (#210): its icon with the name underneath; dimmed when it is hidden. */
@Composable
private fun AppChoiceTile(app:AppEntry,shown:Boolean,modifier:Modifier,onClick:()->Unit) {
    val bitmap=rememberAppArtwork(app,circle=true)
    Focusable(onClick=onClick,modifier=modifier.semantics {contentDescription=AppChoices.spoken(app.label,shown);selected=shown}.testTag("app_choice"),
        radius=TvTokens.Shape.Card,container=if(shown)WashHi else Color.Transparent) {
        val ink=androidx.tv.material3.LocalContentColor.current
        Column(Modifier.fillMaxWidth().padding(vertical=8.dp,horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(AppChoices.ICON.dp)) {
                Box(Modifier.fillMaxSize().alpha(if(shown)1f else .3f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF1F3F4))) {
                    if(bitmap!=null)androidx.compose.foundation.Image(bitmap.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
                    else ArtworkPlaceholder(app.label,Modifier.fillMaxSize())
                }
                if(shown)Box(Modifier.align(Alignment.TopEnd).offset(x=5.dp,y=(-5).dp).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center) {
                    Text("✓",fontSize=12.sp,color=Color.White,fontWeight=FontWeight.Bold)
                }
            }
            Text(app.label,fontSize=12.sp,color=if(shown)ink else ink.copy(alpha=.55f),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** Where the TV opens (#224). */
internal object StartupChoices {
    val options=listOf("home" to "Home","app" to "An app","input" to "A TV input","presentation" to "Presentation")
    fun normalize(value:String?)=value?.takeIf {v->options.any {it.first==v}} ?: "home"
}

internal object AppChoices {
    /** Six apps across, at the wide dialog's width. */
    const val PER_ROW=6
    const val ICON=64
    fun count(shown:Int,all:Int)=when {all==0->"No apps";all==1->if(shown==1)"1 app" else "0 of 1 app";shown==all->"All $all apps";else->"$shown of $all apps"}
    fun spoken(name:String,shown:Boolean)="$name, "+if(shown)"shown" else "hidden"
}

@Composable internal fun HomeToolsPage(vm:HomeViewModel, part:HomeToolsPart) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val opts=vm.launcherExtras.options
    var chooseStartup by remember {mutableStateOf(false)}
    var startupKind by remember {mutableStateOf("app")}
    var chooseApps by remember {mutableStateOf<String?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(22.dp)) {
        // #190: the two places apps show, each choosing its apps in a dialog instead of a switch per app down the page.
        if(part==HomeToolsPart.APPS)Section(stringResource(R.string.ui_apps_on_home),stringResource(R.string.st_the_apps_widget_on_home_all),id="apps-on-home") {
            val on=vm.sections.sectionOn(HomeViewModel.HOME_APPS)
            SettingToggle(stringResource(R.string.st_show_apps_on_home),stringResource(R.string.st_the_apps_widget_its_place_among),on){vm.sections.toggleSection(HomeViewModel.HOME_APPS)}
            SettingValueRow(stringResource(R.string.ui_apps_on_home),AppChoices.count(vm.apps.count {it.pkg !in vm.hidden},vm.apps.size)){chooseApps="home"}
            Text(stringResource(R.string.ui_rows_of_apps),fontSize=15.sp)
            ButtonRow(spacing=10.dp) {
                Chip(stringResource(R.string.ui_one_row),selected=vm.homeAppRows==1){vm.updateHomeAppRows(1)}
                Chip(stringResource(R.string.ui_two_rows),selected=vm.homeAppRows==2){vm.updateHomeAppRows(2)}
            }
        }
        if(part==HomeToolsPart.FOLDERS)Section(stringResource(R.string.st_app_folders), stringResource(R.string.st_app_order_visibility_folders_artwork_and), id="app-folders") {
            Chip(stringResource(R.string.st_new_app_folder)){vm.homeFeatureAction="edit:folder-new"}
            vm.personalHomes.current.folders.forEachIndexed {index,f->ButtonRow {
                Chip(stringResource(R.string.st_edit, f.name)){vm.homeFeatureAction="edit:folder:${f.id}"}
                if(index>0)Chip(stringResource(R.string.ui_move_earlier)){vm.personalHomes.update {p->val folders=p.folders.toMutableList();java.util.Collections.swap(folders,index,index-1);p.copy(folders=folders)}}
            }}
            Text(stringResource(R.string.tx_hold_ok_on_an_app_to),color=Mute,fontSize=14.sp)
            if(vm.launcherExtras.options.lockedApps.isNotEmpty())Chip(stringResource(R.string.st_remove_all_app_pin_locks)) {vm.pin.withPin("Enter the administrator PIN"){vm.launcherExtras.update {it.copy(lockedApps=emptySet())}}}
        }
        if(part==HomeToolsPart.FOLDERS)Section(stringResource(R.string.st_apps_page),stringResource(R.string.st_the_page_that_lists_every_app),id="apps-page") {
            val all=vm.appAccess.page(true)
            SettingValueRow(stringResource(R.string.st_apps_on_the_apps_page),AppChoices.count(all.count {it.pkg !in vm.appsPageOff},all.size)){chooseApps="page"}
        }
        if(part==HomeToolsPart.FOLDERS)HiddenAppsSection(vm)
        if(part==HomeToolsPart.STARTUP)Section(stringResource(R.string.st_start_with_the_tv), stringResource(R.string.st_choose_home_an_app_an_input)) {
            // #224: where the TV opens, as pictures; an app or an input is then picked from its own list.
            val now=when(opts.destination){"app"->vm.apps.firstOrNull {it.pkg==opts.target}?.label;"input"->"TV input";else->null}
            PictureChoice("start",StartupChoices.options,StartupChoices.normalize(opts.destination)) {kind->
                when(kind) {
                    "app","input"->chooseStartup=true.also {startupKind=kind}
                    else->vm.launcherExtras.update {it.copy(destination=kind,target="")}
                }
            }
            now?.let {Text(stringResource(R.string.tx_opens, it),color=Mute,fontSize=13.sp)}
            SettingToggle(stringResource(R.string.st_at_startup),null,opts.atBoot){vm.launcherExtras.update {it.copy(atBoot=!it.atBoot)}}
            SettingToggle(stringResource(R.string.st_after_standby),stringResource(R.string.st_wake_launching_requires_ember_to_be),opts.onWake){vm.launcherExtras.update {it.copy(onWake=!it.onWake)}}
        }
        if(part==HomeToolsPart.STARTUP)Section("Back on Home","What the Back button does on Home when nothing is open.",id="back-on-home") {
            Choice(listOf("none" to "Nothing","screensaver" to "Start the screensaver","clock" to "Show a full-screen clock"),opts.homeBack,"Back on Home") {value->vm.launcherExtras.update {it.copy(homeBack=value)}}
            if(app.ember.tv.player.QuickReturn.key(vm.playerSettings.playbackConfig)=="back")
                Text("Pressing Back twice currently returns to your last title, so Back on Home does nothing. Choose another key for quick return in ${SettingsTab.PLAYER.path} to use this.",color=Mute,fontSize=13.sp)
        }
        if(part==HomeToolsPart.EXTRAS)Section(stringResource(R.string.st_presentation), stringResource(R.string.st_loop_photos_and_videos_selected_local)) {
            ButtonRow {
                Chip(stringResource(R.string.st_choose_media_on_tv)){vm.homeFeatureAction="edit:presentation-add"}
                if(opts.slides.isNotEmpty())Chip(stringResource(R.string.st_start_presentation)){if(!vm.profiles.profile.kids)PresentationActivity.start(context) else vm.showNotice("Switch to an adult profile to use presentation mode.")}
            }
            Text(stringResource(R.string.tx_small_photos_and_videos_can_also),color=Mute,fontSize=13.sp)
            SettingToggle(stringResource(R.string.st_keep_screen_awake),stringResource(R.string.st_applies_only_while_the_presentation_is),opts.keepAwake){vm.launcherExtras.update {it.copy(keepAwake=!it.keepAwake)}}
            StepSlider(stringResource(R.string.tx_photo_duration),listOf(5,10,20,30,60),opts.slideSeconds,{"${it}s"}){seconds->vm.launcherExtras.update {it.copy(slideSeconds=seconds)}}
            opts.slides.forEachIndexed {index,item->
                Text("${index+1}. ${item.name}",fontSize=14.sp,maxLines=2)
                ButtonRow {
                    if(index>0)Chip(stringResource(R.string.st_earlier)){vm.launcherExtras.update {p->val rows=p.slides.toMutableList();java.util.Collections.swap(rows,index,index-1);p.copy(slides=rows)}}
                    Chip(stringResource(R.string.cal_remove_clip)) {vm.launcherExtras.update {it.copy(slides=it.slides.filterNot {s->s.id==item.id})};scope.launch(Dispatchers.IO){PresentationMedia.removeUpload(context,item)}}
                }
            }
        }
        if(part==HomeToolsPart.EXTRAS)Section(stringResource(R.string.st_now_playing), stringResource(R.string.st_control_supported_apps_from_light_home)) {
            SettingToggle(stringResource(R.string.st_show_now_playing),null,opts.nowPlaying){vm.launcherExtras.update {it.copy(nowPlaying=!it.nowPlaying)}}
            if(opts.nowPlaying) {
                Text(if(PlaybackAccess.granted(context))"Media control access allowed" else "Media control access needed",color=Mute,fontSize=14.sp)
                Chip(stringResource(R.string.st_allow_media_controls)){runCatching {context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}.onFailure {vm.showNotice("This TV does not expose notification-listener access.")}}
                NowPlayingPanel(vm)
            }
        }
        if(part==HomeToolsPart.EXTRAS)Section("Notifications","Recent notifications from your apps in quick settings. They are never saved or sent anywhere.",id="notifications") {
            SettingToggle("Show notifications in quick settings",null,opts.notifications){vm.launcherExtras.update {it.copy(notifications=!it.notifications)}}
            if(opts.notifications) {
                Text(if(PlaybackAccess.granted(context))"Notification access allowed" else "Notification access needed",color=Mute,fontSize=14.sp)
                if(!PlaybackAccess.granted(context))Chip("Allow notification access"){runCatching {context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}.onFailure {vm.showNotice("This TV does not expose notification access settings. Grant access to \"Ember\" with adb: cmd notification allow_listener app.ember.tv/app.ember.tv.home.PlaybackAccessService")}}
            }
        }
    }
    chooseApps?.let {where->
        val home=where=="home"
        val all=if(home)vm.apps else vm.appAccess.page(true)
        fun shown(pkg:String)=if(home)pkg !in vm.hidden else pkg !in vm.appsPageOff
        fun toggle(pkg:String){if(home)vm.toggleHidden(pkg) else vm.toggleAppsPage(pkg)}
        AppChooserDialog(if(home)"Apps on Home" else "Apps on the Apps page",all,::shown,::toggle){chooseApps=null}
    }
    if(chooseStartup)MediaDialog(if(startupKind=="input")"Start on a TV input" else "Start with an app",{chooseStartup=false},wide=true) {
        fun choose(kind:String,target:String=""){vm.launcherExtras.update {it.copy(destination=kind,target=target)};chooseStartup=false}
        if(startupKind=="input") {
            val inputs=rememberLightInputs(0)
            if(inputs.isEmpty())Text(stringResource(R.string.tx_this_tv_has_no_inputs_to),color=Mute,fontSize=13.sp)
            ButtonRow {inputs.forEach {input->Chip(input.label,selected=vm.launcherExtras.options.target==input.id){choose("input",input.id)}}}
        } else Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            // The app picker's icons, one press to choose.
            vm.appAccess.page(false).sortedBy {it.label.lowercase()}.chunked(AppChoices.PER_ROW).forEach {line->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    line.forEach {a->AppChoiceTile(a,vm.launcherExtras.options.target==a.pkg,Modifier.weight(1f)){choose("app",a.pkg)}}
                    repeat(AppChoices.PER_ROW-line.size){Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}

/** Pick apps from their icons (#210): six to a row; a highlighted app is on, OK switches one. Shared by Home's app pickers and Light Home (#318). */
@Composable internal fun AppChooserDialog(title:String,all:List<AppEntry>,shown:(String)->Boolean,toggle:(String)->Unit,onDone:()->Unit) {
    MediaDialog(title,onDone, wide = true) {
        Text(AppChoices.count(all.count {shown(it.pkg)},all.size)+". Highlighted apps are shown; press OK to switch one.",color=Mute,fontSize=13.sp)
        ButtonRow {
            Chip(stringResource(R.string.st_show_all)){all.forEach {if(!shown(it.pkg))toggle(it.pkg)}}
            Chip(stringResource(R.string.st_hide_all)){all.forEach {if(shown(it.pkg))toggle(it.pkg)}}
            Chip(stringResource(R.string.st_done),onClick=onDone)
        }
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            all.sortedBy {it.label.lowercase()}.chunked(AppChoices.PER_ROW).forEach {line->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    line.forEach {a->AppChoiceTile(a,shown(a.pkg),Modifier.weight(1f)){toggle(a.pkg)}}
                    repeat(AppChoices.PER_ROW-line.size){Spacer(Modifier.weight(1f))}
                }
            }
        }
    }
}

@Composable internal fun NowPlayingPanel(vm:HomeViewModel) {
    val enabled=vm.launcherExtras.options.nowPlaying && !vm.profiles.profile.kids
    val allowed=vm.appAccess.page(false).map {it.pkg}.toSet()
    val current=rememberNowPlaying(enabled,allowed)
    if(!enabled)return
    if(current==null)Text(stringResource(R.string.tx_no_active_supported_media_session),color=Mute,fontSize=14.sp) else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(current.title,fontSize=17.sp,maxLines=2)
        Text(vm.apps.firstOrNull {it.pkg==current.pkg}?.label ?: current.pkg,color=Mute,fontSize=13.sp)
        val actions=current.controller.playbackState?.actions ?: 0L
        ButtonRow {
            fun control(action:()->Unit){if(!vm.profiles.profile.kids && vm.appAccess.canLaunch(current.pkg))runCatching(action).onFailure {vm.showNotice("This session is no longer available.")}}
            if(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS!=0L)Chip(stringResource(R.string.st_previous)){control {current.controller.transportControls.skipToPrevious()}}
            if(actions and (android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)!=0L)Chip(if(current.playing)stringResource(R.string.player_pause) else stringResource(R.string.cal_play_clip)){control {if(current.playing)current.controller.transportControls.pause() else current.controller.transportControls.play()}}
            if(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT!=0L)Chip(stringResource(R.string.st_next)){control {current.controller.transportControls.skipToNext()}}
            Chip(stringResource(R.string.st_open_app)){vm.appAccess.page(false).firstOrNull {it.pkg==current.pkg}?.let {vm.pendingAppLaunch=it}}
        }
    }
}

/**
 * Hidden apps (#134): apps removed from Home or hidden, and Ember Light page tiles that were hidden,
 * come back from here now that Light has no More Apps folder.
 */
@Composable private fun HiddenAppsSection(vm:HomeViewModel) {
    val offHome=vm.hidden;val hiddenApps=vm.appsPageOff
    val apps=vm.appAccess.page(true).filter {it.pkg in offHome || it.pkg in hiddenApps}
    val pages=app.ember.tv.data.LightNavigation.entries(vm.visibleTabs).filter {it.pkg in vm.personalHomes.current.lightNavHidden}
    Section(stringResource(R.string.st_hidden_apps),stringResource(R.string.st_apps_taken_off_home_or_hidden),id="hidden-apps") {
        if(apps.isEmpty() && pages.isEmpty())Text(stringResource(R.string.tx_nothing_is_hidden),color=Mute,fontSize=14.sp)
        apps.forEach {a->HiddenAppRow(a.label,if(a.pkg in hiddenApps)"Hidden" else "Not on Home") {
            if(a.pkg in hiddenApps)vm.toggleAppsPage(a.pkg)
            if(a.pkg in vm.hidden)vm.toggleHidden(a.pkg)
        }}
        pages.forEach {p->HiddenAppRow(p.label,"Ember Light page tile") {vm.toggleLightNavigation(p.pkg)}}
    }
}

@Composable private fun HiddenAppRow(name:String,state:String,show:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name,fontSize=16.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(state,color=Mute,fontSize=13.sp,maxLines=1)
        }
        Chip(stringResource(R.string.st_show),maxLines=1,modifier=Modifier.semantics {contentDescription="Show $name"},onClick=show)
    }
}

/** Recent notifications in quick settings: OK opens one in its app; Clear removes it from the TV. */
@Composable internal fun NotificationsPanel(vm:HomeViewModel,onOpened:()->Unit) {
    val context=LocalContext.current
    val enabled=vm.launcherExtras.options.notifications && !vm.profiles.profile.kids
    val granted=remember(enabled){enabled && PlaybackAccess.granted(context)}
    val revision by NotificationFeed.changes.collectAsState()
    val items=remember(enabled,granted,revision){if(enabled && granted)NotificationFeed.current(context.packageName) else emptyList()}
    if(!enabled)return
    if(!granted){Text("Allow notification access in ${SettingsTab.HOME_EXTRAS.path}.",color=Mute,fontSize=14.sp);return}
    if(items.isEmpty()){Text("No notifications.",color=Mute,fontSize=14.sp);return}
    fun label(pkg:String)=vm.apps.firstOrNull {it.pkg==pkg}?.label ?: pkg
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items.forEach {n->
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(n.title.ifBlank {label(n.pkg)},fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(n.text.isNotBlank())Text(n.text,color=Mute,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(label(n.pkg)+" · "+app.ember.tv.data.DisplayDates.time(n.postedAt),color=Mute,fontSize=12.sp,maxLines=1)
                }
                Chip("Open") {
                    val app=vm.apps.firstOrNull {it.pkg==n.pkg}
                    // The same rules as opening the app: this profile's apps only, and app PIN locks.
                    if(!vm.appAccess.canLaunch(n.pkg)){vm.showNotice("This profile cannot open that app.");return@Chip}
                    if(app!=null && n.pkg in vm.launcherExtras.options.lockedApps){vm.pendingAppLaunch=app;onOpened();return@Chip}
                    val sent=n.open?.let {intent->runCatching {
                        val options=android.app.ActivityOptions.makeBasic()
                        if(android.os.Build.VERSION.SDK_INT>=34)options.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        intent.send(context,0,null,null,null,null,options.toBundle())
                    }.isSuccess} ?: false
                    if(!sent && app!=null)vm.pendingAppLaunch=app
                    if(sent || app!=null)onOpened() else vm.showNotice("This notification can no longer be opened.")
                }
                if(n.clearable)Chip("Clear"){NotificationFeed.dismiss(listOf(n.key))}
            }
        }
        if(items.count {it.clearable}>1)Chip("Clear all"){NotificationFeed.dismiss(items.filter {it.clearable}.map {it.key})}
    }
}
