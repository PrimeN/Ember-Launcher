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

/**
 * Home widgets on the TV (#161): Settings > Home lists your widgets and creates, edits and deletes
 * them with the remote. Their order and whether they show are in Home layout, with every other
 * section. The phone setup page has the same editor with a keyboard and more room.
 */
@Composable
internal fun WidgetsSettings(vm: HomeViewModel) {
    var editing by remember(vm.profiles.profileId) { mutableStateOf<HomeWidget?>(null) }
    var removing by remember(vm.profiles.profileId) { mutableStateOf<HomeWidget?>(null) }
    Section("Your widgets", "Rows you define for the Ember Home: a numbered Top 10, a cinematic carousel or a hero banner, from a catalog, a list or filters.", id = "home-widgets") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Create widget", modifier = Modifier.testTag("widget_create")) { editing = WidgetDrafts.new() }
            vm.widgets.list.forEach { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.width(420.dp)) {
                        Text(w.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(WidgetDrafts.summary(w, vm.allCatalogs), color = Mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Chip("Edit") { editing = w }
                    Chip("Delete") { removing = w }
                }
            }
            if (vm.widgets.list.isNotEmpty()) Note("Move widgets and switch them off in Home layout below.")
        }
    }
    editing?.let { WidgetEditorDialog(vm, it) { editing = null } }
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
