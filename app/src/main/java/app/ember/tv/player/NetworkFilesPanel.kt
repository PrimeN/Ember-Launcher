package app.ember.tv.player

import app.ember.tv.data.toHex
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.ember.tv.data.Prefs
import app.ember.tv.tracker.TokenStore
import app.ember.tv.ui.TvDialog
import app.ember.tv.ui.DialogHeading
import app.ember.tv.ui.TvTextField
import app.ember.tv.ui.Chip
import app.ember.tv.ui.ButtonRow
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
internal fun NetworkFilesPanel(activity:MediaFilesActivity) {
    val scope=rememberCoroutineScope();val json=remember {Json {ignoreUnknownKeys=true}}
    val profile=remember {Prefs(activity).currentProfile}
    val key=remember {"media.network."+profile}
    val shortcutStore=remember {app.ember.tv.data.HomeShortcutStore(activity)}
    var pins by remember {mutableStateOf<List<app.ember.tv.data.HomeShortcut>>(emptyList())}
    var accounts by remember {mutableStateOf<List<NetworkAccount>>(emptyList())}
    var edit by remember {mutableStateOf(false)}
    var browse by remember {mutableStateOf<NetworkAccount?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(key) {
        accounts=withContext(Dispatchers.IO){runCatching {json.decodeFromString<List<NetworkAccount>>(TokenStore(activity).get(key)?:"[]")}.getOrDefault(emptyList())}
        pins=withContext(Dispatchers.IO){shortcutStore.read(profile)}
        if(activity.intent.getStringExtra("network.profile")==profile) {
            val id=activity.intent.getStringExtra("network.account")
            if(id!=null){browse=accounts.firstOrNull {it.id==id};if(browse==null)error="This saved folder was removed. Add it again to use this shortcut."}
        }
    }
    Text("Network folders",fontSize=20.sp)
    LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        item {Chip("Add network folder"){edit=true}}
        items(accounts,key={it.id}) {a->Chip(a.label){browse=a}}
    }
    error?.let {Text(it)}
    if(edit) {
        var label by remember {mutableStateOf("")};var address by remember {mutableStateOf("")}
        var user by remember {mutableStateOf("")};var password by remember {mutableStateOf("")}
        var domain by remember {mutableStateOf("")};var fingerprint by remember {mutableStateOf("")}
        var unencryptedSmb by remember {mutableStateOf(false)}
        var saving by remember {mutableStateOf(false)};var note by remember {mutableStateOf<String?>(null)}
        TvDialog({if(!saving)edit=false},width=540.dp,resizeForKeyboard=true) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f,false),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {DialogHeading("Add network folder")}
                item {Text("SMB3 (encrypted): smb://server/share · SFTP: sftp://server/folder\nWebDAV: https://server/folder · FTPS: ftps://server/folder\nPlain FTP and HTTP do not encrypt network traffic.",fontSize=13.sp)}
                item {NetworkField("Name",label){label=it}}
                item {NetworkField("Address",address){address=it}}
                item {NetworkField("Username",user){user=it}}
                item {NetworkField("Password",password,true){password=it}}
                item {NetworkField("SMB domain (optional)",domain){domain=it}}
                item {NetworkField("SFTP verified SHA256 fingerprint",fingerprint){fingerprint=it}}
                if(address.trim().startsWith("smb://",ignoreCase=true))item {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Chip(if(unencryptedSmb)"SMB encryption: not required" else "SMB encryption: required",selected=unencryptedSmb) {unencryptedSmb=!unencryptedSmb}
                        if(unencryptedSmb)Text("For older NAS devices without SMB3 encryption. Others on your network could see the files you play; signing still stops them being altered.",fontSize=13.sp)
                    }
                }
                note?.let {item {Text(it)}}
                item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Chip(if(saving)"Saving…" else "Save") {if(!saving)scope.launch {
                        note=null
                        val valid=runCatching {val l=NetworkLocation.parse(address);require(label.isNotBlank());require(user.none {it.isISOControl()});if(l.scheme=="sftp")require(fingerprint.startsWith("SHA256:"));NetworkAccount(label=label.trim().take(80),address=address.trim(),username=user,password=password,domain=domain,fingerprint=fingerprint.trim(),allowUnencryptedSmb=unencryptedSmb&&l.scheme=="smb")}.getOrNull()
                        if(valid==null){note="Check the name, address and SFTP host fingerprint.";return@launch}
                        saving=true
                        val updated=accounts+valid
                        val saved=withContext(Dispatchers.IO){TokenStore(activity).putEncrypted(key,json.encodeToString(updated))}
                        saving=false
                        if(saved){accounts=updated;edit=false}else note="Encrypted storage is unavailable. The folder was not saved."
                    }}
                    Chip("Cancel"){if(!saving)edit=false}
                }}
            }
        }
    }
    browse?.let {account ->
        var path by remember(account.id) {mutableStateOf(if(activity.intent.getStringExtra("network.account")==account.id && activity.intent.getStringExtra("network.profile")==profile)activity.intent.getStringExtra("network.path").orEmpty() else "")};var entries by remember {mutableStateOf<List<NetworkEntry>>(emptyList())}
        var selected by remember {mutableStateOf<Set<String>>(emptySet())};var busy by remember {mutableStateOf(true)}
        var note by remember {mutableStateOf<String?>(null)}
        var testJob by remember {mutableStateOf<Job?>(null)}
        var probeNote by remember(account.id,path) {mutableStateOf<String?>(null)}
        DisposableEffect(account.id,path) {onDispose {testJob?.cancel()}}
        val shortcut=app.ember.tv.data.HomeShortcut("network",if(path.isEmpty())account.label else path.substringAfterLast('/'),account.id,path)
        var offset by remember(account.id,path) {mutableIntStateOf(0)}
        var next by remember(account.id,path) {mutableStateOf<Int?>(null)}
        var reload by remember {mutableIntStateOf(0)}
        LaunchedEffect(account.id,path,offset,reload) {
            busy=true;note=null;entries=emptyList();selected=emptySet()
            try {val context=currentCoroutineContext()
                val page=withContext(Dispatchers.IO){NetworkFiles.listPage(account,path,offset){context.ensureActive()}}
                ensureActive();entries=page.entries;next=page.next}
            catch(e:CancellationException){throw e}
            catch(_:ListingLimitException){note="This directory listing is too large. Open a smaller folder."}
            catch(_:Exception){note="Could not open this folder. Check its address, credentials, permissions and host key."}
            finally {busy=false}
        }
        fun play(all:Boolean) {
            val chosen=entries.filter {!it.directory&&it.size>0&&(if(all)MediaFileTypes.playable(it) else it.name in selected)}.take(200)
            if(chosen.isEmpty())return
            var cleanup:MediaRelay?=null
            try {
                val relay=MediaRelay().also {cleanup=it}
                val items=chosen.map {e->
                    val relative=NetworkLocation.child(path,e.name)
                    val identity="network:"+java.security.MessageDigest.getInstance("SHA-256").digest((account.id+":"+relative).toByteArray()).toHex()
                    QueueMedia(relay.register(NetworkFiles.resource(account,relative,e.size),e.name),e.name,identity)
                }
                MediaFiles.launch(activity,items,listOf(relay))
            } catch(_:Exception) {cleanup?.close();note="Could not start file playback."}
        }
        TvDialog({browse=null},width=620.dp) {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                DialogHeading(account.label,Modifier.weight(1f))
                app.ember.tv.ui.DialogClose({browse=null})
            }
            Text(if(path.isEmpty())"Root folder" else path,fontSize=13.sp)
            ButtonRow(spacing=8.dp) {
                if(path.isNotEmpty())Chip("Up"){path=path.substringBeforeLast('/',"")}
                Chip("Play selected (${selected.size})"){play(false)}
                Chip("Play media on page"){play(true)}
            }
            ButtonRow(spacing=8.dp) {
                if(offset>0)Chip("Previous page"){offset=(offset-200).coerceAtLeast(0)}
                next?.let {at->Chip("Next page"){if(!busy)offset=at}}
                Chip("Refresh"){if(!busy)reload++}
                Chip(if(pins.any {it.id==shortcut.id})"Unpin from Home" else "Pin to Home") {scope.launch {
                    val saved=withContext(Dispatchers.IO){shortcutStore.toggle(profile,shortcut)}
                    if(saved==app.ember.tv.data.ShortcutWrite.SAVED)pins=withContext(Dispatchers.IO){shortcutStore.read(profile)}
                    else note=saved.message
                }}
            }
            ButtonRow(spacing=8.dp) {
                Chip(if(testJob?.isActive==true)"Cancel test" else "Test connection") {
                    if(testJob?.isActive==true){testJob?.cancel();probeNote="Test cancelled."}
                    else {
                        val sample=entries.firstOrNull {MediaFileTypes.playable(it)}
                        if(sample==null)probeNote="Open a folder containing a media file to test its connection."
                        else testJob=scope.launch {
                            probeNote="Reading up to 8 MiB. No file is saved…"
                            try {
                                val result=ConnectionProbe.run(NetworkFiles.resource(account,NetworkLocation.child(path,sample.name),sample.size))
                                probeNote=String.format(java.util.Locale.US,"%.1f Mbps average · %.1f MiB sampled · first data %d ms",result.averageMbps,result.bytes/(1024.0*1024),result.firstByteMs)+
                                    (result.slowestMbps?.let {String.format(java.util.Locale.US," · slowest sample %.1f Mbps",it)}?:"")+". Short sample; actual playback may vary."
                            } catch(_:TimeoutCancellationException){probeNote="Test timed out. Check the connection and try again."}
                              catch(e:CancellationException){throw e}
                              catch(_:Exception){probeNote="Could not read the sample. Check server access and connection."}
                        }
                    }
                }
            }
            probeNote?.let {Text(it,fontSize=12.sp)}
            if(busy)Text("Opening folder…")
            else Text("${entries.size} entries · page ${offset/200+1}",fontSize=12.sp)
            note?.let {Text(it)}
            LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                items(entries,key={it.name}) {entry->
                    Chip((if(entry.directory)"Folder · " else if(entry.name in selected)"✓ " else "")+entry.name) {
                        if(entry.directory)path=NetworkLocation.child(path,entry.name)
                        else selected=if(entry.name in selected)selected-entry.name else selected+entry.name
                    }
                }
            }
            Chip("Remove saved folder") {scope.launch {
                val updated=accounts.filterNot {it.id==account.id}
                if(withContext(Dispatchers.IO){TokenStore(activity).putEncrypted(key,json.encodeToString(updated))}){accounts=updated;browse=null}
                else note="Could not update encrypted folder storage."
            }}

        }
    }
}
@Composable
private fun NetworkField(label:String,value:String,secret:Boolean=false,change:(String)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,fontSize=13.sp)
        TvTextField(value,change,Modifier.fillMaxWidth(),placeholder=label,password=secret)
    }
}
