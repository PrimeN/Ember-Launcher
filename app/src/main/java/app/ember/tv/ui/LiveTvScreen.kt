package app.ember.tv.ui

import app.ember.tv.isSaved

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta
import app.ember.tv.iptv.*
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType

/** Playlists are collapsed until chosen; only the selected channel list is rendered. */
@Composable
fun IptvLiveScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit, onPlayChannel: (List<Channel>, Int) -> Unit) {
    val iptv = vm.iptv
    val sidebarFocus = remember { FocusRequester() }
    val fallbackFocus = remember { FocusRequester() }
    var repairFocus by remember { mutableStateOf(false) }
    LaunchedEffect(iptv) { while (true) { iptv.tick(); delay(60_000) } }
    val choiceSaver = listSaver<LiveChoice, String>(
        save = { listOf(it.kind, it.source.orEmpty(), it.group.orEmpty(), it.list.orEmpty(), (it.group == null).toString()) },
        restore = { LiveChoice(it[0], it[1].ifEmpty { null }, if (it[4] == "true") null else it[2], it[3].ifEmpty { null }) },
    )
    var choice by rememberSaveable(vm.profiles.profileId, stateSaver = choiceSaver) { mutableStateOf(LiveChoice()) }
    var expanded by rememberSaveable(vm.profiles.profileId) { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable(vm.profiles.profileId) { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(vm.iptvSetup.iptvView) }
    var focused by remember { mutableStateOf<Channel?>(null) }
    var focusedProgramme by remember {mutableStateOf<Programme?>(null)}
    var focusedTime by remember {mutableLongStateOf(iptv.clock)}
    val bodyFocus=remember {FocusRequester()}
    val emptyFocus=remember {FocusRequester()}
    var inlineFeaturedAvailable by remember {mutableStateOf(true)}
    var guideEngaged by rememberSaveable(vm.profiles.profileId) {mutableStateOf(false)}
    var searchOpen by remember {mutableStateOf(false)}
    var programmeSearch by remember(vm.profiles.profileId) {mutableStateOf(false)}
    var guideJump by remember {mutableStateOf<Long?>(null)}
    val featuredFocus=remember {FocusRequester()}
    var featureReturn by remember {mutableStateOf(false)}
    var sidebarReturn by remember {mutableStateOf<FocusRequester?>(null)}
    val screenScope=rememberCoroutineScope()
    var options by remember { mutableStateOf<Channel?>(null) }
    var manage by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var editList by remember { mutableStateOf<ChannelList?>(null) }
    var sidebarOpen by rememberSaveable(vm.profiles.profileId) { mutableStateOf(true) }
    var searchAll by rememberSaveable(vm.profiles.profileId) { mutableStateOf(false) }
    var onNowOpen by remember { mutableStateOf(false) }
    var programmeInfo by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    var entryKey by remember { mutableStateOf<String?>(null) }
    var entryVersion by remember { mutableIntStateOf(0) }
    val savedViews = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var schedule by remember { mutableStateOf<Channel?>(null) }
    var seedChannel by remember { mutableStateOf<String?>(null) }
    var guideTools by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var guideChannel by remember(vm.profiles.profileId) { mutableStateOf<Channel?>(null) }
    var displayOptions by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var moreOpen by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var digits by remember(vm.profiles.profileId) { mutableStateOf("") }
    var numberMessage by remember(vm.profiles.profileId) { mutableStateOf<String?>(null) }
    var numberChoices by remember(vm.profiles.profileId) { mutableStateOf<List<Channel>>(emptyList()) }
    val available = iptv.visibleChannels
    val availableByKey=remember(available) {available.associateBy {it.key}}
    val sourceGroups = remember(iptv.channels) {
        iptv.channels.groupBy { it.sourceId }.mapValues { (_, channels) -> channels.map { it.group }.distinct() }
    }
    val searching = query.trim().length >= 2
    val activeChoice=if(choice.kind=="none")LivePresentation.initialChoice(available,iptv.sources,iptv.library,iptv.favourites,vm.liveRows.isNotEmpty()) else choice
    val base = remember(activeChoice, available, iptv.library, iptv.favourites, iptv.recent) {
        activeChoice.channels(available, iptv.library, iptv.favourites, iptv.recent)
    }
    val selected = remember(activeChoice, base, available, query, searchAll) {
        if (searching) (if (searchAll || activeChoice.kind == "none") available else base).filter { it.name.contains(query.trim(), true) } else base
    }
    LaunchedEffect(selected,guideEngaged) {
        if(guideEngaged) {
            val next=selected.firstOrNull {it.key==focused?.key} ?: selected.firstOrNull()
            if(next?.key!=focused?.key) {focused=next;focusedProgramme=next?.let {iptv.nowNext(it).first};focusedTime=iptv.clock}
            else if(next!=focused)focused=next
        }
    }
    LaunchedEffect(featureReturn,guideEngaged) {
        if(featureReturn && !guideEngaged) {withFrameNanos { }
            if(!runCatching {featuredFocus.requestFocus()}.getOrDefault(false))runCatching {fallbackFocus.requestFocus()}
            featureReturn=false
        }
    }
    LaunchedEffect(iptv.guideReturnKey) {
        iptv.guideReturnKey?.let { key ->
            available.firstOrNull { it.key == key }?.let { channel ->
                if (base.none { it.key == key }) choice = LiveChoice("source", channel.sourceId)
                query = ""; searchAll = false; view = "grid"; vm.iptvSetup.updateIptvView(view)
                focused = channel; entryKey = key; entryVersion++; guideEngaged=true
            }
            iptv.guideReturned()
        }
    }
    LaunchedEffect(iptv.library, iptv.sources, iptv.channels, choice) {
        val repaired = choice.repaired(iptv.sources, iptv.library, iptv.channels)
        if (repaired != choice) { choice = repaired; repairFocus = true }
        expanded = expanded.filter { id -> iptv.sources.any { it.id == id && it.enabled } }
    }
    LaunchedEffect(repairFocus, manage, naming, options) {
        if (repairFocus && !manage && !naming && options == null) {
            withFrameNanos { }
            if (!runCatching { sidebarFocus.requestFocus() }.getOrDefault(false)) runCatching { fallbackFocus.requestFocus() }
            repairFocus = false
        }
    }


    fun choose(next:LiveChoice,clearSearch:Boolean=true) {
        choice=next;guideEngaged=true
        if(clearSearch)query=""
    }
    fun enterGuide() {guideEngaged=true;entryKey=null;entryVersion++}
    fun returnSidebar():Boolean {
        screenScope.launch {sidebarOpen=true;withFrameNanos { }
            if(!runCatching {sidebarReturn?.requestFocus()==true}.getOrDefault(false))runCatching {sidebarFocus.requestFocus()}
        }
        return true
    }
    fun returnFeatured():Boolean {
        if(!liveFeaturedAvailable(inlineFeaturedAvailable,iptv.library.showOnNow,available.isNotEmpty(),searching,activeChoice.kind))return false
        guideEngaged=false;featureReturn=true;return true
    }
    fun play(list: List<Channel>, index: Int) {
        // The release that opens channel options must not tune the underlying card.
        if(options!=null)return
        if (index !in list.indices) return
        val authorized = list.mapNotNull { old -> availableByKey[old.key] }
        val at = authorized.indexOfFirst { it.key == list[index].key }
        if (at < 0) return
        iptv.played(authorized[at])
        onPlayChannel(authorized, at)
    }
    LaunchedEffect(digits, choice, available, selected, vm.profiles.profileId) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1200)
        val pool = if (activeChoice.kind == "none") available else selected
        val matches = LiveRemote.matches(pool, digits)
        val entered = digits
        digits = ""
        when (matches.size) {
            0 -> numberMessage = "No channel $entered in this list."
            1 -> play(pool, pool.indexOfFirst { it.key == matches.single().key })
            else -> numberChoices = matches
        }
    }

    val sourceCounts=remember(available) {available.groupingBy {it.sourceId}.eachCount()}
    val groupCounts=remember(available) {available.groupingBy {it.sourceId to it.group}.eachCount()}
    val visibleKeys=remember(available) {available.mapTo(hashSetOf()) {it.key}}
    val favouriteCount=remember(available,iptv.favourites) {available.count {it.key in iptv.favourites}}
    val recentCount=remember(visibleKeys,iptv.recent) {iptv.recent.distinct().count {it in visibleKeys}}
    val listCounts=remember(visibleKeys,iptv.library.lists) {iptv.library.lists.associate {it.id to it.channelKeys.distinct().count {key->key in visibleKeys}}}
    val scopeName=when(activeChoice.kind) {
        "source"->iptv.sources.firstOrNull {it.id==activeChoice.source}?.name.orEmpty()+(activeChoice.group?.let {" · $it"} ?: "")
        "favourites"->"Favourites";"recent"->"Recently watched"
        "custom"->iptv.library.lists.firstOrNull {it.id==activeChoice.list}?.name.orEmpty()
        "addons"->"Live catalogs";else->"Your channels"
    }
    var backdrop by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(focusedProgramme?.image,guideEngaged) {delay(180);backdrop=if(guideEngaged)focusedProgramme?.image else null}
    LivePageTheme {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sidebarWidth=(maxWidth*.235f).coerceIn(148.dp,226.dp)
        val fontScale=androidx.compose.ui.platform.LocalDensity.current.fontScale
        val viewportHeight=maxHeight
        val allowInline=viewportHeight>=360.dp && fontScale<=1.25f
        SideEffect {inlineFeaturedAvailable=allowInline}
        Box(Modifier.fillMaxSize()) {
            LiveArtworkBackground(backdrop)
            Column(Modifier.fillMaxSize().padding(start=36.dp,end=24.dp,top=4.dp).onKeyEvent {event ->
                val digit=LiveRemote.digit(event.nativeKeyEvent.keyCode)
                if(digit==null)false else {
                    if(event.type==KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount==0 && digits.length<6){digits+=digit;numberMessage=null};true
                }
            }) {
                Row(Modifier.fillMaxWidth().padding(bottom=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("Live TV",color=LiveColors.Text,fontSize=21.sp,fontWeight=FontWeight.Medium)
                        Text(when {
                            searching -> "Search · ${selected.size} channels"
                            iptv.loading -> "Updating channels…"
                            activeChoice.kind=="addons" -> "$scopeName · ${vm.liveRows.size} catalogs"
                            else -> "$scopeName · ${selected.size} channels"
                        },color=LiveColors.Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    FrostedPill {
                        Row(Modifier.navigationGlass().padding(4.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            LiveToolbarAction(if(sidebarOpen)"Hide playlists" else "Show playlists",Icons.Outlined.Menu,selected=sidebarOpen) {sidebarOpen=!sidebarOpen}
                            LiveToolbarAction("Search channels",Icons.Outlined.Search,Modifier.focusRequester(fallbackFocus),selected=searching) {if(activeChoice.kind=="addons")searchAll=true;searchOpen=true}
                            if(activeChoice.kind!="addons")LiveToolbarAction(if(view=="grid")"Show channel list" else "Show programme guide",
                                if(view=="grid")Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridOn) {
                                view=if(view=="grid")"list" else "grid";vm.iptvSetup.updateIptvView(view);enterGuide()
                            }
                            LiveToolbarAction("Live TV options",Icons.Outlined.MoreHoriz) {moreOpen=true}
                        }
                    }
                }
                if(digits.isNotEmpty())Text("Channel $digits",color=LiveColors.Text,fontSize=18.sp)
                numberMessage?.let {Text(it,color=LiveColors.Muted,fontSize=12.sp)}
                if(iptv.message!=null && available.isEmpty())Text(iptv.message.orEmpty(),color=LiveColors.Muted,fontSize=13.sp)
                if(searching)Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("“$query” · "+if(searchAll)"All enabled playlists" else scopeName,color=LiveColors.Muted,fontSize=12.sp,modifier=Modifier.weight(1f))
                    ToolbarIcon("Clear search",Icons.Outlined.Close) {query="";searchAll=false}
                }
                if(!guideEngaged && liveFeaturedAvailable(allowInline,iptv.library.showOnNow,available.isNotEmpty(),searching,activeChoice.kind)) {
                    LiveNowRow(iptv,onOptions={options=it},onFocus={focused=it;focusedProgramme=iptv.nowNext(it).first},firstFocus=featuredFocus) {channels,index->play(channels,index)}
                }
                Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(22.dp)) {
                    if(sidebarOpen)LazyColumn(Modifier.width(sidebarWidth).fillMaxHeight().focusRequester(sidebarFocus).focusGroup(),
                        verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                        if("favourites" !in iptv.library.hiddenSections || "recent" !in iptv.library.hiddenSections || iptv.library.lists.isNotEmpty())item("your-channels") {LiveSidebarHeading("Your channels")}
                        if("favourites" !in iptv.library.hiddenSections)item("favourites") {
                            LiveSidebarEntry("Favourites",favouriteCount,Icons.Outlined.Star,selected=activeChoice.kind=="favourites",
                                onFocused={sidebarReturn=it;choose(LiveChoice("favourites"),false)},onEnterGuide=::enterGuide,onClick={choose(LiveChoice("favourites"));enterGuide()})
                        }
                        if("recent" !in iptv.library.hiddenSections)item("recent") {
                            LiveSidebarEntry("Recently watched",recentCount,Icons.Outlined.History,selected=activeChoice.kind=="recent",
                                onFocused={sidebarReturn=it;choose(LiveChoice("recent"),false)},onEnterGuide=::enterGuide,onClick={choose(LiveChoice("recent"));enterGuide()})
                        }
                        items(iptv.library.lists.filter {"custom:${it.id}" !in iptv.library.hiddenSections},key={"custom:${it.id}"}) {list->
                            LiveSidebarEntry(list.name,listCounts[list.id]?:0,Icons.Outlined.Folder,selected=activeChoice.kind=="custom" && activeChoice.list==list.id,
                                onFocused={sidebarReturn=it;choose(LiveChoice("custom",list=list.id),false)},onEnterGuide=::enterGuide,
                                onClick={choose(LiveChoice("custom",list=list.id));enterGuide()},onLongClick={editList=list;seedChannel=null;naming=true})
                        }
                        if(iptv.sources.any {it.enabled})item("your-playlists") {LiveSidebarHeading("Your playlists")}
                        iptv.sources.filter {it.enabled}.forEach {source ->
                            item("source:${source.id}") {
                                LiveSidebarEntry(source.name,sourceCounts[source.id]?:0,Icons.Outlined.LiveTv,
                                    selected=activeChoice.kind=="source" && activeChoice.source==source.id,expanded=source.id in expanded,
                                    onFocused={sidebarReturn=it;choose(LiveChoice("source",source.id),false)},onEnterGuide=::enterGuide,onClick={
                                        val closing=source.id in expanded
                                        expanded=if(closing)expanded-source.id else expanded+source.id
                                        choose(LiveChoice("source",source.id))
                                    })
                            }
                            if(source.id in expanded) {
                                item("all:${source.id}") {
                                    LiveSidebarEntry("All in this playlist",sourceCounts[source.id]?:0,Icons.AutoMirrored.Outlined.List,nested=true,
                                        selected=activeChoice.source==source.id && activeChoice.group==null,
                                        onFocused={sidebarReturn=it;choose(LiveChoice("source",source.id),false)},onEnterGuide=::enterGuide,
                                        onClick={choose(LiveChoice("source",source.id));enterGuide()})
                                }
                                items(sourceGroups[source.id].orEmpty().filter {LiveLibrary.groupKey(source.id,it) !in iptv.library.hiddenGroups},key={"group:${source.id}:$it"}) {group ->
                                    LiveSidebarEntry(group.ifBlank {"Ungrouped"},groupCounts[source.id to group]?:0,Icons.AutoMirrored.Outlined.List,nested=true,
                                        selected=activeChoice.source==source.id && activeChoice.group==group,
                                        onFocused={sidebarReturn=it;choose(LiveChoice("source",source.id,group),false)},onEnterGuide=::enterGuide,
                                        onClick={choose(LiveChoice("source",source.id,group));enterGuide()})
                                }
                            }
                        }
                        if(vm.liveRows.isNotEmpty() && "addons" !in iptv.library.hiddenSections)item("live-catalogs") {
                            LiveSidebarEntry("Live catalogs",vm.liveRows.sumOf {it.items.size},Icons.Outlined.VideoLibrary,selected=activeChoice.kind=="addons",
                                onFocused={sidebarReturn=it;choose(LiveChoice("addons"),false)},onEnterGuide=::enterGuide,onClick={choose(LiveChoice("addons"));enterGuide()})
                        }
                        if(!iptv.hasSources && vm.liveRows.isEmpty())item("add-source") {Chip("Add a playlist") {vm.openSettingsPage("LIVE")}}
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(guideEngaged && activeChoice.kind!="addons" && selected.isNotEmpty() && iptv.library.showProgrammePanel && viewportHeight>=320.dp) {
                            val channel=focused?.takeIf {old->selected.any {it.key==old.key}} ?: selected.firstOrNull()
                            val programme=if(channel?.key==focused?.key)focusedProgramme else channel?.let {iptv.nowNext(it).first}
                            LiveProgrammePanel(channel,programme,iptv.clock,
                                iptv.sources.firstOrNull {it.id==channel?.sourceId}?.name.orEmpty(),channel?.let(iptv::guideMessage).orEmpty(),
                                compact=iptv.library.compactProgramme || viewportHeight<400.dp || fontScale>1.25f,browsingTime=focusedTime)
                        }
                        val scopeKey="${vm.profiles.profileId}:$view:"+if(searching)"search:$searchAll" else "${activeChoice.kind}:${activeChoice.source}:${activeChoice.group}:${activeChoice.list}"
                        savedViews.SaveableStateProvider(scopeKey) {
                            if(view=="list" || activeChoice.kind=="addons" || selected.isEmpty())LaunchedEffect(entryVersion) {
                                if(entryVersion>0) {withFrameNanos { }
                                    runCatching {if(selected.isEmpty() && activeChoice.kind!="addons")emptyFocus.requestFocus() else bodyFocus.requestFocus()}
                                    entryVersion=0;entryKey=null
                                }
                            }
                            if(selected.isEmpty() && (activeChoice.kind!="addons" || searching)) {
                                Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp,Alignment.CenterVertically)) {
                                    Text(when {searching->"No channel names match this search.";iptv.loading->"Loading channels…";!iptv.hasSources->"Add an IPTV playlist to start watching.";else->"No visible channels in this list."},color=LiveColors.Muted,fontSize=14.sp)
                                    ButtonRow {
                                        if(searching)ToolbarIcon("Clear search",Icons.Outlined.Clear,Modifier.focusRequester(emptyFocus)) {query="";searchAll=false}
                                        if(iptv.hasSources)ToolbarIcon("Manage channels",Icons.Outlined.Edit,
                                            if(!searching)Modifier.focusRequester(emptyFocus) else Modifier) {manage=true}
                                        ToolbarIcon(if(iptv.hasSources)"Live TV settings" else "Add a playlist",if(iptv.hasSources)Icons.Outlined.Settings else Icons.AutoMirrored.Outlined.PlaylistAdd,
                                            if(!searching && !iptv.hasSources)Modifier.focusRequester(emptyFocus) else Modifier) {vm.openSettingsPage("LIVE")}
                                    }
                                }
                            } else if(activeChoice.kind=="addons" && !searching) {
                                LazyColumn(Modifier.fillMaxWidth().weight(1f).focusRequester(bodyFocus).focusGroup(),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                                    items(vm.liveRows,key={it.catalogKey ?: it.addonName+it.title}) {row->Section(row.displayTitle,row.addonName) {TitleRow(row.items,vm::isSaved,onOpen=onOpen)}}
                                }
                            } else if(view=="grid") {
                                Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged {if(it.hasFocus)guideEngaged=true}) {
                                    GuideGrid(selected,{iptv.guide[it.key].orEmpty()},iptv.favourites,iptv.clock,
                                        onPlay={play(selected,it)},onFavourite={options=it},entryKey=entryKey,entryVersion=entryVersion,
                                        onEntryHandled={entryVersion=0;entryKey=null;if(selected.isEmpty())runCatching {emptyFocus.requestFocus()}},onProgramme={channel,programme->programmeInfo=channel to programme},
                                        desiredRows=iptv.library.guideRows,showLogos=iptv.library.guideLogos,showControls=false,showPanel=false,
                                        onSelection={channel,programme,time->if(channel!=null){focused=channel;focusedProgramme=programme;focusedTime=time}},
                                        onGuideFocus={guideEngaged=true},onExitSidebar=::returnSidebar,onExitUp=::returnFeatured,
                                        jumpTime=guideJump,onJumpHandled={guideJump=null})
                                }
                            } else {
                                LazyColumn(Modifier.weight(1f).fillMaxWidth().focusRequester(bodyFocus).focusGroup().onFocusChanged {if(it.hasFocus)guideEngaged=true},verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                                    itemsIndexed(selected,key={_,c->c.key}) {index,channel ->
                                        ChannelRow(channel,iptv.nowNext(channel),channel.key in iptv.favourites,iptv.clock,
                                            onFocus={focused=channel;focusedProgramme=iptv.nowNext(channel).first},onPlay={play(selected,index)},onFavourite={options=channel})
                                    }
                                }
                            }
                        }

                    }
                }
                if(guideEngaged && activeChoice.kind!="addons" && selected.isNotEmpty())Text("Select a channel to watch · Hold OK for channel options",
                    color=LiveColors.Muted,fontSize=app.ember.tv.ui.TvTokens.Type.Caption.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=4.dp))
            }
        }
    }
    if(programmeSearch)ProgrammeSearchDialog(available,iptv.guide,iptv.clock,{programmeSearch=false}) {channel,programme->
        programmeSearch=false;programmeInfo=channel to programme
    }
    if(searchOpen)LiveDialog("Search channels",{searchOpen=false}) {
        TextInput(query,{query=it},"Channel name",width=500)
        SwitchChip("Search all playlists",searchAll) {searchAll=!searchAll}
        Text("${selected.size} matching channels",color=Mute,fontSize=13.sp)
        ButtonRow {ToolbarIcon("Show results",Icons.Outlined.Check) {searchOpen=false;enterGuide()};ToolbarIcon("Clear search",Icons.Outlined.Clear) {query="";searchAll=false}}
    }
    if (onNowOpen) LiveDialog("On now", { onNowOpen = false }) {
        if (iptv.visibleChannels.isEmpty()) {
            Text("No visible channels. Enable a playlist or restore channels in Manage.", color = Mute)
            Chip("Manage channels") { onNowOpen = false; manage = true }
        }
        LiveNowRow(iptv, onOptions = { onNowOpen = false; options = it }) { channels, index -> onNowOpen = false; play(channels, index) }
    }
    programmeInfo?.let { (channel, programme) ->
        LiveDialog(programme?.title ?: channel.name, { programmeInfo = null }) {
            Text(channel.name + " · " + (iptv.sources.firstOrNull { it.id == channel.sourceId }?.name ?: "IPTV"), color = Mute)
            programme?.let {
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it.start)) + "–" + timeOf(it.stop))
                Text(it.description ?: "No description available")
                if (it.start > iptv.clock) Text("Upcoming programme. Watch live opens the channel's current broadcast.", color = Mute)
            } ?: Text(iptv.guideMessage(channel), color = Mute)
            ButtonRow {
                ToolbarIcon("Watch live",Icons.Outlined.PlayArrow) { programmeInfo = null; play(available, available.indexOfFirst { it.key == channel.key }) }
                ToolbarIcon("Full schedule",Icons.Outlined.Schedule) { programmeInfo = null; schedule = channel }
                ToolbarIcon("Channel options",Icons.Outlined.MoreHoriz) { programmeInfo = null; options = channel }
                if (programme == null) ToolbarIcon("Guide settings",Icons.Outlined.Settings) { programmeInfo = null; vm.openSettingsPage("LIVE") }
            }
        }
    }
    schedule?.let { channel ->
        LiveDialog(channel.name + " · Schedule", { schedule = null }) {
            val upcoming = iptv.upcoming(channel)
            if (upcoming.isEmpty()) Text("No programme information available", color = Mute)
            upcoming.forEach { programme ->
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(programme.start)) + " · " + programme.title)
            }
        }
    }
    options?.let { channel ->
        val labels=listOf(if(channel.key in iptv.favourites)"Remove favourite" else "Add favourite","Match programme guide","Programme details","Hide channel")+
            iptv.library.lists.map {it.name}+"Create list with this channel"
        CompactActionMenu(channel.name,labels,{options=null}) {first->
            MenuActionRow(labels[0],modifier=Modifier.focusRequester(first),icon=Icons.Outlined.Star,selected=channel.key in iptv.favourites) { iptv.toggleFavourite(channel) }
            MenuActionRow(labels[1],icon=Icons.AutoMirrored.Outlined.EventNote) { options = null; vm.openSettingsGuarded { guideChannel = channel; guideTools = true } }
            MenuActionRow(labels[2],icon=Icons.Outlined.Info) { options = null; programmeInfo = channel to iptv.nowNext(channel).first }
            if(iptv.library.lists.isNotEmpty())Text("Custom lists", color = Mute,fontSize=12.sp)
            iptv.library.lists.forEachIndexed { i,list ->
                MenuActionRow(labels[4+i],icon=Icons.Outlined.Folder,selected=channel.key in list.channelKeys) { iptv.toggleListChannel(list.id, channel.key) }
            }
            MenuActionRow(labels.last(),icon=Icons.AutoMirrored.Outlined.PlaylistAdd) { editList = null; seedChannel = channel.key; options = null; naming = true }
            MenuActionRow(labels[3],icon=Icons.Outlined.VisibilityOff,destructive=true,divider=true) {
                vm.hideChannelWithUndo(channel); options = null
                if (view == "grid") { entryKey = null; entryVersion++ } else repairFocus = true
            }
        }
    }
    if (naming) ChannelListEditor(iptv, editList, seedChannel) { naming = false }
    if (manage) LiveManagement(vm) { manage = false }
    if (moreOpen) OptionMenu("Live TV options",buildList {
        add(MenuOption("On now",icon=Icons.Outlined.Schedule) {if(!returnFeatured())onNowOpen=true})
        add(MenuOption("Find a programme",icon=Icons.Outlined.Search) {programmeSearch=true})
        if(selected.isNotEmpty() && activeChoice.kind!="addons") {
            add(MenuOption("Jump to now",icon=Icons.Outlined.Schedule) {view="grid";vm.iptvSetup.updateIptvView(view);guideEngaged=true;guideJump=iptv.clock})
            add(MenuOption("Tonight",icon=Icons.Outlined.Schedule) {view="grid";vm.iptvSetup.updateIptvView(view);guideEngaged=true;guideJump=ProgrammeSearch.tonight(iptv.clock)})
        }
        add(MenuOption("Guide display",icon=Icons.Outlined.Tune) {displayOptions=true})
        add(MenuOption("Manage channels and lists",icon=Icons.Outlined.Edit) {manage=true})
        activeChoice.list?.let {id->iptv.library.lists.firstOrNull {it.id==id}?.let {list->
            add(MenuOption("Edit current list",icon=Icons.Outlined.Folder) {editList=list;seedChannel=null;naming=true})
        }}
        add(MenuOption("New list",icon=Icons.AutoMirrored.Outlined.PlaylistAdd) {editList=null;seedChannel=null;naming=true})
        add(MenuOption("Live TV settings",icon=Icons.Outlined.Settings) {vm.openSettingsPage("LIVE")})
    },close={moreOpen=false})
    if (guideTools) EpgTools(iptv, guideChannel) { guideTools = false }
    if (displayOptions) LiveDialog("Guide display", { displayOptions = false }) {
        Text("Target row count; larger text keeps a readable minimum row height.", color = Mute)
        ButtonRow { (5..7).forEach { rows -> Chip("$rows rows", selected = iptv.library.guideRows == rows) { iptv.guideDisplay(rows = rows) } } }
        SwitchChip("Channel logos", iptv.library.guideLogos) { iptv.guideDisplay(logos = !iptv.library.guideLogos) }
        SwitchChip("Show programme panel", iptv.library.showProgrammePanel) { iptv.guideDisplay(panel = !iptv.library.showProgrammePanel) }
        SwitchChip("Compact programme panel", iptv.library.compactProgramme) { iptv.guideDisplay(compact = !iptv.library.compactProgramme) }
    }
    if (numberChoices.isNotEmpty()) LiveDialog("Choose channel number", { numberChoices = emptyList() }) {
        val list = numberChoices
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.key }) { channel -> Chip(channel.name + " · " + (iptv.sources.firstOrNull { it.id == channel.sourceId }?.name ?: "IPTV")) {
                numberChoices = emptyList()
                play(available, available.indexOfFirst { it.key == channel.key })
            } }
        }
    }
    }
}

@Composable
private fun LiveDialog(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    MediaDialog(title, close, resizeForKeyboard = true) { content() }
}

@Composable
private fun ChannelListEditor(iptv: IptvController, list: ChannelList?, seed: String?, close: () -> Unit) {
    var name by remember(list?.id) { mutableStateOf(list?.name.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    LiveDialog(if (list == null) "Create channel list" else "Edit channel list", close) {
        TextInput(name, { name = it.take(80) }, "List name", width = 500)
        error?.let { Text(it, color = Mute) }
        Chip("Save") {
            val ok = if (list == null) iptv.createList(name, seed) != null else iptv.renameList(list.id, name)
            if (ok) close() else error = "Enter a unique, non-empty name."
        }
        if (list != null) {
            Text("Hold OK on channels to add or remove them from this list.", color = Mute, fontSize = 13.sp)
            Chip("Reorder channels") { ordering = true }
            Chip(if (deleting) "Confirm delete list" else "Delete list") {
                if (deleting) { iptv.deleteList(list.id); close() } else deleting = true
            }
        }
    }
    if (ordering && list != null) ChannelOrderDialog(iptv, list.id) { ordering = false }

}

@Composable
private fun LiveManagement(vm: HomeViewModel, close: () -> Unit) {
    val iptv = vm.iptv
    var tab by remember { mutableStateOf("rows") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ChannelList?>(null) }
    var hiddenOnly by remember { mutableStateOf(true) }
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    var bulkLists by remember { mutableStateOf(false) }
    val done = remember { FocusRequester() }
    LaunchedEffect(iptv.channels) { selectedKeys = selectedKeys.intersect(iptv.channels.map { it.key }.toSet()) }
    val sourceGroups = remember(iptv.channels) { iptv.channels.groupBy { it.sourceId }.mapValues { (_, cs) -> cs.map { it.group }.distinct() } }
    val channels = remember(iptv.channels, iptv.library.hiddenChannels, query, hiddenOnly) {
        iptv.channels.filter { (!hiddenOnly || it.key in iptv.library.hiddenChannels) && (query.trim().length < 2 || it.name.contains(query.trim(), true)) }
    }
    TvDialog(close,resizeForKeyboard=true) {
        DialogHeading("Manage Live TV")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Rows", selected = tab == "rows") { tab = "rows" }
            Chip("Channels", selected = tab == "channels") { tab = "channels" }
            ToolbarIcon("Close",Icons.Outlined.Close,Modifier.focusRequester(done)) { close() }
        }
        if (tab == "channels") {
            TextInput(query, { query = it }, "Search channels (2+ letters)", width = 570)
            ButtonRow(spacing = 8.dp) {
                SwitchChip("Hidden only", hiddenOnly) { hiddenOnly = !hiddenOnly }
                ToolbarIcon("Select shown channels",Icons.Outlined.SelectAll) { selectedKeys = selectedKeys + channels.map { it.key } }
                if (selectedKeys.isNotEmpty()) ToolbarIcon("Clear selection",Icons.Outlined.Clear) { done.requestFocus(); selectedKeys = emptySet() }
            }
            if (selectedKeys.isNotEmpty()) {
                Text("${selectedKeys.size} channels selected", color = Mute, fontSize = 13.sp)
                ButtonRow(spacing = 8.dp) {
                    ToolbarIcon("Hide selected channels",Icons.Outlined.VisibilityOff) { vm.hideChannelsWithUndo(selectedKeys, true) }
                    ToolbarIcon("Restore selected channels",Icons.Outlined.Visibility) { vm.hideChannelsWithUndo(selectedKeys, false) }
                    ToolbarIcon("Favourite selected channels",Icons.Outlined.Star) { iptv.setFavourites(selectedKeys, true) }
                    ToolbarIcon("Unfavourite selected channels",Icons.Outlined.StarBorder) { iptv.setFavourites(selectedKeys, false) }
                    ToolbarIcon("Add or remove selected channels in lists",Icons.AutoMirrored.Outlined.PlaylistAdd) { bulkLists = true }
                    vm.localUndo?.takeIf { it.profile == vm.profiles.profileId && it.message == vm.notice && it.message in setOf("Channels hidden", "Channels restored") }?.let {
                        ToolbarIcon("Undo visibility change",Icons.AutoMirrored.Outlined.Undo) { vm.undoLocalChange() }
                    }
                }
            }
            Text("Select channels, then apply an action to the selection.", color = Mute, fontSize = 12.sp)
        }
        LaunchedEffect(Unit) { withFrameNanos { }; done.requestFocus() }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tab == "rows") {
                item("on-now") { SettingToggle("On now row", null, iptv.library.showOnNow) { iptv.setOnNow(!iptv.library.showOnNow) } }
                items(listOf("favourites" to "Favourites", "recent" to "Recently watched", "addons" to "Live catalogs"), key = { "section:${it.first}" }) { (id, name) ->
                    val hidden = id in iptv.library.hiddenSections
                    SettingToggle(name, null, !hidden) { iptv.setSectionHidden(id, !hidden) }
                }
                items(iptv.library.lists, key = { "custom:${it.id}" }) { list ->
                    val id = "custom:${list.id}"
                    val hidden = id in iptv.library.hiddenSections
                    Column {
                        SettingToggle(list.name, "Custom list", !hidden) { iptv.setSectionHidden(id, !hidden) }
                        ButtonRow { ToolbarIcon("Edit "+list.name,Icons.Outlined.Edit) { editing = list }; ToolbarIcon("Move up",Icons.Outlined.ArrowUpward) { iptv.moveList(list.id, -1) }; ToolbarIcon("Move down",Icons.Outlined.ArrowDownward) { iptv.moveList(list.id, 1) } }
                    }
                }
                iptv.sources.forEach { source ->
                    item("source:${source.id}") {
                        SettingToggle(source.name, "Playlist", source.enabled) { iptv.setEnabled(source.id, !source.enabled) }
                    }
                    items(sourceGroups[source.id].orEmpty(), key = { "row:${source.id}:$it" }) { group ->
                        val hidden = LiveLibrary.groupKey(source.id, group) in iptv.library.hiddenGroups
                        SettingToggle(group.ifBlank { "Ungrouped" }, source.name, !hidden) {
                            iptv.setGroupHidden(source.id, group, !hidden)
                        }
                    }
                }
                val catalogs = vm.allCatalogs.filter { it.homeable && (vm.isOnLive(it) || it.type.lowercase() in setOf("tv", "channel", "channels", "events") || it.key in vm.catalogLive) }
                items(catalogs, key = { "catalog:${it.key}" }) { catalog ->
                    SettingToggle(catalog.label, catalog.addon.name, vm.isOnLive(catalog)) { vm.toggleCatalogLive(catalog) }
                }
            } else {
                if (channels.isEmpty()) item { Text("No matching or hidden channels.", color = Mute) }
                items(channels, key = { it.key }) { channel ->
                    val hidden = channel.key in iptv.library.hiddenChannels
                    val source = iptv.sources.firstOrNull { it.id == channel.sourceId }?.name.orEmpty()
                    Chip(channel.name + " · " + source + if (hidden) " (hidden)" else " (shown)", selected = channel.key in selectedKeys, modifier = Modifier.fillMaxWidth()) {
                        selectedKeys = if (channel.key in selectedKeys) selectedKeys - channel.key else selectedKeys + channel.key
                    }
                }
            }
        }

    }
    if (bulkLists) LiveDialog("Selected channels · Custom lists", { bulkLists = false }) {
        if (iptv.library.lists.isEmpty()) Text("Create a custom list from Live TV first.", color = Mute)
        iptv.library.lists.forEach { list ->
            val keys = iptv.channels.filter { it.key in selectedKeys }.map { it.key }
            Chip("Add to ${list.name}") { iptv.setListChannels(list.id, keys, true); bulkLists = false }
            Chip("Remove from ${list.name}") { iptv.setListChannels(list.id, keys, false); bulkLists = false }
        }
    }
    editing?.let { list -> ChannelListEditor(iptv, list, null) { editing = null } }
}

@Composable
private fun ChannelOrderDialog(iptv: IptvController, listId: String, close: () -> Unit) {
    val list = iptv.library.lists.firstOrNull { it.id == listId }
    val byKey = remember(iptv.channels) { iptv.channels.associateBy { it.key } }
    val first = remember { FocusRequester() }
    TvDialog(close) {
        DialogHeading(list?.name ?: "Custom list")
        ToolbarIcon("Close",Icons.Outlined.Close,Modifier.focusRequester(first),onClick=close)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list?.channelKeys.orEmpty(), key = { it }) { key ->
                Column {
                    Text(byKey[key]?.name ?: "Temporarily unavailable channel", fontSize = 16.sp)
                    ButtonRow { ToolbarIcon("Move up",Icons.Outlined.ArrowUpward) { iptv.moveListChannel(listId, key, -1) }; ToolbarIcon("Move down",Icons.Outlined.ArrowDownward) { iptv.moveListChannel(listId, key, 1) } }
                }
            }
        }

        LaunchedEffect(Unit) { withFrameNanos { }; first.requestFocus() }
    }
}
