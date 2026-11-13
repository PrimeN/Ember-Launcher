package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.AppEntry
import app.ember.tv.home.AppArtImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HomeFeatureDialogs(vm:HomeViewModel,onLaunch:(AppEntry)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var importFor by rememberSaveable {mutableStateOf<Pair<String,String>?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        val target=importFor;importFor=null
        if(uri!=null && target!=null)scope.launch {
            val result=withContext(Dispatchers.IO){runCatching {AppArtImport.read(context,uri)}}
            if(vm.profiles.profileId==target.first && vm.profiles.allowsApp(target.second))result.fold({art ->
                vm.openSettingsGuarded {if(vm.profiles.profileId==target.first) {
                    if(vm.personalHomes.canStoreArtwork(target.second,art))vm.personalHomes.update {it.copy(artwork=it.artwork+(target.second to art))}
                    else vm.showNotice("Custom artwork storage is full. Restore some original artwork before adding more.")
                }}
            },{vm.showNotice(it.message ?: "Could not import artwork.")})
        }
    }
    var presentationOwner by rememberSaveable {mutableStateOf<String?>(null)}
    val mediaPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {uris->
        val owner=presentationOwner;presentationOwner=null
        if(owner!=null && owner==vm.profiles.profileId && !vm.profiles.profile.kids)scope.launch {
            val existing=vm.launcherExtras.options.slides.map {it.uri}.toSet()
            val room=(40-vm.launcherExtras.options.slides.size).coerceAtLeast(0)
            val candidates=uris.distinct().filter {it.toString() !in existing}.take(room)
            val imported=withContext(Dispatchers.IO){app.ember.tv.home.PresentationMedia.documents(context,candidates)}
            if(vm.profiles.profileId==owner && !vm.profiles.profile.kids) {
                if(uris.isNotEmpty() && imported.isEmpty())vm.showNotice(if(room==0)"The presentation playlist is full." else "No new supported files could be added.")
                else if(imported.size<uris.size)vm.showNotice("Added ${imported.size} items. The playlist holds 40 items; unsupported or duplicate files were skipped.")
                vm.openSettingsGuarded {if(vm.profiles.profileId==owner)vm.launcherExtras.update {it.copy(slides=(it.slides+imported).distinctBy {s->s.uri})}}
            }
        }
    }
    val action=vm.homeFeatureAction ?: return
    val close={vm.homeFeatureAction=null}
    key(action,vm.profiles.profileId) {
        when {
            action=="edit:presentation-add"->LaunchedEffect(action) {
                presentationOwner=vm.profiles.profileId;close()
                if(vm.profiles.profile.kids)vm.showNotice("Switch to an adult profile to add presentation media.")
                else runCatching {mediaPicker.launch(arrayOf("image/*","video/*"))}.onFailure {presentationOwner=null;vm.showNotice("Install a document picker to select media.")}
            }
            action.startsWith("edit:art:") -> LaunchedEffect(action) {
                val pkg=action.removePrefix("edit:art:")
                importFor=vm.profiles.profileId to pkg;close()
                runCatching {picker.launch(arrayOf("image/*"))}.onFailure {importFor=null;vm.showNotice("Install a document picker to choose an image.")}
            }
            action.startsWith("edit:folder-new") || action.startsWith("edit:folder:") -> {
                val existing=vm.personalHomes.current.folders.firstOrNull {it.id==action.removePrefix("edit:folder:")}
                var name by remember {mutableStateOf(existing?.name.orEmpty())}
                var selected by remember {mutableStateOf(existing?.packages?.toSet() ?: action.substringAfter("edit:folder-new:","").takeIf {vm.appAccess.canLaunch(it)}?.let {setOf(it)}.orEmpty())}
                MediaDialog(if(existing==null)"New app folder" else "Edit app folder",close) {
                    TvTextField(name,{name=it.take(40)},placeholder="Folder name")
                    ButtonRow {
                        Chip("Save") {runCatching {vm.personalHomes.putFolder(existing?.id,name,selected.filter {vm.appAccess.canLaunch(it)})}
                            .onSuccess {close()}.onFailure {vm.showNotice(it.message ?: "Choose a name.")} }
                        if(existing!=null)Chip("Remove folder") {vm.personalHomes.removeFolder(existing.id);close()}
                    }
                    vm.appAccess.page(false).forEach {app -> SettingToggle(app.label,null,app.pkg in selected) {selected=if(app.pkg in selected)selected-app.pkg else selected+app.pkg} }
                }
            }
            action.startsWith("edit:assign:") -> {
                val pkg=action.removePrefix("edit:assign:")
                MediaDialog("Choose an app folder",close) {
                    Chip("Keep outside folders") {vm.personalHomes.assign(pkg,null);close()}
                    vm.personalHomes.current.folders.forEach {folder -> Chip(folder.name,selected=pkg in folder.packages) {vm.personalHomes.assign(pkg,folder.id);close()} }
                    Chip("Create a folder") {vm.homeFeatureAction="edit:folder-new:$pkg"}
                }
            }
            action.startsWith("folder:") -> {
                val folder=vm.personalHomes.current.folders.firstOrNull {it.id==action.removePrefix("folder:")}
                if(folder==null)LaunchedEffect(Unit){close()} else MediaDialog(folder.name,close) {
                    val apps=vm.appAccess.page(true).filter {it.pkg in folder.packages}
                    if(apps.isEmpty())Text(stringResource(R.string.tx_no_available_apps_in_this_folder))
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(8.dp)) {
                        items(apps,key={it.pkg}){app->AppTile(app,onClick={close();onLaunch(app)},onLongClick={close();vm.openSettingsGuarded {vm.homeFeatureAction="edit:assign:${app.pkg}"}})}
                    }
                    Chip("Edit folder") {vm.openSettingsGuarded {vm.homeFeatureAction="edit:folder:${folder.id}"}}
                }
            }
        }
    }
}

@Composable internal fun HomeFolderLinks(vm:HomeViewModel) {
    val available=vm.personalHomes.current.folders.filter {f->f.packages.any(vm.appAccess::canLaunch)}
    if(available.isNotEmpty())ButtonRow {available.forEach {f->
        Focusable(onClick={vm.homeFeatureAction="folder:${f.id}"},radius=TvTokens.Shape.Panel,container=Wash,focusedContainer=PillColor,focusedContent=OnPillColor) {
            Row(Modifier.padding(horizontal=14.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                androidx.tv.material3.Icon(Icons.Outlined.Folder,null,Modifier.size(18.dp));Text(f.name,fontSize=14.sp,maxLines=1)
            }
        }
    }}
}
