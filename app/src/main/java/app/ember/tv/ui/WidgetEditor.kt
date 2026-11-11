package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Home widgets on the TV (#161): Settings > Home lists your widgets and creates, edits and deletes
 * them with the remote. Their order and whether they show are in Home layout, with every other
 * section. The phone setup page has the same editor with a keyboard and more room.
 */
@Composable
internal fun WidgetsSettings(vm: HomeViewModel) {
    var editing by remember(vm.profiles.profileId) { mutableStateOf<HomeWidget?>(null) }
    var removing by remember(vm.profiles.profileId) { mutableStateOf<HomeWidget?>(null) }
    var importing by remember { mutableStateOf(false) }
    var menu by remember(vm.profiles.profileId) { mutableStateOf<String?>(null) }
    // #183: Home's "Edit widget" lands here with the editor already open.
    LaunchedEffect(vm.widgetToEdit) {
        val id = vm.widgetToEdit ?: return@LaunchedEffect
        vm.widgets.list.firstOrNull { it.id == id }?.let { editing = it }
        vm.widgetToEdit = null
    }
    var message by remember(vm.profiles.profileId) { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val kids = vm.profiles.profile.kids
    val info = vm.sections.sectionInfo.associateBy { it.id }
    fun own(section: String) = if (HomeWidgets.isSection(section)) vm.widgets.list.firstOrNull { HomeWidgets.section(it.id) == section } else null
    // #189: everything on Home is a widget. The ones that come with Ember and your own share one list, in Home order.
    Section("Home widgets", "Everything on Home is a widget. The banner, your apps, your lists and your catalogs come with Ember; add your own from a catalog, a list or filters. Home shows them in this order.", id = "home-widgets") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonRow {
                if (!kids) Chip("Create widget", modifier = Modifier.testTag("widget_create")) { editing = WidgetDrafts.new() }
                // #163: like collections, widgets move between TVs and profiles as a file.
                if (!kids) Chip("Import") { importing = true }
                if (!kids && vm.widgets.list.isNotEmpty()) Chip("Export all") {
                    val pack = HomeWidgets.pack(vm.widgets.list)
                    scope.launch {
                        message = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            Backup.saveToDownloads(context, "ember-widgets-${System.currentTimeMillis()}.json", pack.toByteArray())
                        } }.fold({ "Saved to $it" }, { "Could not save the widget pack." })
                    }
                }
                // #168: after a failure (a missing key, no connection) there was no way to try again without leaving Home.
                if (vm.widgets.errors.isNotEmpty()) Chip("Reload widgets") { vm.refreshWidgets(force = true) }
            }
            message?.let { Text(it, color = Mute, fontSize = 13.sp) }
            vm.sections.layout.forEach { (section, on) ->
                val built = info[section] ?: return@forEach
                val w = own(section)
                // #176: one card per widget, with a picture of what it is; OK opens its actions.
                Focusable(onClick = { menu = section }, modifier = Modifier.width(640.dp).testTag("widget_card"), radius = TvTokens.Shape.Card, container = PanelHi) {
                    val ink = androidx.tv.material3.LocalContentColor.current
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        // The editor's picture at 60%, so a long Home still fits a few to the screen.
                        Box(Modifier.width(94.dp).height(48.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.requiredWidth(156.dp).scale(.6f)) { WidgetStylePreview(WidgetDrafts.picture(section, w?.style), ink) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(w?.title ?: built.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (w != null) "Yours · " + WidgetDrafts.summary(w, vm.allCatalogs) else "Built in · " + built.note, color = ink.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // #168: why a widget is not on Home, instead of a row that is silently missing.
                            Text(WidgetDrafts.state(on, if (w == null) null else WidgetDrafts.status(on, w.id in vm.widgets.loading, vm.widgets.errors[w.id], vm.widgetItems(w).size)),
                                color = ink.copy(alpha = .7f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Note("Press OK on a widget to show or hide it or move it; your own can also be edited and deleted. Banner and list options are in Settings > Home > Banner & lists, and the apps in Apps & folders.")
            Chip("Back to the default order") { vm.sections.resetLayout() }
        }
    }
    var renaming by remember { mutableStateOf<String?>(null) }
    menu?.let { section ->
        val w = own(section)
        val order = vm.sections.layout.map { it.first }
        val catalog = app.ember.tv.state.HomeSectionsState.isCatalogSection(section)
        OptionMenu(w?.title ?: info[section]?.label ?: "Widget", buildList {
            WidgetDrafts.actions(w != null && !kids, vm.sections.sectionOn(section), if (vm.sections.isList(section)) vm.sections.listStyle(section) else null, catalog).forEach { label ->
                add(MenuOption(label) { when (label) {
                    "Edit" -> editing = w
                    "Delete" -> removing = w
                    "Rename" -> renaming = app.ember.tv.state.HomeSectionsState.catalogKey(section)
                    "Show as a button", "Show as a row" -> vm.sections.toggleListStyle(section)
                    else -> vm.sections.toggleSection(section)
                } })
            }
            WidgetDrafts.moves(order.indexOf(section), order.lastIndex).forEach { (label, step) -> add(MenuOption(label) { vm.sections.moveSection(section, step) }) }
        }) { menu = null }
    }
    renaming?.let { key ->
        val original = vm.allCatalogs.firstOrNull { it.key == key }?.originalLabel ?: vm.rows.firstOrNull { it.catalogKey == key }?.title ?: "Catalog"
        CatalogRenameDialog(vm, key, original) { renaming = null }
    }
    editing?.let { WidgetEditorDialog(vm, it) { editing = null } }
    if (importing) WidgetImportDialog(vm, { importing = false }) { message = it }
    removing?.let { w ->
        ConfirmDestructive("Delete ${w.title}?", "This deletes the widget from Home. This can't be undone; its source (a catalog, list or filters) is not affected.",
            { removing = null }) { vm.deleteWidget(w.id) }
    }
}

internal object WidgetDrafts {
    /** A new widget has no source yet; the default addon source without a catalog fails [HomeWidgets.error] until one is chosen. */
    fun new() = HomeWidget(id = java.util.UUID.randomUUID().toString())
    fun hasSource(w: HomeWidget) = HomeWidgets.error(w.copy(title = "x")) == null
    fun summary(w: HomeWidget, defs: List<CatalogDef>) = "${HomeWidgets.styleName(w.style)} · ${HomeWidgets.sourceLabel(w.source, defs)} · up to ${w.limit} titles"
    /** Choosing a source names an untitled widget after it. */
    fun withSource(w: HomeWidget, source: CollectionSource, label: String? = null) =
        w.copy(source = source, title = w.title.ifBlank { (label ?: source.title).orEmpty().take(100) })
    fun trakt(list: CollectionRequests.ListRef, media: String) = CollectionSource(provider = "trakt", title = list.name.take(100), traktListId = list.id, mediaType = media)
    val limits = listOf(10, 20, 30, 50)
    /** What to say under a widget that shows nothing on Home; null when it is showing titles or switched off. */
    fun status(on: Boolean, loading: Boolean, error: String?, titles: Int): String? = when {
        !on || titles > 0 -> null
        loading -> "Loading…"
        error != null -> "Could not load: $error"
        else -> "Nothing to show yet"
    }
    /** The picture on a widget's card (#189): your widget's style, or what a built-in one looks like. */
    fun picture(section: String, style: String?): String = style ?: when (section) {
        app.ember.tv.HomeViewModel.HOME_HERO -> HomeWidgets.HERO
        app.ember.tv.HomeViewModel.HOME_APPS -> APPS
        else -> HomeWidgets.ROW
    }
    const val APPS = "apps"
    /** A widget card's menu (#189), before the moves. Only your own widgets can be edited or deleted. */
    fun actions(own: Boolean, on: Boolean, listStyle: String?, catalog: Boolean): List<String> = buildList {
        if (own) add("Edit")
        add(if (on) "Hide from Home" else "Show on Home")
        if (listStyle != null) add(if (listStyle == "row") "Show as a button" else "Show as a row")
        if (catalog) add("Rename")
        if (own) add("Delete")
    }
    /** The first line of a widget row's menu on Home (#183): the title's own options. */
    fun titleOption(name: String) = "Options for " + name.trim().ifBlank { "this title" }.let { if (it.length > 28) it.take(27).trimEnd() + "…" else it }
    /** #181: filters built in the editor become a source named after what they ask for. */
    fun filters(query: app.ember.tv.discover.ExploreQuery, profile: String): CollectionSource =
        CollectionDrafts.discover(HomeWidgets.summary(query).ifBlank { "Filters" }, query, profile)
    /** How many titles the editor's preview draws (#182): posters fit more than wide cards; a panel style shows one. */
    fun previewCount(style: String, wide: Boolean) = when (style) { HomeWidgets.CAROUSEL, HomeWidgets.HERO -> 1; else -> if (wide) 4 else 7 }
    /** The last line of a widget's card (#176): what is wrong, or simply whether it is on Home. */
    fun state(on: Boolean, status: String?) = status ?: if (on) "On Home" else "Hidden from Home"
    /** "Move up", "Move to the top"… for a widget at [index] of the Home layout; none when it has no place there. */
    fun moves(index: Int, lastIndex: Int): List<Pair<String, Int>> =
        if (index < 0) emptyList() else moveOptions(index, lastIndex).map { (label, step) -> "Move " + label.replaceFirstChar { it.lowercase() } to step }
    /** A Trakt list's card (#178): who made it, how long it is and how well liked. */
    fun traktLine(list: CollectionRequests.ListRef) = listOfNotNull(list.by.takeIf { it.isNotBlank() }?.let { "@$it" },
        "${list.items} title" + if (list.items == 1) "" else "s", list.likes.takeIf { it > 0 }?.let { "$it like" + if (it == 1) "" else "s" }).joinToString(" · ")
    /** #171: an MDBList list typed on the TV; the error to show, or the source. */
    fun mdblist(text: String, hasKey: Boolean): Pair<String?, CollectionSource?> {
        val path = HomeWidgets.mdblistPath(text) ?: return "Enter an MDBList list link, user/list-name or number." to null
        if (!hasKey) return "Add your MDBList API key in Settings > Accounts > API keys first." to null
        return null to CollectionSource(provider = HomeWidgets.MDBLIST, catalogId = path, title = "MDBList $path")
    }
}

/** The TV editor's three steps (#173): one decision on screen at a time. */
internal object WidgetSteps {
    const val STYLE = "style"; const val SOURCE = "source"; const val DETAILS = "details"
    val all = listOf(STYLE to "Style", SOURCE to "Source", DETAILS to "Details")
    /** A new widget starts by choosing how it looks; editing opens on the details, with the other steps one press away. */
    fun first(existing: Boolean) = if (existing) DETAILS else STYLE
    fun next(step: String): String? = all.map { it.first }.let { ids -> ids.getOrNull(ids.indexOf(step) + 1) }
    fun heading(step: String) = when (step) { STYLE -> "How it looks"; SOURCE -> "Where the titles come from"; else -> "Name and size" }
}

@Composable
private fun WidgetEditorDialog(vm: HomeViewModel, initial: HomeWidget, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(false) }
    var trakt by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val existing = vm.widgets.list.any { it.id == initial.id }
    var step by remember(initial.id) { mutableStateOf(WidgetSteps.first(existing)) }
    // Choosing a source moves a new widget on to its details; an edit stays where it is.
    fun chosen(source: CollectionSource, label: String? = null) { draft = WidgetDrafts.withSource(draft, source, label); message = null; if (!existing) step = WidgetSteps.DETAILS }
    if (picker) CollectionSourcePicker(vm, onChoose = { source -> chosen(source); picker = false }, close = { picker = false })
    else if (trakt) TraktListPicker(vm, onChoose = { source -> chosen(source); trakt = false }, close = { trakt = false })
    // #181: Discover's own filter panel, opened in place; no saved search needed first.
    else if (filters) ExploreFilters(draft.source.exploreQuery ?: app.ember.tv.discover.ExploreQuery(), vm.watch.watchRegion, vm.exploreRepository,
        onApply = { query -> chosen(WidgetDrafts.filters(query, vm.profiles.profileId)); filters = false }, onClose = { filters = false })
    else MediaDialog((if (existing) "Edit widget" else "New widget") + " · " + WidgetSteps.heading(step), close, resizeForKeyboard = true) {
        ButtonRow { WidgetSteps.all.forEachIndexed { i, (id, name) -> Chip("${i + 1}  $name", step == id, modifier = Modifier.testTag("widget_step_$id")) { step = id; message = null } } }
        when (step) {
            WidgetSteps.STYLE -> {
                // #172: each style as a small picture of itself.
                ButtonRow { HomeWidgets.styles.forEach { (value, name) ->
                    WidgetStyleTile(value, name, draft.style == value) { draft = draft.copy(style = value); if (!existing) step = WidgetSteps.SOURCE }
                } }
            }
            WidgetSteps.SOURCE -> {
                Text("Now: " + if (WidgetDrafts.hasSource(draft)) HomeWidgets.sourceLabel(draft.source, vm.allCatalogs) else "nothing chosen yet", fontSize = 15.sp)
                ButtonRow {
                    Chip("Build filters", modifier = Modifier.testTag("widget_filters")) { filters = true }
                    Chip("Catalog, saved search or TMDB") { picker = true }
                    Chip("Browse Trakt lists") { trakt = true }
                }
                Text("One of your lists", color = Mute, fontSize = 13.sp)
                ButtonRow { vm.widgetLists().take(14).forEach { (id, label) ->
                    Chip(label, draft.source.provider == HomeWidgets.LIST && draft.source.catalogId == id, maxLines = 1) {
                        chosen(CollectionSource(provider = HomeWidgets.LIST, catalogId = id, title = label), label)
                    }
                } }
                // #171: MDBList was only on the phone page.
                Text("An MDBList list", color = Mute, fontSize = 13.sp)
                var mdb by remember { mutableStateOf(if (draft.source.provider == HomeWidgets.MDBLIST) draft.source.catalogId.orEmpty() else "") }
                TvTextField(mdb, { mdb = it.take(200) }, Modifier.fillMaxWidth(), placeholder = "MDBList link, user/list-name or number")
                ButtonRow { Chip("Use this MDBList list", draft.source.provider == HomeWidgets.MDBLIST) {
                    val (problem, source) = WidgetDrafts.mdblist(mdb, ApiKeys.get("MDBLIST_API_KEY").isNotBlank())
                    if (source != null) chosen(source) else message = problem
                } }
                Note("Build filters picks titles by kind, genre, years, rating, people, studios and services. Searches you saved in Discover are under Catalog, saved search or TMDB.")
            }
            else -> {
                TvTextField(draft.title, { draft = draft.copy(title = it.take(100)) }, Modifier.fillMaxWidth(), placeholder = "Widget title")
                if (draft.style == HomeWidgets.ROW || draft.style == HomeWidgets.NUMBERED) {
                    Text("Card shape", color = Mute, fontSize = 13.sp)
                    ButtonRow { HomeWidgets.shapes.forEach { (value, name) -> Chip(name, draft.shape == value) { draft = draft.copy(shape = value) } } }
                }
                Text("Titles to show", color = Mute, fontSize = 13.sp)
                ButtonRow { WidgetDrafts.limits.forEach { n -> Chip("$n", draft.limit == n) { draft = draft.copy(limit = n) } } }
                Text(HomeWidgets.styleName(draft.style) + " · " + if (WidgetDrafts.hasSource(draft)) HomeWidgets.sourceLabel(draft.source, vm.allCatalogs) else "no source yet", color = Mute, fontSize = 13.sp)
                // #182: what will land on Home, with the source's real titles.
                WidgetPreview(vm, draft)
            }
        }
        message?.let { Text(it, color = Mute, fontSize = 13.sp) }
        ButtonRow {
            val next = WidgetSteps.next(step)
            if (next != null && !existing) Chip("Next") { step = next; message = null }
            if (existing || next == null) Chip("Save widget", modifier = Modifier.testTag("widget_save")) {
                message = vm.widgets.save(draft)
                if (message == null) { vm.refreshWidgets(); close() }
                // Send them to the step that is missing something.
                else if (!WidgetDrafts.hasSource(draft)) step = WidgetSteps.SOURCE
            }
            Chip("Cancel") { close() }
        }
    }
}

/**
 * The editor's preview (#182): the chosen source's first titles drawn in the chosen style, so an
 * empty or wrong source shows before saving. Nothing here takes focus.
 */
@Composable
private fun WidgetPreview(vm: HomeViewModel, draft: HomeWidget) {
    if (!WidgetDrafts.hasSource(draft)) return
    var titles by remember { mutableStateOf<List<Meta>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draft.source) {
        titles = null; problem = null
        if (draft.source.provider == HomeWidgets.LIST) titles = vm.widgetItems(draft)
        else vm.previewWidget(draft.source).fold({ titles = it }, { problem = app.ember.tv.state.WidgetsState.reason(it) })
    }
    Text("Preview", color = Mute, fontSize = 13.sp)
    val shown = titles?.take(draft.limit)
    when {
        problem != null -> Text("Could not load: $problem", fontSize = 13.sp)
        shown == null -> Text("Loading…", color = Mute, fontSize = 13.sp)
        shown.isEmpty() -> Text("This source has no titles right now.", fontSize = 13.sp)
        else -> {
            val wide = draft.shape == "wide"
            val few = shown.take(WidgetDrafts.previewCount(draft.style, wide))
            when (draft.style) {
                HomeWidgets.CAROUSEL, HomeWidgets.HERO -> Box(Modifier.width(360.dp).height(202.dp).clip(RoundedCornerShape(10.dp)).background(PanelHi).testTag("widget_preview")) {
                    LandscapeImage(few.first(), Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(.5f to Color.Transparent, 1f to Color(0xE6000000))))
                    Text(few.first().name, Modifier.align(if (draft.style == HomeWidgets.HERO) Alignment.BottomStart else Alignment.BottomCenter).padding(12.dp),
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> Row(Modifier.testTag("widget_preview"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    few.forEachIndexed { i, meta ->
                        if (draft.style == HomeWidgets.NUMBERED) Text("${i + 1}", fontSize = 30.sp, lineHeight = 30.sp, fontWeight = FontWeight.Black, color = Mute)
                        val shape = Modifier.clip(RoundedCornerShape(8.dp)).background(PanelHi)
                        if (wide) LandscapeImage(meta, Modifier.width(132.dp).height(74.dp).then(shape))
                        else PosterImage(meta, Modifier.width(70.dp).height(105.dp).then(shape))
                    }
                }
            }
            Text(if (shown.size == 1) "1 title" else "${shown.size} titles, the first ${few.size} shown", color = Mute, fontSize = 12.sp)
        }
    }
}

/** One widget style in the picker (#172): a small picture of it, its name and a line on what it is. */
@Composable
private fun WidgetStyleTile(style: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.width(176.dp).testTag("widget_style_$style"), radius = TvTokens.Shape.Card, container = if (selected) WashHi else PanelHi) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetStylePreview(style, ink)
            Text(if (selected) "$name  ✓" else name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeWidgets.about(style), fontSize = 12.sp, lineHeight = 15.sp, color = ink.copy(alpha = .7f), minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A diagram of the style in the tile's own ink, so it reads on any theme and when focused. */
@Composable
private fun WidgetStylePreview(style: String, ink: Color) {
    val soft = ink.copy(alpha = .4f); val strong = ink.copy(alpha = .92f)
    fun Modifier.block(color: Color, corner: Int = 3) = clip(RoundedCornerShape(corner.dp)).background(color)
    Box(Modifier.fillMaxWidth().height(80.dp).block(ink.copy(alpha = .1f), 8).padding(8.dp), contentAlignment = Alignment.Center) {
        when (style) {
            HomeWidgets.NUMBERED -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                (1..3).forEach { n ->
                    Text("$n", fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black, color = strong)
                    Box(Modifier.width(20.dp).height(38.dp).block(soft))
                }
            }
            HomeWidgets.CAROUSEL -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).height(46.dp).block(ink.copy(alpha = .22f)))
                Box(Modifier.weight(1f).fillMaxHeight().block(soft, 5)) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp).width(46.dp).height(5.dp).block(strong))
                }
                Box(Modifier.width(5.dp).height(46.dp).block(ink.copy(alpha = .22f)))
            }
            HomeWidgets.HERO -> Box(Modifier.fillMaxSize().block(soft, 5)) {
                Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(56.dp).height(6.dp).block(strong))
                    Box(Modifier.width(40.dp).height(4.dp).block(ink.copy(alpha = .7f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(24.dp).height(9.dp).block(strong, 5))
                        Box(Modifier.size(9.dp).block(ink.copy(alpha = .7f), 5))
                    }
                }
            }
            // The Apps widget: a row of app tiles.
            WidgetDrafts.APPS -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { Box(Modifier.width(30.dp).height(20.dp).block(soft, 6)) }
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { Box(Modifier.width(30.dp).height(44.dp).block(soft)) }
            }
        }
    }
}

/** Import a widget pack from a link (#163). The phone setup page takes a file or pasted text instead. */
@Composable
private fun WidgetImportDialog(vm: HomeViewModel, close: () -> Unit, done: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<String, List<HomeWidget>>?>(null) }
    val scope = rememberCoroutineScope()
    MediaDialog("Import widgets", close, resizeForKeyboard = true) {
        Text("A link to a widget pack exported from Ember. Widgets with the same id are updated; the rest are added.", color = Mute)
        TvTextField(url, { url = it; pending = null; message = null }, Modifier.fillMaxWidth(), placeholder = "Pack link (https://…)")
        message?.let { Text(it, color = Mute) }
        val found = pending
        if (found == null) Chip(if (busy) "Opening…" else "Check the pack") { if (!busy && url.isNotBlank()) { busy = true; scope.launch {
            val text = runCatching { Collections.download(url) }
            val widgets = text.getOrNull()?.let { HomeWidgets.unpack(it, vm.profiles.profileId) }
            message = when {
                text.isFailure -> "Could not open that link. Check it and the connection."
                widgets?.isFailure == true -> widgets.exceptionOrNull()?.message ?: "This is not an Ember widget pack."
                else -> null
            }
            pending = text.getOrNull()?.let { raw -> widgets?.getOrNull()?.let { raw to it } }
            busy = false
        } } } else {
            Text("${found.second.size} widget" + (if (found.second.size == 1) "" else "s") + ": " + found.second.take(6).joinToString(", ") { it.title } + if (found.second.size > 6) "…" else "", fontSize = 15.sp)
            ButtonRow {
                Chip("Import ${found.second.size}") { done(vm.importWidgets(found.first)); close() }
                Chip("Cancel") { close() }
            }
        }
    }
}

/** Trakt's popular and trending public lists, and a search of them. */
@Composable
private fun TraktListPicker(vm: HomeViewModel, onChoose: (CollectionSource) -> Unit, close: () -> Unit) {
    var tab by remember { mutableStateOf("popular") }
    var query by remember { mutableStateOf("") }
    var media by remember { mutableStateOf("MOVIE") }
    var found by remember { mutableStateOf<List<CollectionRequests.ListRef>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, query) {
        found = null; failed = null
        if (query.isNotBlank()) delay(450)
        val result = runCatching { vm.traktLists(tab, query.trim().takeIf { it.length >= 2 }.orEmpty()) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // #166: say why, as the phone page does; "check the connection" hid a missing or refused client ID.
        failed = result.exceptionOrNull()?.let { app.ember.tv.phone.PhoneLookups.reason("trakt", it).replace("Accounts & APIs > Trakt", "Settings > Accounts > API keys > Trakt").replace("under Accounts & APIs", "in Settings > Accounts > API keys") }
        found = result.getOrDefault(emptyList())
    }
    MediaDialog("Choose a Trakt list", close, resizeForKeyboard = true) {
        ButtonRow {
            Chip("Popular", tab == "popular" && query.isBlank()) { tab = "popular"; query = "" }
            Chip("Trending", tab == "trending" && query.isBlank()) { tab = "trending"; query = "" }
            listOf("MOVIE" to "Movies", "TV" to "Shows").forEach { (key, label) -> Chip(label, media == key) { media = key } }
        }
        TvTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), placeholder = "Search Trakt lists")
        val lists = found
        when {
            lists == null -> Text("Loading…", color = Mute)
            failed != null -> Text(failed.orEmpty(), color = Mute)
            lists.isEmpty() -> Text("No lists found.", color = Mute)
            // #178: cards, two across, instead of thirty full-width buttons.
            else -> ButtonRow { lists.take(30).forEach { l ->
                Focusable(onClick = { onChoose(WidgetDrafts.trakt(l, media)) }, modifier = Modifier.width(340.dp).testTag("trakt_list"), radius = TvTokens.Shape.Card, container = PanelHi) {
                    val ink = androidx.tv.material3.LocalContentColor.current
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(l.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(WidgetDrafts.traktLine(l), fontSize = 12.sp, color = ink.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(l.description, fontSize = 12.sp, lineHeight = 15.sp, color = ink.copy(alpha = .6f), minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } }
        }
    }
}
