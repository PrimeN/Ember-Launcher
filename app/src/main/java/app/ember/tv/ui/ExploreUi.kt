package app.ember.tv.ui

import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import app.ember.tv.isSaved

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import app.ember.tv.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta
import app.ember.tv.discover.*
import app.ember.tv.state.DiscoverSection

@Composable
fun DiscoverScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit) {
    val state = vm.explore
    val kids = vm.profiles.profile.kids
    val sections = vm.discoverSections
    val section = app.ember.tv.data.DiscoverVisibility.selected(state.section, sections)
    var panel by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var save by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var presets by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var sortMenu by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var kindMenu by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var catalogs by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var anyCatalog by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var catalogFilters by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var info by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var recovery by remember(vm.profiles.profileId) {mutableStateOf(false)}
    val aiConfigured = vm.look.aiDiscoveryEnabled && vm.exploreRepository.configured &&
        remember(vm.keysRev) { app.ember.tv.data.ApiKeys.get("MISTRAL_API_KEY").isNotBlank() }
    var aiOpen by remember(vm.profiles.profileId, vm.keysRev, aiConfigured, vm.watch.watchRegion) { mutableStateOf(false) }
    var aiDraft by remember(vm.profiles.profileId, vm.keysRev, aiConfigured, vm.watch.watchRegion) { mutableStateOf<ExploreQuery?>(null) }
    val aiReview = aiDraft != null
    val filterFocus = remember { FocusRequester() }
    val sortFocus = remember { FocusRequester() }
    val kindFocus = remember { FocusRequester() }
    val catalogFocus = remember { FocusRequester() }
    val grid = key(vm.profiles.profileId) { rememberLazyGridState() }
    var shownRevision by rememberSaveable(vm.profiles.profileId) { mutableIntStateOf(state.revision) }
    val interactive = LocalSceneInteractive.current
    val def = vm.discover.discoverCatalog
    LaunchedEffect(vm.profiles.profileId, vm.activeAddons, vm.catalogs.catalogDiscoverOff, interactive, vm.look.discoverSections) {
        if (interactive && vm.look.discoverSections[DiscoverSection.CATALOGS.name] != false)
            vm.discover.openDiscover(loadItems = false)
    }
    LaunchedEffect(section, vm.profiles.profileId, vm.watch.watchRegion, interactive) {
        if (!interactive) { state.pause(); return@LaunchedEffect }
        if (section != null) state.section = section
        when (section) {
            DiscoverSection.CATALOGS -> { state.pause(); vm.discover.openDiscover() }
            DiscoverSection.COLLECTIONS, null -> state.pause()
            DiscoverSection.EXPLORE -> state.open()
        }
    }
    LaunchedEffect(state.revision) {
        if (shownRevision != state.revision) { grid.scrollToItem(0); shownRevision = state.revision }
    }
    // One header row: section choices scroll independently; compact actions stay reachable.
    Column(Modifier.fillMaxSize()) {
        if(sections.size>1 || section==DiscoverSection.EXPLORE || section==DiscoverSection.CATALOGS && def!=null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = TvTokens.Space.Page.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sections.size > 1) androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f),
                    contentPadding = PaddingValues(5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sections, key = { it.name }) { choice ->
                        Chip(choice.name.lowercase().replaceFirstChar { it.uppercase() }, selected = section == choice) { state.section = choice }
                    }
                }
                when (section) {
                    DiscoverSection.EXPLORE -> {
                        Chip(state.query.kind.label + " ▾", modifier = Modifier.focusRequester(kindFocus)) { kindMenu = true }
                        ToolbarIcon("Filters · ${exploreTags(state.query).size} active", androidx.compose.material.icons.Icons.Outlined.FilterList,
                            Modifier.focusRequester(filterFocus), selected = exploreTags(state.query).isNotEmpty()) { panel = true }
                        ToolbarIcon("Sort: ${state.query.sort.label}", androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Sort,
                            Modifier.focusRequester(sortFocus)) { sortMenu = true }
                    }
                    DiscoverSection.CATALOGS -> {
                        if (def != null) Chip(def.label.take(22) + " ▾", modifier = Modifier.focusRequester(catalogFocus)) { catalogs = true }
                        if (def != null && (def.extras.isNotEmpty() || def.genres.isNotEmpty()))
                            ToolbarIcon("Catalog filters", androidx.compose.material.icons.Icons.Outlined.FilterList,
                                Modifier.focusRequester(filterFocus), selected = vm.discover.extraArgs != def.defaultExtras || vm.discover.discoverGenre != null) { catalogFilters = true }
                    }
                    DiscoverSection.COLLECTIONS -> Unit
                    null -> Unit
                }
                if (sections.size <= 1) Spacer(Modifier.weight(1f))
            }
        }
        when (section) {
            null -> Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = TvTokens.Space.Page.dp)) {
                if (vm.discover.discoverLoading) LoadingCards() else RecoveryPanel("No Discover sections to show",
                    "Enable a section in Discover settings, or add catalogs and collections.", "Discover settings", { vm.openSettingsPage("DISCOVER") })
            }
            DiscoverSection.COLLECTIONS -> Box(Modifier.fillMaxWidth().weight(1f)) { DiscoverCollections(vm) }
            DiscoverSection.CATALOGS -> Box(Modifier.fillMaxWidth().weight(1f)) { AddonDiscoverScreen(vm, onOpen) }
            DiscoverSection.EXPLORE -> Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = TvTokens.Space.Page.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${state.query.sort.label} · ${state.results.size}/100 titles" +
                    (if (exploreTags(state.query).isNotEmpty()) " · ${exploreTags(state.query).size} filters" else "") + when {
                        state.loading -> " · Finding matches…"
                        state.complete -> if (state.pagingLimit) " · Provider limit reached" else " · Complete"
                        else -> ""
                    }, color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (!state.configured) {
                    RecoveryPanel("Set up Explore", "A TMDB API key enables movie and show discovery.", "API keys", { vm.openSettingsPage("KEYS") })
                } else {
                    state.error?.let { message ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, color = Mute, fontSize = TvTokens.Type.Compact.sp, modifier = Modifier.weight(1f))
                            ToolbarIcon("Retry search", icon = androidx.compose.material.icons.Icons.Outlined.Refresh) { state.more() }
                        }
                    }
                    if (state.loading && state.results.isEmpty()) LoadingCards()
                    if (state.complete && state.results.isEmpty() && state.error==null) {
                        RecoveryPanel("No matching titles",ExploreRecovery.explanation(state.query,vm.watch.watchRegion,state.pagingLimit),
                            "Edit filters",{panel=true})
                        if(!kids && ExploreRecovery.choices(state.query).isNotEmpty())
                            Chip("Relax one filter group") {recovery=true}
                    }
                    if (state.results.isNotEmpty()) TitleGrid(
                        state.results.mapIndexed { i, title -> title.meta.copy(badge = "#${i + 1} · ${title.meta.badge ?: "TMDB"}") },
                        isSaved = { vm.isSaved(it) }, onOpen = { state.pause(); onOpen(it) }, modifier = Modifier.fillMaxWidth().weight(1f), state = grid,
                        footer = if (state.complete || state.loading) null else ({ Chip("Keep finding matches") { state.more() } }),
                    ) else if (!state.loading && !state.complete && state.error == null) {
                        Text("No matches in the candidates checked so far. Your filters are kept.", color = Mute, fontSize = TvTokens.Type.Compact.sp)
                        Chip("Keep finding matches") { state.more() }
                    }
                }
            }
        }
    }
    if(recovery && !kids)OptionMenu("Relax filters",ExploreRecovery.choices(state.query).map {choice->
        MenuOption(choice.label) {state.apply(choice.query)}
    }) {recovery=false}
    if (kindMenu && !kids) OptionMenu("Title type", ExploreKind.entries.map { kind ->
        MenuOption(kind.label, selected = state.query.kind == kind) {
            state.apply(state.query.copy(kind = kind, oscars = if (kind == ExploreKind.TV) OscarMode.ANY else state.query.oscars,
                bestPicture = kind != ExploreKind.TV && state.query.bestPicture))
        }
    }) { kindMenu = false }
    if (catalogs) OptionMenu("Choose catalog", vm.discover.discoverCatalogs.mapIndexed { index, catalog ->
        MenuOption("${catalog.label} · ${catalog.addon.name}", selected = index == vm.discover.discoverIndex) { vm.discover.selectCatalog(index) }
    }+MenuOption("Other catalogs…",icon=androidx.compose.material.icons.Icons.AutoMirrored.Outlined.KeyboardArrowRight) {
        anyCatalog = true
    }+MenuOption("Refresh catalog",icon=androidx.compose.material.icons.Icons.Outlined.Refresh) {
        vm.discover.discoverCatalog?.let {vm.discover.selectCatalog(vm.discover.discoverIndex)} ?: vm.discover.retry()
    }) { catalogs = false }
    // Any addon catalog, not only those switched on for Discover (#68).
    if (anyCatalog) CatalogSourcePicker(vm, "Any catalog", vm.discover.discoverCatalog?.key, { key ->
        vm.catalogs.allCatalogs.firstOrNull { it.key == key }?.let(vm.discover::show)
    }, automatic = false, eligible = { it.browsable }) { anyCatalog = false }
    if (catalogFilters && def != null) {
        val filterDef = if (def.genres.isNotEmpty() && def.extras.none { it.name == "genre" })
            def.copy(extras = def.extras + app.ember.tv.data.CatalogExtraDef("genre", def.genreRequired, def.genres)) else def
        CatalogOptions(filterDef, vm.discover.extraArgs + listOfNotNull(vm.discover.discoverGenre?.let { "genre" to it }).toMap(), { catalogFilters = false }) {
            vm.discover.applyExtras(it); catalogFilters = false
        }
    }
    if (sortMenu && !kids) OptionMenu("Sort by", ExploreSort.entries.map { sort ->
        MenuOption(sort.label, selected = state.query.sort == sort) { if (state.query.sort != sort) state.apply(state.query.copy(sort = sort)) }
    }) { sortMenu = false }
    if ((panel || aiReview) && !kids) ExploreFilters(aiDraft ?: state.query, vm.watch.watchRegion, vm.exploreRepository,
        onApply = { state.apply(it); panel = false; aiDraft = null }, onClose = { panel = false; aiDraft = null },
        searchTools=if(aiReview)null else ({
            Text("Apply any filter changes before saving a search.",color=Mute,fontSize=TvTokens.Type.Compact.sp)
            if(aiConfigured)MenuActionRow("Describe what to watch") {panel=false;aiOpen=true}
            MenuActionRow("Save current search",icon=androidx.compose.material.icons.Icons.Outlined.Add) {panel=false;save=true}
            MenuActionRow("Saved searches (${state.saved.size})") {panel=false;presets=true}
            MenuActionRow(if(state.loading)"Pause search" else "Keep finding matches") {if(state.loading)state.pause() else state.more()}
            MenuActionRow("Refresh search",icon=androidx.compose.material.icons.Icons.Outlined.Refresh) {state.refresh()}
            MenuActionRow("Search details and active filters") {panel=false;info=true}
        }))
    if (aiOpen && aiConfigured && !kids) key(vm.profiles.profileId, vm.keysRev, vm.watch.watchRegion) {
        AiDiscoveryDialog(vm.exploreRepository, vm.watch.watchRegion, onClose = { aiOpen = false },
            onReview = { aiDraft = it; aiOpen = false })
    }
    if (save && !kids) SaveExploreDialog(onClose = { save = false }, onSave = state::save)
    if (presets && !kids) SavedExploreDialog(state.saved, onOpen = { state.load(it); presets = false }, onDelete = state::delete,
        onHome = state::toggleHome, onClose = { presets = false })
    if (info && !kids) MediaDialog("Search details", { info = false }) {
        Text("${vm.watch.watchRegion} · TMDB · ${state.pagesChecked} pages checked", color = Mute, fontSize = TvTokens.Type.Compact.sp)
        if (state.query.providers.isNotEmpty()) Text("Availability from JustWatch", color = Mute, fontSize = TvTokens.Type.Compact.sp)
        if (state.query.oscars != OscarMode.ANY) Text("Oscar records: Wikidata. Coverage may be incomplete; missing records do not count as matches.", color = Mute, fontSize = TvTokens.Type.Compact.sp)
        exploreTags(state.query).forEach { (label, query) -> Chip("$label ×", selected = true) { state.apply(query) } }
    }
    RestoreToolbarFocus(panel || catalogFilters || save || presets || info || aiOpen || aiReview || recovery, filterFocus)
    RestoreToolbarFocus(sortMenu, sortFocus)
    RestoreToolbarFocus(kindMenu, kindFocus)
    RestoreToolbarFocus(catalogs, catalogFocus)
}

@Composable
private fun DiscoverCollections(vm: HomeViewModel) {
    val collections = vm.collections.onDiscover
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = TvTokens.Space.Page.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (collections.isEmpty()) item(key = "empty") {
            RecoveryPanel("No collections to show", "Import collections and choose which ones appear in Discover in Settings > Stremio.")
        }
        items(collections, key = { it.id }) { collection ->
            CollectionRow(collection, animations = vm.collections.heroPreviews && !vm.profiles.profile.kids,
                onMove = if (vm.profiles.profile.kids) null else ({ f, delta -> vm.collections.moveFolder(collection, f, delta) })) { folder -> vm.collections.openFolder(collection, folder) }
        }
    }
}


internal fun exploreTags(q: ExploreQuery): List<Pair<String, ExploreQuery>> = buildList {
    ExploreGenres.selected(q).forEach { add(it.label to q.copy(genres = q.genres - it.key)) }
    if (q.fromYear != null || q.toYear != null) add("${q.fromYear ?: "Any"}–${q.toYear ?: "Now"}" to q.copy(fromYear = null, toYear = null))
    if (q.minRating != null || q.maxRating != null) add("TMDB ${q.minRating ?: 0.0}–${q.maxRating ?: 10.0}" to q.copy(minRating = null, maxRating = null))
    q.providers.forEach { r -> add(r.name to q.copy(providers = q.providers.filterNot { it.id == r.id })) }
    q.actors.forEach { r -> add("Actor: ${r.name}" to q.copy(actors = q.actors.filterNot { it.id == r.id })) }
    q.directors.forEach { r -> add("Director: ${r.name}" to q.copy(directors = q.directors.filterNot { it.id == r.id })) }
    q.companies.forEach { r -> add(r.name to q.copy(companies = q.companies.filterNot { it.id == r.id })) }
    if (q.oscars != OscarMode.ANY) add((q.oscars.label + if (q.bestPicture) " · Best Picture" else "") to q.copy(oscars = OscarMode.ANY, bestPicture = false))
}

@Composable
private fun SaveExploreDialog(onClose: () -> Unit, onSave: (String) -> String?) {
    var name by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    TvDialog(onClose,width=430.dp,resizeForKeyboard=true,scrollContent=true) {
        DialogHeading("Save this search")
        TvTextField(name, { name = it.take(80) }, placeholder = "e.g. DreamWorks favourites", focusRequester = first)
        Text("An existing name updates that search. Results refresh from its saved filters.", color = Mute, fontSize = TvTokens.Type.Compact.sp)
        error?.let { Text(it, color = Mute, fontSize = TvTokens.Type.Compact.sp) }
        ButtonRow { Chip("Save") { error = onSave(name); if (error == null) onClose() }; Chip("Cancel", onClick = onClose) }

        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}

@Composable
private fun SavedExploreDialog(saved: List<SavedExplore>, onOpen: (String) -> Unit, onDelete: (String) -> Unit,
    onHome: (String) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    TvDialog(onClose,width=560.dp,height=440.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            DialogHeading("Saved searches",Modifier.weight(1f))
            DialogClose(onClose, Modifier.focusRequester(first))
        }
        if (saved.isEmpty()) Text("Apply your filters, then choose Save search. Saved searches belong to this profile.", color = Mute, fontSize = 14.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(saved, key = { it.id }) { item ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text((listOf(item.query.kind.label, item.query.sort.label) + item.query.summary()).joinToString(" · "), color = Mute, fontSize = TvTokens.Type.Caption.sp)
                    ButtonRow {
                        Chip("Open") { onOpen(item.id) }
                        Chip(if (item.home) "Remove from Home" else "Add to Home") { onHome(item.id) }
                        Chip("Delete") { onDelete(item.id) }
                    }
                }
            }
        }

        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
}
