package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var message by remember(vm.profiles.profileId) { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    Section("Your widgets", "Rows you define for the Ember Home: a numbered Top 10, a cinematic carousel or a hero banner, from a catalog, a list or filters.", id = "home-widgets") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonRow {
                Chip("Create widget", modifier = Modifier.testTag("widget_create")) { editing = WidgetDrafts.new() }
                // #163: like collections, widgets move between TVs and profiles as a file.
                Chip("Import") { importing = true }
                if (vm.widgets.list.isNotEmpty()) Chip("Export all") {
                    val pack = HomeWidgets.pack(vm.widgets.list)
                    scope.launch {
                        message = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            Backup.saveToDownloads(context, "ember-widgets-${System.currentTimeMillis()}.json", pack.toByteArray())
                        } }.fold({ "Saved to $it" }, { "Could not save the widget pack." })
                    }
                }
            }
            message?.let { Text(it, color = Mute, fontSize = 13.sp) }
            // In Home order, so Move matches what Home shows.
            val layout = vm.sections.layout
            val order = layout.map { it.first }
            vm.widgets.list.sortedBy { w -> order.indexOf(HomeWidgets.section(w.id)).let { if (it < 0) Int.MAX_VALUE else it } }.forEach { w ->
                val section = HomeWidgets.section(w.id)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.width(340.dp)) {
                        Text(w.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(WidgetDrafts.summary(w, vm.allCatalogs), color = Mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SwitchChip("On Home", vm.sections.sectionOn(section)) { vm.sections.toggleSection(section) }
                    MoveButton(w.title, order.indexOf(section), order.lastIndex) { step -> vm.sections.moveSection(section, step) }
                    Chip("Edit") { editing = w }
                    Chip("Delete") { removing = w }
                }
            }
            Note(if (vm.widgets.list.isEmpty()) "No widgets yet. The banner, apps, catalogs and lists that come with Home are arranged in Home layout."
                else "Move steps a widget past every Home section, built-in ones included; Home layout shows the whole order.")
        }
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
}

@Composable
private fun WidgetEditorDialog(vm: HomeViewModel, initial: HomeWidget, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(false) }
    var trakt by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val existing = vm.widgets.list.any { it.id == initial.id }
    if (picker) CollectionSourcePicker(vm, onChoose = { source -> draft = WidgetDrafts.withSource(draft, source); picker = false; message = null }, close = { picker = false })
    else if (trakt) TraktListPicker(vm, onChoose = { source -> draft = WidgetDrafts.withSource(draft, source); trakt = false; message = null }, close = { trakt = false })
    else MediaDialog(if (existing) "Edit widget" else "New widget", close, resizeForKeyboard = true) {
        TvTextField(draft.title, { draft = draft.copy(title = it.take(100)) }, Modifier.fillMaxWidth(), placeholder = "Widget title")
        Text("Style", color = Mute, fontSize = 13.sp)
        ButtonRow { HomeWidgets.styles.forEach { (value, name) -> Chip(name, draft.style == value) { draft = draft.copy(style = value) } } }
        if (draft.style == HomeWidgets.ROW || draft.style == HomeWidgets.NUMBERED) {
            Text("Card shape", color = Mute, fontSize = 13.sp)
            ButtonRow { HomeWidgets.shapes.forEach { (value, name) -> Chip(name, draft.shape == value) { draft = draft.copy(shape = value) } } }
        }
        Text("Titles to show", color = Mute, fontSize = 13.sp)
        ButtonRow { WidgetDrafts.limits.forEach { n -> Chip("$n", draft.limit == n) { draft = draft.copy(limit = n) } } }
        Text("Source: " + if (WidgetDrafts.hasSource(draft)) HomeWidgets.sourceLabel(draft.source, vm.allCatalogs) else "none yet", fontSize = 15.sp)
        ButtonRow {
            Chip("Catalog, saved search or TMDB") { picker = true }
            Chip("Browse Trakt lists") { trakt = true }
        }
        Text("Or one of your lists", color = Mute, fontSize = 13.sp)
        ButtonRow { vm.widgetLists().take(14).forEach { (id, label) ->
            Chip(label, draft.source.provider == HomeWidgets.LIST && draft.source.catalogId == id, maxLines = 1) {
                draft = WidgetDrafts.withSource(draft, CollectionSource(provider = HomeWidgets.LIST, catalogId = id, title = label), label); message = null
            }
        } }
        message?.let { Text(it, color = Mute, fontSize = 13.sp) }
        ButtonRow {
            Chip("Save widget") {
                message = vm.widgets.save(draft)
                if (message == null) { vm.refreshWidgets(); close() }
            }
            Chip("Cancel") { close() }
        }
        Note("Filters: save a search in Discover, then pick it under Catalog, saved search or TMDB. MDBList by link is on the phone setup page.")
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
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(tab, query) {
        found = null; failed = false
        if (query.isNotBlank()) delay(450)
        val result = runCatching { vm.traktLists(tab, query.trim().takeIf { it.length >= 2 }.orEmpty()) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        failed = result.isFailure
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
            failed -> Text("Trakt's lists could not be loaded. Check the connection and try again.", color = Mute)
            lists.isEmpty() -> Text("No lists found.", color = Mute)
            else -> lists.take(30).forEach { l ->
                Chip(l.name + listOfNotNull(l.by.takeIf { it.isNotBlank() }?.let { " · @$it" }, " · ${l.items} titles").joinToString(""), modifier = Modifier.fillMaxWidth(), maxLines = 1) {
                    onChoose(WidgetDrafts.trakt(l, media))
                }
            }
        }
    }
}
