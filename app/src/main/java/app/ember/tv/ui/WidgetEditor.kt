package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            // #168: after a failure (a missing key, no connection) there was no way to try again without leaving Home.
            if (vm.widgets.errors.isNotEmpty()) Chip("Reload widgets") { vm.refreshWidgets(force = true) }
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
                        // #168: why a widget is not on Home, instead of a row that is silently missing.
                        WidgetDrafts.status(vm.sections.sectionOn(section), w.id in vm.widgets.loading, vm.widgets.errors[w.id], vm.widgetItems(w).size)?.let {
                            Text(it, color = Mute, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
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
    /** What to say under a widget that shows nothing on Home; null when it is showing titles or switched off. */
    fun status(on: Boolean, loading: Boolean, error: String?, titles: Int): String? = when {
        !on || titles > 0 -> null
        loading -> "Loading…"
        error != null -> "Could not load: $error"
        else -> "Nothing to show yet"
    }
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
    var message by remember { mutableStateOf<String?>(null) }
    val existing = vm.widgets.list.any { it.id == initial.id }
    var step by remember(initial.id) { mutableStateOf(WidgetSteps.first(existing)) }
    // Choosing a source moves a new widget on to its details; an edit stays where it is.
    fun chosen(source: CollectionSource, label: String? = null) { draft = WidgetDrafts.withSource(draft, source, label); message = null; if (!existing) step = WidgetSteps.DETAILS }
    if (picker) CollectionSourcePicker(vm, onChoose = { source -> chosen(source); picker = false }, close = { picker = false })
    else if (trakt) TraktListPicker(vm, onChoose = { source -> chosen(source); trakt = false }, close = { trakt = false })
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
                Note("Filters: save a search in Discover, then pick it under Catalog, saved search or TMDB.")
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

/** One widget style in the picker (#172): a small picture of it, its name and a line on what it is. */
@Composable
private fun WidgetStyleTile(style: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.width(176.dp).testTag("widget_style_$style"), radius = TvTokens.Shape.Card, container = if (selected) WashHi else PanelHi) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetStylePreview(style, ink)
            Text(if (selected) "$name  ✓" else name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeWidgets.about(style), fontSize = 11.sp, lineHeight = 14.sp, color = ink.copy(alpha = .7f), minLines = 3, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
            else -> lists.take(30).forEach { l ->
                Chip(l.name + listOfNotNull(l.by.takeIf { it.isNotBlank() }?.let { " · @$it" }, " · ${l.items} titles").joinToString(""), modifier = Modifier.fillMaxWidth(), maxLines = 1) {
                    onChoose(WidgetDrafts.trakt(l, media))
                }
            }
        }
    }
}
