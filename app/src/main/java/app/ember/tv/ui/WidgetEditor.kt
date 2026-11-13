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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.updateHomeAppRows
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
    var showOff by remember { mutableStateOf(false) }
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
                if (vm.hiddenHomeTitleCount > 0) Chip("Restore ${vm.hiddenHomeTitleCount} hidden Home titles") { vm.restoreHomeTitles() }
            }
            // #195: one press sets every built-in row; a row can still be given its own shape in its Edit.
            Text("Rows that come with Ember", color = Mute, fontSize = 13.sp)
            ButtonRow {
                val own = vm.sections.rowShapes.size
                Chip("All wide", selected = own == 0 && vm.look.modernCards != "poster") { vm.look.updateModernCards("wide"); vm.sections.clearShapes() }
                Chip("All posters", selected = own == 0 && vm.look.modernCards == "poster") { vm.look.updateModernCards("poster"); vm.sections.clearShapes() }
                if (own > 0) Text(if (own == 1) "1 row has its own shape" else "$own rows have their own shape", color = Mute, fontSize = 13.sp)
            }
            message?.let { Text(it, color = Mute, fontSize = 13.sp) }
            // #238/#239: the list is what is on Home; what is off waits below, folded.
            val (onIds, offIds) = WidgetDrafts.split(vm.sections.layout, info.keys)
            Text(ManagerCards.counts(onIds.size, offIds.size), color = Mute, fontSize = 13.sp)
            @Composable fun card(section: String, on: Boolean) {
                val built = info.getValue(section)
                val w = own(section)
                ManagerCard(w?.title ?: built.label, listOf(
                    if (w != null) "Yours · " + WidgetDrafts.summary(w, vm.allCatalogs) else "Built in · " + built.note,
                    // #168: why a widget is not on Home, instead of a row that is silently missing.
                    WidgetDrafts.state(on, if (w == null) null else WidgetDrafts.status(on, w.id in vm.widgets.loading, vm.widgets.errors[w.id], vm.widgetItems(w).size)),
                ), onClick = { menu = section }, tag = "widget_card", trailing = {
                    // #199: hide or show in one press, without the menu.
                    SwitchChip("On Home", on, modifier = Modifier.semantics { contentDescription = (w?.title ?: built.label) + ", on Home" }) { vm.sections.toggleSection(section) }
                }) {
                    val ink = androidx.tv.material3.LocalContentColor.current
                    // The editor's picture, scaled down so a long Home still fits a few to the screen.
                    Box(Modifier.requiredWidth(156.dp).scale(.68f)) { WidgetStylePreview(WidgetDrafts.picture(section, w?.style), ink) }
                }
            }
            onIds.forEach { card(it, true) }
            if (onIds.isEmpty()) Note("Nothing is on Home. Turn widgets on below.")
            if (offIds.isNotEmpty()) {
                FoldHeader("Turned off · ${offIds.size} ${if (offIds.size == 1) "widget" else "widgets"}", showOff, Modifier.testTag("widgets_off")) { showOff = !showOff }
                if (showOff) WidgetDrafts.offGroups(offIds, { own(it) != null }, { id -> info[id]?.note?.substringBefore(" · ")?.ifBlank { null } ?: "addons" }).forEach { (group, ids) ->
                    Text(group, color = Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    ids.forEach { card(it, false) }
                }
            }
            Note("The switch beside a widget hides or shows it; turned-off widgets wait in their own list. Addon catalogs become widgets when they are on Home. Press OK on a widget to edit or move it: the banner's catalog and artwork, what each list includes, a row's card shape. Your own can also be deleted.")
            Chip("Back to the default order") { vm.sections.resetLayout() }
        }
    }
    var renaming by remember { mutableStateOf<String?>(null) }
    var adjusting by remember(vm.profiles.profileId) { mutableStateOf<String?>(null) }
    menu?.let { section ->
        val w = own(section)
        val order = vm.sections.layout.filter { it.second && it.first in info }.map { it.first }
        OptionMenu(w?.title ?: info[section]?.label ?: "Widget", buildList {
            WidgetDrafts.actions(w != null && !kids, w == null && WidgetDrafts.editable(section), vm.sections.sectionOn(section)).forEach { label ->
                add(MenuOption(label) { when (label) {
                    // #195: built-in widgets can't be deleted, but what applies to them can be adjusted.
                    "Edit" -> if (w != null) editing = w else adjusting = section
                    "Delete" -> removing = w
                    else -> vm.sections.toggleSection(section)
                } })
            }
            // #239: moves count only the widgets on Home, so every step shows.
            WidgetDrafts.moves(order.indexOf(section), order.lastIndex).forEach { (label, step) ->
                add(MenuOption(label) { WidgetDrafts.reorder(order, section, step)?.let(vm.sections::arrange) })
            }
        }) { menu = null }
    }
    adjusting?.let { section -> BuiltInWidgetDialog(vm, section, info[section]?.label ?: "Widget", rename = { renaming = it }) { adjusting = null } }
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
    /** A widget card's menu (#189, #195), before the moves. Built-in widgets are edited where a setting applies, never deleted. */
    fun actions(own: Boolean, editable: Boolean, on: Boolean): List<String> = buildList {
        if (own || editable) add("Edit")
        add(if (on) "Hide from Home" else "Show on Home")
        if (own) add("Delete")
    }
    /** Built-in widgets with something to adjust: the banner, apps, the three lists and every row of titles. */
    fun editable(section: String): Boolean = section == app.ember.tv.HomeViewModel.HOME_HERO || section == app.ember.tv.HomeViewModel.HOME_APPS ||
        app.ember.tv.state.HomeSectionsState.hasShape(section)
    /** The list a built-in list widget draws on, as list sources name it; null for any other widget. */
    fun personalList(section: String): String? = PersonalListSources.lists.firstOrNull { it.first == section }?.first
    /** The first line of a widget row's menu on Home (#183): the title's own options. */
    fun titleOption(name: String) = "Options for " + name.trim().ifBlank { "this title" }.let { if (it.length > 28) it.take(27).trimEnd() + "…" else it }
    /** #181: filters built in the editor become a source named after what they ask for. */
    fun filters(query: app.ember.tv.discover.ExploreQuery, profile: String): CollectionSource =
        CollectionDrafts.discover(HomeWidgets.summary(query).ifBlank { "Filters" }, query, profile)
    /** How many titles the editor's preview draws (#182): posters fit more than wide cards; a panel style shows one. */
    fun previewCount(style: String, wide: Boolean) = when (style) { HomeWidgets.CAROUSEL, HomeWidgets.HERO -> 1; else -> if (wide) 4 else 7 }
    /** The last line of a widget's card (#176): what is wrong, or simply whether it is on Home. */
    fun state(on: Boolean, status: String?) = status ?: if (on) "On Home" else "Hidden from Home"
    /**
     * #238/#239: the widgets on Home, in order, and the ones switched off. An addon catalog only
     * counts as a widget while it is on Home; off, it waits in the turned-off list with the rest.
     */
    fun split(layout: List<Pair<String, Boolean>>, known: Set<String>): Pair<List<String>, List<String>> =
        layout.filter { it.first in known }.partition { it.second }.let { (on, off) -> on.map { it.first } to off.map { it.first } }
    /** Turned-off widgets in groups: what comes with Ember, yours, then each addon's catalogs. */
    fun offGroups(off: List<String>, own: (String) -> Boolean, addon: (String) -> String): List<Pair<String, List<String>>> {
        val (catalogs, rest) = off.partition { app.ember.tv.state.HomeSectionsState.isCatalogSection(it) }
        val (yours, built) = rest.partition(own)
        return listOfNotNull(built.takeIf { it.isNotEmpty() }?.let { "Built in" to it }, yours.takeIf { it.isNotEmpty() }?.let { "Your widgets" to it }) +
            catalogs.groupBy(addon).toList().sortedBy { it.first.lowercase() }.map { (name, ids) -> "Catalogs from $name" to ids }
    }
    /** The Home order after moving [id] by [step] among the widgets on Home; null when it does not move. */
    fun reorder(on: List<String>, id: String, step: Int): List<String>? {
        val from = on.indexOf(id); if (from < 0) return null
        val to = (from + step).coerceIn(0, on.lastIndex); if (to == from) return null
        return on.toMutableList().apply { add(to, removeAt(from)) }
    }
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

/** Poster, wide, or whatever Home's rows use, as a small picture (#195). */
@Composable
internal fun CardShapeTile(shape: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.width(132.dp).testTag("card_shape_$shape"), radius = TvTokens.Shape.Card, container = if (selected) WashHi else PanelHi) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.height(56.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                fun Modifier.block() = clip(RoundedCornerShape(4.dp)).background(ink.copy(alpha = .45f))
                when (shape) {
                    "poster" -> repeat(2) { Box(Modifier.width(34.dp).height(51.dp).block()) }
                    "wide" -> Box(Modifier.width(84.dp).height(47.dp).block())
                    else -> { Box(Modifier.width(26.dp).height(39.dp).block()); Box(Modifier.width(52.dp).height(29.dp).block()) }
                }
            }
            Text(if (selected) "$name  ✓" else name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Adjusting a widget that comes with Ember (#195, #197): the banner's catalog and artwork, how
 * many rows of apps, what a list includes and whether it is a row or a button, a row's card shape,
 * a catalog's name. Nothing here deletes it.
 */
@Composable
private fun BuiltInWidgetDialog(vm: HomeViewModel, section: String, label: String, rename: (String) -> Unit, close: () -> Unit) {
    var catalogs by remember { mutableStateOf(false) }
    if (catalogs) { CatalogSourcePicker(vm, "Banner catalog", vm.look.heroCatalog, vm.look::updateHeroCatalog) { catalogs = false }; return }
    MediaDialog(label, close, wide = true) {
        when {
            section == HomeViewModel.HOME_HERO -> {
                SettingValueRow("Catalog", catalogSourceLabel(vm, vm.look.heroCatalog)) { catalogs = true }
                Note("Any addon catalog, or a mix of the Home catalogs. Up to ten titles with artwork are shown, with Watch now and watchlist.")
                Text("Artwork", color = Mute, fontSize = 13.sp)
                ButtonRow { HomeWidgets.arts.forEach { (value, name) -> Chip(name, vm.look.heroArtworkMode == value) { vm.look.updateHeroArtworkMode(value) } } }
                Note(if (vm.look.heroArtworkMode == HeroArtworkMode.FANART) "Prefers Fanart backgrounds and logos; missing images fall back to standard artwork."
                    else "Standard TMDB and catalog backgrounds and logos.")
                if (vm.look.heroArtworkMode == HeroArtworkMode.FANART) Chip("Fanart API key") { close(); vm.openSettingsPage("KEYS") }
            }
            section == HomeViewModel.HOME_APPS -> {
                Text("Rows of apps", color = Mute, fontSize = 13.sp)
                ButtonRow {
                    Chip("One row", selected = vm.homeAppRows == 1) { vm.updateHomeAppRows(1) }
                    Chip("Two rows", selected = vm.homeAppRows == 2) { vm.updateHomeAppRows(2) }
                }
                Chip("Choose the apps") { close(); vm.openSettingsPage("HOME_TOOLS") }
            }
        }
        WidgetDrafts.personalList(section)?.let { list ->
            Text("Show as", color = Mute, fontSize = 13.sp)
            ButtonRow { listOf("row" to "A row of titles", "button" to "A button") .forEach { (value, name) ->
                Chip(name, vm.sections.listStyle(section) == value) { if (vm.sections.listStyle(section) != value) vm.sections.toggleListStyle(section) }
            } }
            // #197: what the list includes, for this profile; it used to be on a page of its own.
            Text("Include from", color = Mute, fontSize = 13.sp)
            ButtonRow { PersonalListSources.sources.forEach { (source, name) ->
                SwitchChip(name, vm.sections.sourceEnabled(vm.profiles.profileId, list, source)) { vm.sections.toggleSource(vm.profiles.profileId, list, source) }
            } }
            Note("Applies to ${vm.profile.name}, on Home and on the list's own page. A source left out keeps its own data.")
            if (list != "recent") Chip("Restore hidden items") { LocalLibrary.restoreList(list) }
        }
        if (app.ember.tv.state.HomeSectionsState.hasShape(section) && (WidgetDrafts.personalList(section) == null || vm.sections.listStyle(section) == "row")) {
            Text("Card shape", color = Mute, fontSize = 13.sp)
            val now = vm.sections.shape(section) ?: "default"
            ButtonRow { HomeWidgets.shapes.forEach { (value, name) -> CardShapeTile(value, name, now == value) { vm.sections.setShape(section, value.takeIf { it != "default" }) } } }
        }
        if (app.ember.tv.state.HomeSectionsState.isCatalogSection(section)) Chip("Rename") { close(); rename(app.ember.tv.state.HomeSectionsState.catalogKey(section)) }
        ButtonRow { Chip("Done") { close() } }
    }
}

/** One widget style in the picker (#172): a small picture of it, its name and a line on what it is. */
@Composable
internal fun WidgetStyleTile(style: String, name: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = modifier.width(176.dp).testTag("widget_style_$style"), radius = TvTokens.Shape.Card, container = if (selected) WashHi else PanelHi) {
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
    Box(Modifier.fillMaxWidth().height(80.dp).block(ink.copy(alpha = .1f), 8).padding(if (style == HomeWidgets.CAROUSEL) 0.dp else 8.dp), contentAlignment = Alignment.Center) {
        when (style) {
            // #202: the rank sits on each picture's corner.
            HomeWidgets.NUMBERED -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                (1..4).forEach { n ->
                    Box(Modifier.width(30.dp).height(44.dp).block(soft), contentAlignment = Alignment.BottomStart) {
                        Text("$n", Modifier.padding(start = 3.dp), fontSize = 16.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black, color = strong)
                    }
                }
            }
            // #201: the whole page.
            HomeWidgets.CAROUSEL -> Box(Modifier.fillMaxSize().block(soft, 2)) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).width(50.dp).height(6.dp).block(strong))
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).width(20.dp).height(2.dp).block(ink.copy(alpha = .7f)))
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

/** Your Trakt lists and the ones you liked (signed in), Trakt's popular and trending lists, and a search (#246). */
@Composable
internal fun TraktListPicker(vm: HomeViewModel, onChoose: (CollectionSource) -> Unit, close: () -> Unit) {
    val signedIn = remember { vm.hub.trakt() != null }
    var tab by remember { mutableStateOf(if (signedIn) "mine" else "popular") }
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
    MediaDialog("Choose a Trakt list", close, resizeForKeyboard = true, wide = true) {
        ButtonRow {
            if (signedIn) {
                Chip("My lists", tab == "mine" && query.isBlank()) { tab = "mine"; query = "" }
                Chip("Liked", tab == "liked" && query.isBlank()) { tab = "liked"; query = "" }
            }
            Chip("Popular", tab == "popular" && query.isBlank()) { tab = "popular"; query = "" }
            Chip("Trending", tab == "trending" && query.isBlank()) { tab = "trending"; query = "" }
            Chip("Official", tab == "official" && query.isBlank()) { tab = "official"; query = "" }
            listOf("MOVIE" to "Movies", "TV" to "Shows").forEach { (key, label) -> Chip(label, media == key) { media = key } }
        }
        TvTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), placeholder = "Search Trakt lists")
        val lists = found
        when {
            lists == null -> Text("Loading…", color = Mute)
            failed != null -> Text(failed.orEmpty(), color = Mute)
            lists.isEmpty() -> Text(if (tab == "mine" && query.isBlank()) "Your Trakt account has no lists yet." else "No lists found.", color = Mute)
            // #178: cards, two across, instead of thirty full-width buttons.
            else -> ButtonRow { lists.take(30).forEach { l ->
                Focusable(onClick = { onChoose(WidgetDrafts.trakt(l, media)) }, modifier = Modifier.width(270.dp).testTag("trakt_list"), radius = TvTokens.Shape.Card, container = PanelHi) {
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
