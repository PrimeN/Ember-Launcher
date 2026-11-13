package app.ember.tv.ui

import androidx.compose.ui.res.stringResource
import app.ember.tv.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

@Composable
internal fun CollectionEditor(vm: HomeViewModel, original: MediaCollection?, close: () -> Unit) {
    val owner = vm.profiles.profileId
    var draft by remember(owner, original?.id) { mutableStateOf(original ?: CollectionDrafts.collection()) }
    var folder by remember { mutableStateOf<CollectionFolder?>(null) }
    var returnFolder by remember { mutableStateOf<String?>(null) }
    val restoreFocus = remember { FocusRequester() }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun dismiss() { if (!saving) { if (draft != original && draft != CollectionDrafts.collection().copy(id = draft.id)) discard = true else close() } }
    if (folder != null) {
        CollectionFolderEditor(vm, folder!!, onSave = { edited ->
            draft = draft.copy(folders = if (draft.folders.any { it.id == edited.id }) draft.folders.map { if (it.id == edited.id) edited else it } else draft.folders + edited)
            returnFolder = edited.id; folder = null
        }, close = { returnFolder = folder?.id?.takeIf { id -> draft.folders.any { it.id == id } }; folder = null })
    } else MediaDialog(if (original == null) "Create collection" else "Edit collection", ::dismiss, resizeForKeyboard = true, initialFocus = restoreFocus.takeIf { returnFolder != null }, wide = true) {
        Text(stringResource(R.string.tx_create_folders_from_catalogs_and_discover), color = Mute, fontSize = 13.sp)
        TvTextField(draft.title, { draft = draft.copy(title = it.take(100)) }, Modifier.fillMaxWidth(), placeholder = "Collection name")
        ButtonRow {
            Chip(if (saving) "Saving…" else "Save collection") {
                if (!saving) { saving = true; scope.launch {
                    error = if (owner != vm.profiles.profileId) "The profile changed. Reopen the editor." else vm.collections.saveCollection(draft, original)
                    saving = false; if (error == null) close()
                } }
            }
            Chip("Add folder") { folder = CollectionDrafts.folder() }
        }
        error?.let { Text(it, color = Mute, fontSize = 13.sp) }
        SwitchChip("Pin above catalogs", draft.pinToTop) { draft = draft.copy(pinToTop = !draft.pinToTop) }
        SwitchChip("Combined All tab", draft.showAllTab) { draft = draft.copy(showAllTab = !draft.showAllTab) }
        ButtonRow {
            listOf("TABBED_GRID" to "Tabs", "ROWS" to "Rows", "FOLLOW_LAYOUT" to "Follow layout").forEach { (id, label) ->
                Chip(label, draft.viewMode == id) { draft = draft.copy(viewMode = id) }
            }
        }
        TvTextField(draft.backdropImageUrl.orEmpty(), { draft = draft.copy(backdropImageUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Backdrop URL (optional)")
        if (draft.folders.isEmpty()) Text(stringResource(R.string.tx_add_at_least_one_folder_then), color = Mute)
        draft.folders.forEachIndexed { index, item ->
            Column(Modifier.fillMaxWidth().background(PanelHi, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, fontSize = 16.sp)
                Text(stringResource(R.string.tx_sources, item.allSources.size, item.shape), color = Mute, fontSize = 12.sp)
                ButtonRow(spacing = 6.dp) {
                    Chip("Edit", modifier = if (returnFolder == item.id) Modifier.focusRequester(restoreFocus) else Modifier) { folder = item }
                    MoveButton(item.title, index, draft.folders.lastIndex) { step -> draft = draft.copy(folders = draft.folders.moved(index, step)) }
                    Chip("Duplicate") { draft = draft.copy(folders = draft.folders + CollectionDrafts.duplicate(item).copy(title = item.title + " (copy)")) }
                    Chip("Remove") { draft = draft.copy(folders = draft.folders.filterNot { it.id == item.id }) }
                }
            }
        }
    }
    if (discard) MediaDialog("Discard unsaved changes?", { discard = false }) {
        Chip("Keep editing") { discard = false }
        Chip("Discard changes") { discard = false; close() }
    }
}

/** The folder editor's parts (#185). */
internal object FolderSteps {
    const val LOOK = "look"; const val SOURCES = "sources"; const val ARTWORK = "artwork"
    val all = listOf(LOOK to "Look", SOURCES to "Sources", ARTWORK to "Artwork")
    val shapes = listOf("SQUARE" to "Square", "LANDSCAPE" to "Wide", "POSTER" to "Poster")
    /** The tile's picture in the shape picker, width to height in dp. */
    fun size(shape: String): Pair<Int, Int> = when (shape) { "LANDSCAPE" -> 80 to 45; "POSTER" -> 36 to 54; else -> 50 to 50 }
}

/** A folder tile shape as a small picture of itself (#185). */
@Composable
private fun ShapeTile(shape: String, name: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, modifier = Modifier.width(132.dp).testTag("folder_shape_$shape"), radius = TvTokens.Shape.Card, container = if (selected) WashHi else PanelHi) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                val (w, h) = FolderSteps.size(shape)
                Box(Modifier.width(w.dp).height(h.dp).background(ink.copy(alpha = .45f), RoundedCornerShape(5.dp)))
            }
            Text(if (selected) "$name  ✓" else name, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun CollectionFolderEditor(vm: HomeViewModel, initial: CollectionFolder, onSave: (CollectionFolder) -> Unit, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    // #185: one part at a time, as the widget editor works.
    var step by remember(initial.id) { mutableStateOf(FolderSteps.LOOK) }
    fun dismiss() { if (draft != initial) discard = true else close() }
    if (editingSource != null) CollectionSourceEditor(vm, draft.allSources[editingSource!!], close = { editingSource = null }) { edited ->
        val index = editingSource!!
        if (draft.allSources.filterIndexed { i, _ -> i != index }.any { vm.collections.sourceKey(it) == vm.collections.sourceKey(edited) }) message = "This source is already in the folder."
        else draft = draft.copy(sources = draft.allSources.mapIndexed { i, s -> if (i == index) edited else s }, catalogSources = emptyList())
        editingSource = null
    } else if (picker) CollectionSourcePicker(vm, onChoose = { source ->
        if (draft.allSources.none { vm.collections.sourceKey(it) == vm.collections.sourceKey(source) }) draft = draft.copy(sources = draft.allSources + source, catalogSources = emptyList())
        picker = false
    }, close = { picker = false })
    else MediaDialog("Edit folder", ::dismiss, resizeForKeyboard = true, wide = true) {
        ButtonRow { FolderSteps.all.forEachIndexed { i, (id, name) ->
            Chip("${i + 1}  $name" + if (id == FolderSteps.SOURCES) " (${draft.allSources.size})" else "", step == id, modifier = Modifier.testTag("folder_step_$id")) { step = id }
        } }
        if (step == FolderSteps.LOOK) {
        TvTextField(draft.title, { draft = draft.copy(title = it.take(100)) }, Modifier.fillMaxWidth(), placeholder = "Folder name")
        Text(stringResource(R.string.tx_tile_shape), color = Mute, fontSize = 13.sp)
        ButtonRow { FolderSteps.shapes.forEach { (shape, name) -> ShapeTile(shape, name, draft.tileShape == shape) { draft = draft.copy(tileShape = shape) } } }
        TvTextField(draft.coverEmoji.orEmpty(), { draft = draft.copy(coverEmoji = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Cover emoji (optional)")
        SwitchChip("Hide folder title", draft.hideTitle) { draft = draft.copy(hideTitle = !draft.hideTitle) }
        ButtonRow { Chip("Preview cover") { preview = !preview } }
        if (preview) Box(Modifier.size(150.dp).background(PanelHi, RoundedCornerShape(12.dp))) {
            if (!draft.coverImageUrl.isNullOrBlank()) CollectionImage(draft.coverImageUrl!!, draft.title, Modifier.fillMaxSize(), animate = vm.collections.heroPreviews && !vm.profiles.profile.kids)
            else Text(draft.coverEmoji ?: draft.title, Modifier.padding(12.dp), fontSize = 24.sp)
        }
        }
        if (step == FolderSteps.ARTWORK) {
        TvTextField(draft.coverImageUrl.orEmpty(), { draft = draft.copy(coverImageUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Cover image URL (optional)")
        TvTextField(draft.focusGifUrl.orEmpty(), { draft = draft.copy(focusGifUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Focus video/GIF URL (optional)")
        SwitchChip("Animate cover when focused", draft.focusGifEnabled != false) { draft = draft.copy(focusGifEnabled = draft.focusGifEnabled == false) }
        TvTextField(draft.heroBackdropUrl.orEmpty(), { draft = draft.copy(heroBackdropUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Folder backdrop URL (optional)")
        TvTextField(draft.heroVideoUrl.orEmpty(), { draft = draft.copy(heroVideoUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Hero video/GIF URL (optional, Animated collections must be on)")
        TvTextField(draft.titleLogoUrl.orEmpty(), { draft = draft.copy(titleLogoUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Title logo URL (optional)")
        }
        if (step == FolderSteps.SOURCES) {
        ButtonRow { Chip("Add source") { picker = true } }
        if (draft.allSources.isEmpty()) Text(stringResource(R.string.tx_add_a_catalog_or_search_to), color = Mute)
        draft.allSources.forEachIndexed { index, source ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Collections.label(source, vm.allCatalogs), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ButtonRow(spacing = 6.dp) {
                    Chip("Edit source") { editingSource = index }
                    MoveButton(Collections.label(source, vm.allCatalogs), index, draft.allSources.lastIndex) { step -> draft = draft.copy(sources = draft.allSources.moved(index, step), catalogSources = emptyList()) }
                    Chip("Remove source") { draft = draft.copy(sources = draft.allSources.filterIndexed { i, _ -> i != index }, catalogSources = emptyList()) }
                }
            }
        }
        }
        message?.let { Text(it, color = Mute, fontSize = 13.sp) }
        ButtonRow {
            Chip("Save folder") {
                val check = Collections.inspect(Collections.encode(listOf(MediaCollection("draft", "Draft", folders = listOf(draft)))))
                message = check.error
                if (message == null) onSave(draft)
                // A folder with nothing in it is the usual reason; show where to fix it.
                else if (draft.allSources.isEmpty()) step = FolderSteps.SOURCES
            }
            Chip("Cancel") { dismiss() }
        }
    }
    if (discard) MediaDialog("Discard folder changes?", { discard = false }) {
        Chip("Keep editing") { discard = false }
        Chip("Discard changes") { discard = false; close() }
    }
}

@Composable
internal fun AddToCollection(vm: HomeViewModel, source: CollectionSource, close: () -> Unit) {
    val owner = vm.profiles.profileId
    var collection by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var collectionName by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf(source.title.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MediaDialog("Add to collection", close, resizeForKeyboard = true, wide = true) {
        Text(source.title ?: "Selected source", fontSize = 16.sp)
        ButtonRow {
            Chip("New collection", collection == null) { collection = null; folder = null }
            vm.collections.list.forEach { item -> Chip(item.title, item.id == collection) { collection = item.id; folder = null } }
        }
        if (collection == null) TvTextField(collectionName, { collectionName = it.take(100) }, Modifier.fillMaxWidth(), placeholder = "New collection name")
        val selected = vm.collections.list.firstOrNull { it.id == collection }
        ButtonRow {
            Chip("New folder", folder == null) { folder = null }
            selected?.folders?.forEach { item -> Chip(item.title, item.id == folder) { folder = item.id } }
        }
        if (folder == null) TvTextField(folderName, { folderName = it.take(100) }, Modifier.fillMaxWidth(), placeholder = "New folder name")
        error?.let { Text(it, color = Mute, fontSize = 13.sp) }
        Chip(if (saving) "Saving…" else "Add source") { if (!saving) { saving = true; scope.launch {
            error = if (owner != vm.profiles.profileId) "The profile changed. Reopen this action." else vm.collections.addSource(collection, folder, collectionName, folderName, source)
            saving = false; if (error == null) close()
        } } }
    }
}

@Composable
internal fun CatalogOptions(def: CatalogDef, initial: Map<String, String>, close: () -> Unit, apply: (Map<String, String>) -> Unit) {
    var values by remember(def.key) { mutableStateOf(def.requestExtras + initial) }
    var error by remember { mutableStateOf<String?>(null) }
    MediaDialog("Catalog filters", close, resizeForKeyboard = true) {
        Text(def.label, fontSize = 16.sp)
        def.extras.forEach { extra ->
            var optionSearch by remember(extra.name) { mutableStateOf("") }
            if (extra.options.size > 30) TvTextField(optionSearch, { optionSearch = it }, Modifier.fillMaxWidth(), placeholder = "Find ${extra.name}")
            Text(extra.name.replaceFirstChar { it.uppercase() } + (if (extra.required) " · Required" else "") + (if (extra.limit > 1) " · up to ${extra.limit}" else ""), fontSize = 14.sp)
            if (extra.options.isNotEmpty()) ButtonRow(spacing = 6.dp) {
                if (!extra.required) Chip("Any", values[extra.name].isNullOrBlank()) { values = values + (extra.name to "") }
                val chosen = app.ember.tv.data.extraValues(values[extra.name].orEmpty())
                extra.options.filter { it.contains(optionSearch, true) }.take(40).forEach { value ->
                    Chip(value, value in chosen) {
                        // Several at once where the catalog allows it (optionsLimit, #72).
                        val next = when {
                            extra.limit <= 1 -> listOf(value)
                            value in chosen -> chosen - value
                            chosen.size < extra.limit -> chosen + value
                            else -> chosen.drop(1) + value
                        }
                        values = values + (extra.name to next.joinToString(app.ember.tv.data.EXTRA_VALUES_SEPARATOR.toString()))
                    }
                }
            } else TvTextField(values[extra.name].orEmpty(), { values = values + (extra.name to it) }, Modifier.fillMaxWidth(), placeholder = extra.name, password = CollectionSecrets.sensitive(extra.name))
        }
        error?.let { Text(it, color = Mute) }
        Chip("Apply filters") { error = def.extraError(values); if (error == null) apply(values) }
    }
}

@Composable
internal fun CollectionSourcePicker(vm: HomeViewModel, onChoose: (CollectionSource) -> Unit, close: () -> Unit) {
    var mode by remember { mutableStateOf("Catalogs") }
    var query by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<CatalogDef?>(null) }
    var kind by remember { mutableStateOf("COLLECTION") }
    var media by remember { mutableStateOf("MOVIE") }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<app.ember.tv.discover.ExploreRef>>(emptyList()) }
    LaunchedEffect(query, mode, kind) {
        found = emptyList()
        if (mode == "TMDB" && kind in setOf("COMPANY", "PERSON", "DIRECTOR") && query.trim().length >= 2) {
            delay(350)
            val result = runCatching { vm.exploreRepository.search(query.trim(), kind == "COMPANY") }.getOrDefault(emptyList()).take(12)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            found = result
        }
    }
    if (catalog != null) CatalogOptions(catalog!!, emptyMap(), { catalog = null }) { values ->
        val c = catalog!!; onChoose(CollectionDrafts.catalog(c, values["genre"], values - "genre"))
    } else MediaDialog("Choose a source", close, resizeForKeyboard = true, wide = true) {
        ButtonRow { listOf("Catalogs", "Saved searches", "TMDB", "Trakt").forEach { value -> Chip(value, mode == value) { mode = value; query = "" } } }
        if (mode == "Catalogs") {
            TvTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Find catalogs")
            val matches = vm.collectionCatalogs.filter { (it.label + it.addon.name).contains(query, true) }
            Text(stringResource(R.string.tx_catalogs, matches.size) + if (matches.size > 60) " · First 60 shown; search to narrow the list." else "", fontSize = 12.sp, color = Mute)
            matches.take(60).forEach { c -> Chip(c.label + " · " + c.addon.name) {
                if (c.extras.isEmpty()) onChoose(CollectionDrafts.catalog(c)) else catalog = c
            } }
        } else if (mode == "Saved searches") {
            val saved = vm.collectionSearches
            if (saved.isEmpty()) Text(stringResource(R.string.tx_save_a_search_in_discover_first), color = Mute)
            saved.forEach { search -> Chip(search.name) { onChoose(CollectionDrafts.discover(search.name, search.query, vm.profiles.profileId)) } }
        } else {
            if (mode == "TMDB") ButtonRow {
                listOf("COLLECTION", "LIST", "COMPANY", "NETWORK", "PERSON", "DIRECTOR").forEach { value -> Chip(value.lowercase().replaceFirstChar { it.uppercase() }, kind == value) { kind = value; if (value == "NETWORK") media = "TV" else if (value == "COLLECTION") media = "MOVIE" } }
            }
            TvTextField(name, { name = it.take(100) }, Modifier.fillMaxWidth(), placeholder = "Source name")
            if (mode == "TMDB" && kind in setOf("COMPANY", "PERSON", "DIRECTOR")) {
                TvTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search by name")
                found.forEach { ref -> Chip(ref.name) { id = ref.id.toString(); if (name.isBlank()) name = ref.name; found = emptyList() } }
            }
            TvTextField(id, { id = it }, Modifier.fillMaxWidth(), placeholder = if (mode == "TMDB") "TMDB ID or URL" else "Trakt list ID or numeric list URL")
            if (mode == "Trakt" || kind !in setOf("COLLECTION", "NETWORK")) ButtonRow { listOf("MOVIE" to "Movies", "TV" to "Shows").forEach { (key, label) -> Chip(label, media == key) { media = key } } }
            error?.let { Text(it, color = Mute) }
            Chip("Use source") {
                val number = CollectionDrafts.numericId(id)
                error = when { name.isBlank() -> "Enter a source name."; number == null -> "Enter a positive numeric ID or URL containing it."; mode == "TMDB" && number > Int.MAX_VALUE -> "That TMDB ID is too large."; else -> null }
                if (error == null) onChoose(if (mode == "TMDB") CollectionSource(provider = "tmdb", title = name.trim(), tmdbSourceType = kind, tmdbId = number!!.toInt(), mediaType = media)
                    else CollectionSource(provider = "trakt", title = name.trim(), traktListId = number, mediaType = media))
            }
        }
    }
}
