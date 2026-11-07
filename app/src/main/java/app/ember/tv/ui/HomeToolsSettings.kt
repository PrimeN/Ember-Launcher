package app.ember.tv.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.home.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home tools are split across Settings > Home: Apps & folders, Startup and Extras. */
internal enum class HomeToolsPart { FOLDERS, STARTUP, EXTRAS }

@Composable internal fun HomeToolsPage(vm:HomeViewModel, part:HomeToolsPart) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val opts=vm.launcherExtras.options
    var chooseStartup by remember {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(22.dp)) {
        if(part==HomeToolsPart.FOLDERS)Section("Personal Home", "App order, visibility, folders, artwork and Light choices are saved separately for each profile.") {
            Chip("New app folder"){vm.homeFeatureAction="edit:folder-new"}
            vm.personalHomes.current.folders.forEachIndexed {index,f->ButtonRow {
                Chip("Edit ${f.name}"){vm.homeFeatureAction="edit:folder:${f.id}"}
                if(index>0)Chip("Move earlier"){vm.personalHomes.update {p->val folders=p.folders.toMutableList();java.util.Collections.swap(folders,index,index-1);p.copy(folders=folders)}}
            }}
            Text("Hold OK on an app to choose artwork, move it to a folder or set a PIN lock.",color=Mute,fontSize=14.sp)
            if(vm.launcherExtras.options.lockedApps.isNotEmpty())Chip("Remove all app PIN locks") {vm.pin.withPin("Enter the administrator PIN"){vm.launcherExtras.update {it.copy(lockedApps=emptySet())}}}
        }
        if(part==HomeToolsPart.STARTUP)Section("Start with the TV", "Choose Home, an app, an input or a presentation. Child profiles stay on Home.") {
            val name=when(opts.destination){"app"->vm.apps.firstOrNull {it.pkg==opts.target}?.label ?: "App";"input"->"TV input";"presentation"->"Presentation";else->"Home"}
            SettingValueRow("Destination",name){chooseStartup=true}
            SettingToggle("At startup",null,opts.atBoot){vm.launcherExtras.update {it.copy(atBoot=!it.atBoot)}}
            SettingToggle("After standby","Wake launching requires Ember to be the active Home, or its Home button service to be enabled.",opts.onWake){vm.launcherExtras.update {it.copy(onWake=!it.onWake)}}
        }
        if(part==HomeToolsPart.EXTRAS)Section("Now Playing", "Control supported apps from Light Home or the quick menu. No listening history is stored.") {
            SettingToggle("Show Now Playing",null,opts.nowPlaying){vm.launcherExtras.update {it.copy(nowPlaying=!it.nowPlaying)}}
            if(opts.nowPlaying) {
                Text(if(PlaybackAccess.granted(context))"Media control access allowed" else "Media control access needed",color=Mute,fontSize=14.sp)
                Chip("Allow media controls"){runCatching {context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}.onFailure {vm.showNotice("This TV does not expose notification-listener access.")}}
                NowPlayingPanel(vm)
            }
        }
        if(part==HomeToolsPart.EXTRAS)Section("Presentation", "Loop photos and videos. Selected local files must remain available; settings backups keep the playlist but do not copy large media files.") {
            ButtonRow {
                Chip("Choose media on TV"){vm.homeFeatureAction="edit:presentation-add"}
                if(opts.slides.isNotEmpty())Chip("Start presentation"){if(!vm.profiles.profile.kids)PresentationActivity.start(context) else vm.showNotice("Switch to an adult profile to use presentation mode.")}
            }
            Text("Small photos and videos can also be sent through the existing 15-minute Phone setup session, with approval on the TV.",color=Mute,fontSize=13.sp)
            SettingToggle("Keep screen awake","Applies only while the presentation is open.",opts.keepAwake){vm.launcherExtras.update {it.copy(keepAwake=!it.keepAwake)}}
            Text("Photo duration",fontSize=15.sp)
            ButtonRow {listOf(5,10,20,30,60).forEach {seconds->Chip("${seconds}s",selected=opts.slideSeconds==seconds){vm.launcherExtras.update {it.copy(slideSeconds=seconds)}}}}
            opts.slides.forEachIndexed {index,item->
                Text("${index+1}. ${item.name}",fontSize=14.sp,maxLines=2)
                ButtonRow {
                    if(index>0)Chip("Earlier"){vm.launcherExtras.update {p->val rows=p.slides.toMutableList();java.util.Collections.swap(rows,index,index-1);p.copy(slides=rows)}}
                    Chip("Remove") {vm.launcherExtras.update {it.copy(slides=it.slides.filterNot {s->s.id==item.id})};scope.launch(Dispatchers.IO){PresentationMedia.removeUpload(context,item)}}
                }
            }
        }
    }
    if(chooseStartup)MediaDialog("Startup destination",{chooseStartup=false}) {
        fun choose(kind:String,target:String=""){vm.launcherExtras.update {it.copy(destination=kind,target=target)};chooseStartup=false}
        Chip("Home"){choose("home")};Chip("Presentation"){choose("presentation")}
        val inputs=rememberLightInputs(0)
        inputs.forEach {input->Chip(input.label){choose("input",input.id)}}
        vm.appAccess.page(false).forEach {app->Chip(app.label){choose("app",app.pkg)}}
    }
}

@Composable internal fun NowPlayingPanel(vm:HomeViewModel) {
    val enabled=vm.launcherExtras.options.nowPlaying && !vm.profiles.profile.kids
    val allowed=vm.appAccess.page(false).map {it.pkg}.toSet()
    val current=rememberNowPlaying(enabled,allowed)
    if(!enabled)return
    if(current==null)Text("No active supported media session.",color=Mute,fontSize=14.sp) else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(current.title,fontSize=17.sp,maxLines=2)
        Text(vm.apps.firstOrNull {it.pkg==current.pkg}?.label ?: current.pkg,color=Mute,fontSize=13.sp)
        val actions=current.controller.playbackState?.actions ?: 0L
        ButtonRow {
            fun control(action:()->Unit){if(!vm.profiles.profile.kids && vm.appAccess.canLaunch(current.pkg))runCatching(action).onFailure {vm.showNotice("This session is no longer available.")}}
            if(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS!=0L)Chip("Previous"){control {current.controller.transportControls.skipToPrevious()}}
            if(actions and (android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE)!=0L)Chip(if(current.playing)"Pause" else "Play"){control {if(current.playing)current.controller.transportControls.pause() else current.controller.transportControls.play()}}
            if(actions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT!=0L)Chip("Next"){control {current.controller.transportControls.skipToNext()}}
            Chip("Open app"){vm.appAccess.page(false).firstOrNull {it.pkg==current.pkg}?.let {vm.pendingAppLaunch=it}}
        }
    }
}
