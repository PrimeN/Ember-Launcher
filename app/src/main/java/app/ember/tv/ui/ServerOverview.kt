package app.ember.tv.ui

import app.ember.tv.isSaved
import app.ember.tv.retryServer
import app.ember.tv.syncServers
import app.ember.tv.toggleWatchlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*

@Composable
internal fun ServerOverview(vm:HomeViewModel,onOpen:(Meta)->Unit) {
    var browsing by rememberSaveable(vm.profiles.profileId) {mutableStateOf(false)}
    var browseMenu by remember(vm.profiles.profileId) {mutableStateOf(false)}
    var issues by remember {mutableStateOf(false)}
    var heroIndex by remember(vm.profiles.profileId,vm.look.serverHeroSource) {mutableIntStateOf(0)}
    val state=rememberLazyListState()
    val sections=vm.serverSections.filter {it.visible(vm.look.serverSectionsOff)}
    val hero=if(vm.look.serverHeroOn)vm.serverHeroItems else emptyList()
    var ambient by remember(vm.profiles.profileId) {mutableStateOf<Meta?>(null)}
    var presented by remember(vm.profiles.profileId) {mutableStateOf<Meta?>(null)}
    val atTop by remember {derivedStateOf {state.firstVisibleItemIndex==0 && state.firstVisibleItemScrollOffset<120}}
    val selected=hero.getOrNull(HeroBanner.index(heroIndex,hero.size))
    val backdrop=if(hero.isNotEmpty() && atTop)selected else ambient ?: selected ?: sections.firstOrNull()?.items?.firstOrNull()
    val host=LocalPageBackdrop.current
    val browsingLibrary=browsing && vm.serverBrowser.library!=null
    val showBackdrop=!browsingLibrary && LocalFocusBackdrop.current?.tile==null && FocusBackground.idleArtwork(hero.isNotEmpty()&&atTop,vm.look.mediaFocusBackground)
    val selection=if(showBackdrop)PageBackdropSelection(backdrop,if(hero.isNotEmpty()&&atTop)0f else .55f,
        if(isEmberX)1f else HeroBanner.BACKDROP_FRACTION) else null
    SideEffect { host?.selection=selection }
    DisposableEffect(host) { onDispose { host?.selection=null } }
    LaunchedEffect(vm.libraryShortcutPending) {if(vm.libraryShortcutPending){browsing=true;vm.consumeLibraryShortcut()}}
    if(browsingLibrary) {ServerLibraryScreen(vm,onOpen){browsing=false};return}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height=HeroLayout.height(maxHeight.value,androidx.compose.ui.platform.LocalDensity.current.fontScale).dp
        if(host==null && selection!=null)
            ModernBackdrop(selection.meta,selection.dim,heightFraction=selection.heightFraction,onPresented={presented=it})
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal=TvTokens.Space.Page.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                ToolbarIcon("Browse server libraries",Icons.Outlined.GridView){browseMenu=true}
                ToolbarIcon("Refresh servers",Icons.Outlined.Refresh){vm.syncServers()}
                ToolbarIcon("Library sections and hero",Icons.Outlined.Tune){vm.openSettingsPage("SERVERS")}
                if(vm.serverHealth.any {it.failed})ToolbarIcon("Server connection issues",Icons.Outlined.WarningAmber){issues=true}
            }
            LazyColumn(state=state,contentPadding=PaddingValues(start=TvTokens.Space.Page.dp,end=TvTokens.Space.Page.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(hero.isNotEmpty())item(key="server-hero") {
                    ModernHero(hero,heroIndex,{heroIndex=it},height,vm::isSaved,vm::toggleWatchlist,onOpen,
                        onFocus={},rotating=LocalSceneInteractive.current&&atTop&&!state.isScrollInProgress,presented=if(host!=null)host.presented else presented)
                }
                items(sections,key={it.id}) {section->
                    Column {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(section.title,fontSize=TvTokens.Type.Section.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                            Text(section.source,fontSize=TvTokens.Type.Caption.sp,color=Mute,maxLines=1)
                        }
                        TitleRow(section.items,vm::isSaved,onFocused={ambient=it},onOpen=onOpen)
                    }
                }
                if(vm.librariesLoading && sections.isEmpty())item(key="loading"){LoadingCards()}
                if(!vm.librariesLoading && sections.isEmpty())item(key="empty") {
                    val connected=vm.plexConnected||vm.serverAccounts.isNotEmpty()
                    RecoveryPanel(if(connected)"No visible server sections" else "Connect a media server",
                        if(connected)"Choose sections in server settings, or refresh your libraries." else "Connect Plex, Jellyfin or Emby.","Server settings",vm::openServerSettings)
                }
            }
        }
    }
    if(browseMenu)OptionMenu("Browse libraries",vm.serverLibraries.filter {it.ref.isNotBlank()}.map {lib->
        MenuOption("${lib.title} · ${lib.server}"){vm.serverBrowser.open(lib);browsing=true}
    }.ifEmpty {listOf(MenuOption("Server settings"){vm.openServerSettings()})}){browseMenu=false}
    if(issues)MediaDialog("Server connections",{issues=false}){SourceStatusButton("Connection issues",vm.serverHealth.filter {it.failed},vm::retryServer)}
}

@Composable
internal fun ServerPresentationSettings(vm:HomeViewModel) {
    var sourceMenu by remember {mutableStateOf(false)}
    Section("Library sections","Choose which rows appear in Library.") {
        ServerPresentation.groups.forEach {(id,label)->
            SettingToggle(label,null,"group:$id" !in vm.look.serverSectionsOff){vm.look.toggleServerSection("group:$id")}
        }
        vm.serverSections.forEach {section->
            SettingToggle("${section.title} · ${section.source}",null,section.id !in vm.look.serverSectionsOff){vm.look.toggleServerSection(section.id)}
        }
    }
    Section("Library hero","Optional carousel with up to ten titles. Hidden sections are excluded; an unavailable source falls back to the visible sections.") {
        SettingToggle("Show Library hero",null,vm.look.serverHeroOn){vm.look.updateServerHeroOn(!vm.look.serverHeroOn)}
        Chip("Source: "+(vm.serverSections.firstOrNull {it.id==vm.look.serverHeroSource}?.title ?: "Automatic")){sourceMenu=true}
    }
    if(sourceMenu)OptionMenu("Library hero source",listOf(MenuOption("Automatic",vm.look.serverHeroSource==null){vm.look.updateServerHeroSource(null)})+
        vm.serverSections.filter {it.visible(vm.look.serverSectionsOff)}.map {section->MenuOption("${section.title} · ${section.source}",vm.look.serverHeroSource==section.id){vm.look.updateServerHeroSource(section.id)}}){sourceMenu=false}
}
