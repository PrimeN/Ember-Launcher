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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun CollectionEditor(vm: HomeViewModel, original: MediaCollection?, close: () -> Unit) {
    val owner = vm.profiles.profileId
    var draft by remember(owner, original?.id) { mutableStateOf(original ?: CollectionDrafts.collection()) }
    var folder by remember { mutableStateOf<CollectionFolder?>(null) }
    var returnFolder by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf<CollectionFolder?>(null) }
    var moving by remember { mutableStateOf<CollectionFolder?>(null) }
    val restoreFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun dismiss() { if (!saving) { if (draft != original && draft != CollectionDrafts.collection().copy(id = draft.id)) discard = true else close() } }
    fun save() {
        if (saving) return
        CollectionEditing.problem(draft)?.let { error = it; return }
        saving = true; scope.launch {
            error = if (owner != vm.profiles.profileId) "The profile changed. Reopen the editor." else vm.collections.saveCollection(draft, original)
            saving = false; if (error == null) close()
        }
    }
    if (folder != null) {
        CollectionFolderEditor(vm, folder!!, onSave = { edited ->
            draft = draft.copy(folders = if (draft.folders.any { it.id == edited.id }) draft.folders.map { if (it.id == edited.id) edited else it } else draft.folders + edited)
            returnFolder = edited.id; folder = null; error = null
        }, close = { returnFolder = folder?.id?.takeIf { id -> draft.folders.any { it.id == id } }; folder = null })
    } else MediaDialog(if (original == null) "Create collection" else "Edit collection", ::dismiss, resizeForKeyboard = true, wide = true, scrollBody = false,
        initialFocus = if (returnFolder != null) restoreFocus else nameFocus) {
        // #468: laid out like the widget editor: headed parts, picture cards, one footer.
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorHeading("Name", "What the collection is called on Home and in Discover.")
            TvTextField(draft.title, { draft = draft.copy(title = it.take(100)); error = null }, Modifier.width(560.dp).testTag("collection_name"),
                placeholder = "for example Streaming services", focusRequester = nameFocus)

            EditorHeading("Folders", if (draft.folders.isEmpty()) "Each folder opens catalogs, lists or searches. Add the first one."
                else draft.folders.size.counted("folder") + ". OK on a folder to edit, move, duplicate or remove it.")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                draft.folders.forEach { item ->
                    FolderCard(vm, item, moving = moving?.id == item.id,
                        modifier = (if (returnFolder == item.id) Modifier.focusRequester(restoreFocus) else Modifier).testTag("collection_folder_${item.id}")) {
                        if (moving == null) folderMenu = item
                    }
                }
                ChoiceCard("Add folder", "Catalogs, your lists or searches", false, Modifier.testTag("collection_add_folder"), width = 196.dp,
                    icon = Icons.Outlined.CreateNewFolder) { folder = CollectionDrafts.folder() }
            }

            EditorHeading("Opening a folder", "How its titles show.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CollectionEditing.layouts.forEach { (id, name, about) ->
                    ChoiceCard(name, about, draft.viewMode == id, Modifier.testTag("collection_layout_$id"), icon = CollectionEditing.layoutIcon(id)) { draft = draft.copy(viewMode = id) }
                }
            }
            EditorSwitch("Combined All tab", "A first tab with every folder's titles together.", draft.showAllTab) { draft = draft.copy(showAllTab = !draft.showAllTab) }

            EditorHeading("On Home")
            EditorSwitch("Pin above catalogs", "Shows this collection before the catalog rows.", draft.pinToTop) { draft = draft.copy(pinToTop = !draft.pinToTop) }
            TvTextField(draft.backdropImageUrl.orEmpty(), { draft = draft.copy(backdropImageUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Backdrop picture link (optional)")
        }
        EditorFooter("Cancel", ::dismiss, error ?: CollectionEditing.problem(draft).takeIf { draft.title.isNotBlank() || draft.folders.isNotEmpty() }) {
            PrimaryAction(if (saving) "Saving…" else "Save collection", CollectionEditing.problem(draft) == null, Modifier.testTag("collection_save")) { save() }
        }
    }
    folderMenu?.let { item ->
        val index = draft.folders.indexOfFirst { it.id == item.id }
        OptionMenu(item.title, listOfNotNull(
            MenuOption("Edit folder") { folderMenu = null; folder = item },
            MenuOption("Move") { folderMenu = null; moving = item }.takeIf { draft.folders.size > 1 },
            MenuOption("Duplicate") { folderMenu = null; draft = draft.copy(folders = draft.folders + CollectionDrafts.duplicate(item).copy(title = item.title + " (copy)")) },
            MenuOption("Remove") { folderMenu = null; draft = draft.copy(folders = draft.folders.filterNot { it.id == item.id }) },
        )) { folderMenu = null }
        if (index < 0) folderMenu = null
    }
    moving?.let { item ->
        val index = draft.folders.indexOfFirst { it.id == item.id }
        if (index < 0) moving = null
        else OptionMenu("Move ${item.title}", moveOptions(index, draft.folders.lastIndex, horizontal = true).map { (label, step) ->
            MenuOption(label) { draft = draft.copy(folders = draft.folders.moved(index, step)); moving = null; returnFolder = item.id }
        }) { moving = null }
    }
    if (discard) MediaDialog("Discard unsaved changes?", { discard = false }) {
        Chip("Keep editing") { discard = false }
        Chip("Discard changes") { discard = false; close() }
    }
}

/** #468: what the collection editor needs to know about a draft. */
internal object CollectionEditing {
    data class Layout(val id: String, val name: String, val about: String)
    val layouts = listOf(
        Layout("TABBED_GRID", "Tabs", "One tab per source, titles in a grid"),
        Layout("ROWS", "Rows", "One row per source"),
        Layout("FOLLOW_LAYOUT", "Follow layout", "As Home's layout setting"),
    )
    fun layoutIcon(id: String) = when (id) { "ROWS" -> Icons.Outlined.ViewAgenda; "FOLLOW_LAYOUT" -> Icons.Outlined.AutoAwesomeMosaic; else -> Icons.Outlined.Tab }
    /** Why the draft can't be saved yet, or null. */
    fun problem(c: MediaCollection): String? = when {
        c.title.isBlank() -> "Give the collection a name."
        c.folders.isEmpty() -> "Add at least one folder."
        else -> null
    }
    /** The kind of source, under its name. */
    fun sourceAbout(s: CollectionSource): String = when (s.provider.lowercase()) {
        "tmdb" -> "TMDB"
        "trakt" -> "Trakt list"
        "ember" -> "Saved Discover search"
        LibraryLists.PROVIDER -> "Your list"
        else -> listOfNotNull("Catalog", s.genre?.takeIf { it.isNotBlank() && it != "None" }).joinToString(" · ")
    }
    /** An icon for each kind of source. */
    fun sourceIcon(s: CollectionSource) = when (s.provider.lowercase()) {
        "tmdb" -> Icons.Outlined.Movie
        "trakt" -> Icons.Outlined.PlaylistPlay
        "ember" -> Icons.Outlined.Tune
        LibraryLists.PROVIDER -> Icons.Outlined.Bookmarks
        else -> Icons.Outlined.Widgets
    }
}

/** #468: a folder as it will look: its cover in its shape, its name and how many sources it reads. */
@Composable
private fun FolderCard(vm: HomeViewModel, item: CollectionFolder, moving: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (w, h) = FolderSteps.size(item.tileShape)
    Focusable(onClick = onClick, modifier = modifier.width(196.dp).jiggle(moving), radius = TvTokens.Shape.Card, container = PanelHi,
        focusedContainer = PillColor, focusedContent = OnPillColor, outline = false, focusedScale = TvTokens.Focus.Card) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width((w * 1.6f).dp).height((h * 1.6f).dp).clip(RoundedCornerShape(8.dp)).background(Wash), contentAlignment = Alignment.Center) {
                    if (!item.coverImageUrl.isNullOrBlank()) CollectionImage(item.coverImageUrl!!, item.title, Modifier.fillMaxSize(), animate = false)
                    else Text(item.coverEmoji ?: item.title.take(1).uppercase(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.allSources.size.counted("source") + " · " + FolderSteps.shapes.firstOrNull { it.first == item.tileShape }?.second.orEmpty(),
                fontSize = 12.sp, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = .7f), maxLines = 1)
        }
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

@Composable
internal fun CollectionFolderEditor(vm: HomeViewModel, initial: CollectionFolder, onSave: (CollectionFolder) -> Unit, close: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var picker by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<Int?>(null) }
    var sourceMenu by remember { mutableStateOf<Int?>(null) }
    var movingSource by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    // #185: one part at a time, as the widget editor works.
    var step by remember(initial.id) { mutableStateOf(FolderSteps.LOOK) }
    val stepFocus = remember { FocusRequester() }
    fun dismiss() { if (draft != initial) discard = true else close() }
    fun save() {
        val check = Collections.inspect(Collections.encode(listOf(MediaCollection("draft", "Draft", folders = listOf(draft)))))
        message = check.error
        if (message == null) onSave(draft)
        // A folder with nothing in it is the usual reason; show where to fix it.
        else if (draft.allSources.isEmpty()) { step = FolderSteps.SOURCES; message = "Add at least one source." }
    }
    if (editingSource != null) CollectionSourceEditor(vm, draft.allSources[editingSource!!], close = { editingSource = null }) { edited ->
        val index = editingSource!!
        if (draft.allSources.filterIndexed { i, _ -> i != index }.any { vm.collections.sourceKey(it) == vm.collections.sourceKey(edited) }) message = "This source is already in the folder."
        else draft = draft.copy(sources = draft.allSources.mapIndexed { i, s -> if (i == index) edited else s }, catalogSources = emptyList())
        editingSource = null
    } else if (picker) CollectionSourcePicker(vm, onChoose = { source ->
        if (draft.allSources.none { vm.collections.sourceKey(it) == vm.collections.sourceKey(source) }) draft = draft.copy(sources = draft.allSources + source, catalogSources = emptyList())
        picker = false; message = null
    }, close = { picker = false }, onChooseMany = { sources ->
        // #264: several catalogs at once; ones already in the folder are skipped.
        val fresh = sources.filter { s -> draft.allSources.none { vm.collections.sourceKey(it) == vm.collections.sourceKey(s) } }
        if (fresh.isNotEmpty()) draft = draft.copy(sources = draft.allSources + fresh, catalogSources = emptyList())
        message = if (fresh.size < sources.size) "Sources already in the folder were skipped." else null
        picker = false
    })
    else MediaDialog(if (initial.title.isBlank()) "New folder" else "Edit folder", ::dismiss, resizeForKeyboard = true, wide = true, scrollBody = false, initialFocus = stepFocus) {
        // #468: the same steps, headings and footer as the widget editor.
        EditorSteps(FolderSteps.all, step, "folder_step_",
            done = { id -> when (id) { FolderSteps.LOOK -> draft.title.isNotBlank(); FolderSteps.SOURCES -> draft.allSources.isNotEmpty(); else -> false } },
            badge = { id -> if (id == FolderSteps.SOURCES) "${draft.allSources.size}" else null }) { step = it; message = null }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (step) {
                FolderSteps.LOOK -> Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        EditorHeading("Look", "The folder's name and the shape of its tile.")
                        TvTextField(draft.title, { draft = draft.copy(title = it.take(100)); message = null }, Modifier.fillMaxWidth().testTag("folder_name"),
                            placeholder = "Folder name", focusRequester = stepFocus)
                        EditorLabel(stringResource(R.string.tx_tile_shape))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FolderSteps.shapes.forEach { (shape, name) ->
                                ChoiceCard(name, null, draft.tileShape == shape, Modifier.testTag("folder_shape_$shape"), width = 140.dp, picture = {
                                    val (w, h) = FolderSteps.size(shape)
                                    Box(Modifier.width(w.dp).height(h.dp).background(androidx.tv.material3.LocalContentColor.current.copy(alpha = .45f), RoundedCornerShape(5.dp)))
                                }) { draft = draft.copy(tileShape = shape) }
                            }
                        }
                        TvTextField(draft.coverEmoji.orEmpty(), { draft = draft.copy(coverEmoji = it.ifBlank { null }) }, Modifier.width(320.dp), placeholder = "Cover emoji (optional)")
                        EditorSwitch("Hide folder title", "The tile shows only its picture.", draft.hideTitle) { draft = draft.copy(hideTitle = !draft.hideTitle) }
                    }
                    // What the tile will look like, always in view.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditorLabel("Preview")
                        val (w, h) = FolderSteps.size(draft.tileShape)
                        Box(Modifier.width((w * 3).dp).height((h * 3).dp).clip(RoundedCornerShape(12.dp)).background(PanelHi), contentAlignment = Alignment.Center) {
                            if (!draft.coverImageUrl.isNullOrBlank()) CollectionImage(draft.coverImageUrl!!, draft.title, Modifier.fillMaxSize(), animate = vm.collections.heroPreviews && !vm.profiles.profile.kids)
                            else Text(draft.coverEmoji ?: draft.title.ifBlank { "?" }.take(1).uppercase(), fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (!draft.hideTitle) Text(draft.title.ifBlank { "Folder name" }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                FolderSteps.SOURCES -> {
                    EditorHeading("Sources", if (draft.allSources.isEmpty()) stringResource(R.string.tx_add_a_catalog_or_search_to)
                        else draft.allSources.size.counted("source") + ", read in this order. OK on one to edit, move or remove it.")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        draft.allSources.forEachIndexed { index, source ->
                            ChoiceCard(Collections.label(source, vm.allCatalogs), CollectionEditing.sourceAbout(source), false,
                                (if (index == 0) Modifier.focusRequester(stepFocus) else Modifier).testTag("folder_source_$index").jiggle(movingSource == index),
                                width = 260.dp, icon = CollectionEditing.sourceIcon(source)) { if (movingSource == null) sourceMenu = index }
                        }
                        ChoiceCard("Add source", "Catalogs, your lists, searches, TMDB or Trakt", false,
                            (if (draft.allSources.isEmpty()) Modifier.focusRequester(stepFocus) else Modifier).testTag("folder_add_source"), width = 260.dp,
                            icon = Icons.Outlined.AddCircleOutline) { picker = true }
                    }
                }
                else -> {
                    EditorHeading("Artwork", "Optional pictures and video links for the tile and the open folder.")
                    TvTextField(draft.coverImageUrl.orEmpty(), { draft = draft.copy(coverImageUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Cover picture link", focusRequester = stepFocus)
                    TvTextField(draft.focusGifUrl.orEmpty(), { draft = draft.copy(focusGifUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Video or GIF while focused")
                    EditorSwitch("Animate cover when focused", null, draft.focusGifEnabled != false) { draft = draft.copy(focusGifEnabled = draft.focusGifEnabled == false) }
                    TvTextField(draft.heroBackdropUrl.orEmpty(), { draft = draft.copy(heroBackdropUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Backdrop of the open folder")
                    TvTextField(draft.heroVideoUrl.orEmpty(), { draft = draft.copy(heroVideoUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Video or GIF behind the open folder (Animated collections must be on)")
                    TvTextField(draft.titleLogoUrl.orEmpty(), { draft = draft.copy(titleLogoUrl = it.ifBlank { null }) }, Modifier.fillMaxWidth(), placeholder = "Title logo link")
                }
            }
        }
        EditorFooter("Cancel", ::dismiss, message) {
            val next = FolderSteps.all.map { it.first }.let { ids -> ids.getOrNull(ids.indexOf(step) + 1) }
            if (next != null) Chip("Next: " + FolderSteps.all.first { it.first == next }.second, modifier = Modifier.testTag("folder_next")) { step = next; message = null }
            PrimaryAction("Save folder", draft.title.isNotBlank() && draft.allSources.isNotEmpty(), Modifier.testTag("folder_save")) { save() }
        }
    }
    sourceMenu?.let { index ->
        val source = draft.allSources.getOrNull(index)
        if (source == null) sourceMenu = null
        else OptionMenu(Collections.label(source, vm.allCatalogs), listOfNotNull(
            MenuOption("Edit source") { sourceMenu = null; editingSource = index },
            MenuOption("Move") { sourceMenu = null; movingSource = index }.takeIf { draft.allSources.size > 1 },
            MenuOption("Remove source") { sourceMenu = null; draft = draft.copy(sources = draft.allSources.filterIndexed { i, _ -> i != index }, catalogSources = emptyList()) },
        )) { sourceMenu = null }
    }
    movingSource?.let { index ->
        if (index !in draft.allSources.indices) movingSource = null
        else OptionMenu("Move " + Collections.label(draft.allSources[index], vm.allCatalogs), moveOptions(index, draft.allSources.lastIndex, horizontal = true).map { (label, by) ->
            MenuOption(label) { draft = draft.copy(sources = draft.allSources.moved(index, by), catalogSources = emptyList()); movingSource = null }
        }) { movingSource = null }
    }
    LaunchedEffect(step) { withFrameNanos { }; runCatching { stepFocus.requestFocus() } }
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
    fun add() { if (!saving) { saving = true; scope.launch {
        error = if (owner != vm.profiles.profileId) "The profile changed. Reopen this action." else vm.collections.addSource(collection, folder, collectionName, folderName, source)
        saving = false; if (error == null) close()
    } } }
    // #468: picture cards and the editors' footer, as in the collection editor.
    MediaDialog("Add to collection", close, resizeForKeyboard = true, wide = true, scrollBody = false) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            EditorHeading("Collection", "Adding " + (source.title ?: "this source") + ".")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard("New collection", null, collection == null, icon = Icons.Outlined.CreateNewFolder, width = 196.dp) { collection = null; folder = null }
                vm.collections.list.forEach { item ->
                    ChoiceCard(item.title, item.folders.size.counted("folder"), item.id == collection, icon = Icons.Outlined.Folder, width = 196.dp) { collection = item.id; folder = null }
                }
            }
            if (collection == null) TvTextField(collectionName, { collectionName = it.take(100) }, Modifier.width(560.dp), placeholder = "New collection name")
            val selected = vm.collections.list.firstOrNull { it.id == collection }
            EditorHeading("Folder", "The source joins this folder's tabs.")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard("New folder", null, folder == null, icon = Icons.Outlined.AddCircleOutline, width = 196.dp) { folder = null }
                selected?.folders?.forEach { item ->
                    ChoiceCard(item.title, item.allSources.size.counted("source"), item.id == folder, icon = Icons.Outlined.FolderOpen, width = 196.dp) { folder = item.id }
                }
            }
            if (folder == null) TvTextField(folderName, { folderName = it.take(100) }, Modifier.width(560.dp), placeholder = "New folder name")
        }
        EditorFooter("Cancel", close, error) { PrimaryAction(if (saving) "Adding…" else "Add source") { add() } }
    }
}

@Composable
internal fun CatalogOptions(def: CatalogDef, initial: Map<String, String>, close: () -> Unit, apply: (Map<String, String>) -> Unit) {
    var values by remember(def.key) { mutableStateOf(def.requestExtras + initial) }
    var error by remember { mutableStateOf<String?>(null) }
    // #468: each filter a headed part, the editors' footer under them.
    MediaDialog("Catalog filters", close, resizeForKeyboard = true, wide = true, scrollBody = false) {
        Text(def.label + " · " + def.addon.name, fontSize = 15.sp, color = Mute)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            def.extras.forEach { extra ->
                var optionSearch by remember(extra.name) { mutableStateOf("") }
                EditorHeading(extra.name.replaceFirstChar { it.uppercase() },
                    listOfNotNull("Required".takeIf { extra.required }, "up to ${extra.limit}".takeIf { extra.limit > 1 },
                        "${extra.options.size} choices".takeIf { extra.options.size > 12 }).joinToString(" · ").ifBlank { null })
                if (extra.options.size > 30) TvTextField(optionSearch, { optionSearch = it }, Modifier.width(420.dp), placeholder = "Find ${extra.name}")
                if (extra.options.isNotEmpty()) ButtonRow(spacing = 8.dp) {
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
                            values = values + (extra.name to next.joinToString(app.ember.tv.data.EXTRA_VALUES_SEPARATOR.toString())); error = null
                        }
                    }
                } else TvTextField(values[extra.name].orEmpty(), { values = values + (extra.name to it); error = null }, Modifier.width(560.dp), placeholder = extra.name, password = CollectionSecrets.sensitive(extra.name))
            }
        }
        EditorFooter("Cancel", close, error) {
            PrimaryAction("Apply filters", def.extraError(values) == null, Modifier.testTag("catalog_filters_apply")) { error = def.extraError(values); if (error == null) apply(values) }
        }
    }
}

/** #264: picking several sources in one pass. */
internal object SourcePicks {
    /** The same catalog, search or list, whatever its options. */
    fun same(a: CollectionSource, b: CollectionSource) = a.provider == b.provider && a.addonId == b.addonId && a.type == b.type &&
        a.catalogId == b.catalogId && a.tmdbSourceType == b.tmdbSourceType && a.tmdbId == b.tmdbId && a.traktListId == b.traktListId && a.exploreQuery == b.exploreQuery
    /** Adds [source], or takes it out when it is already picked. */
    fun toggle(picked: List<CollectionSource>, source: CollectionSource) =
        if (picked.any { same(it, source) }) picked.filterNot { same(it, source) } else picked + source
    fun count(n: Int) = when (n) { 0 -> "Choose as many as you like"; 1 -> "1 selected"; else -> "$n selected" }
    fun addLabel(n: Int) = if (n == 1) "Add 1 source" else "Add $n sources"
}

@Composable
internal fun CollectionSourcePicker(vm: HomeViewModel, onChoose: (CollectionSource) -> Unit, close: () -> Unit,
    /** #264: when set, several sources are picked in one pass and handed over together. */
    onChooseMany: ((List<CollectionSource>) -> Unit)? = null) {
    val multiple = onChooseMany != null
    var picked by remember { mutableStateOf<List<CollectionSource>>(emptyList()) }
    fun same(a: CollectionSource, b: CollectionSource) = SourcePicks.same(a, b)
    fun take(source: CollectionSource) {
        if (!multiple) { onChoose(source); return }
        picked = SourcePicks.toggle(picked, source)
    }
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
        val c = catalog!!; take(CollectionDrafts.catalog(c, values["genre"], values - "genre")); catalog = null
    } else MediaDialog(if (multiple) "Choose sources" else "Choose a source", close, resizeForKeyboard = true, wide = true, scrollBody = false) {
        // #468: kinds as cards, choices as cards, and the editors' footer.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Five across, sharing the width.
            SourceKinds.all.forEach { k ->
                ChoiceCard(k.name, k.about, mode == k.name, Modifier.weight(1f).testTag("source_kind_${k.name}"), width = 400.dp, icon = k.icon) { mode = k.name; query = ""; error = null }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberMenuScrollState()).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val cards = Modifier
            when (mode) {
                "Catalogs" -> {
                    val matches = vm.collectionCatalogs.filter { (it.label + it.addon.name).contains(query, true) }
                    EditorHeading("Catalogs", stringResource(R.string.tx_catalogs, matches.size) + if (matches.size > 60) " · the first 60; search to narrow them." else "")
                    TvTextField(query, { query = it }, Modifier.width(560.dp), placeholder = "Find catalogs")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        matches.take(60).forEach { c ->
                            val plain = CollectionDrafts.catalog(c)
                            val on = multiple && picked.any { same(it, plain) }
                            ChoiceCard(c.label, c.addon.name + if (c.extras.isNotEmpty()) " · filters" else "", on, cards, width = 260.dp, icon = Icons.Outlined.Widgets) {
                                // A catalog with options is set up first; choosing it again takes it out.
                                if (c.extras.isEmpty() || on) take(plain) else catalog = c
                            }
                        }
                    }
                }
                "Your lists" -> {
                    // #461: Ember's own lists: they fill from your watching and your trackers.
                    EditorHeading("Your lists", "Your Watchlist, what you're watching and your smart rows. They stay up to date as you watch.")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        app.ember.tv.data.LibraryLists.all.forEach { entry ->
                            val source = app.ember.tv.data.LibraryLists.source(entry)
                            ChoiceCard(entry.label, null, multiple && picked.any { same(it, source) }, Modifier.testTag("library_${entry.id}"), width = 230.dp,
                                icon = Icons.Outlined.Bookmarks) { take(source) }
                        }
                    }
                }
                "Saved searches" -> {
                    val saved = vm.collectionSearches
                    EditorHeading("Saved searches", if (saved.isEmpty()) stringResource(R.string.tx_save_a_search_in_discover_first) else "Searches you saved in Discover.")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        saved.forEach { search ->
                            val source = CollectionDrafts.discover(search.name, search.query, vm.profiles.profileId)
                            ChoiceCard(search.name, null, multiple && picked.any { same(it, source) }, cards, width = 230.dp, icon = Icons.Outlined.Tune) { take(source) }
                        }
                    }
                }
                else -> {
                    val tmdb = mode == "TMDB"
                    EditorHeading(if (tmdb) "From TMDB" else "A Trakt list", if (tmdb) "A collection, list, company, network or person on TMDB." else "Paste a Trakt list's number or link.")
                    if (tmdb) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SourceKinds.tmdb.forEach { (value, label, icon) ->
                            ChoiceCard(label, null, kind == value, cards, width = 170.dp, icon = icon) { kind = value; if (value == "NETWORK") media = "TV" else if (value == "COLLECTION") media = "MOVIE" }
                        }
                    }
                    TvTextField(name, { name = it.take(100) }, Modifier.width(560.dp), placeholder = "Source name")
                    if (tmdb && kind in setOf("COMPANY", "PERSON", "DIRECTOR")) {
                        TvTextField(query, { query = it }, Modifier.width(560.dp), placeholder = "Search by name")
                        if (found.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            found.forEach { ref -> ChoiceCard(ref.name, null, id == ref.id.toString(), cards, width = 220.dp) { id = ref.id.toString(); if (name.isBlank()) name = ref.name } }
                        }
                    }
                    TvTextField(id, { id = it }, Modifier.width(560.dp), placeholder = if (tmdb) "TMDB ID or link" else "Trakt list number or link")
                    if (!tmdb || kind !in setOf("COLLECTION", "NETWORK")) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(Triple("MOVIE", "Movies", Icons.Outlined.Movie), Triple("TV", "Shows", Icons.Outlined.Tv)).forEach { (key, label, icon) ->
                            ChoiceCard(label, null, media == key, cards, width = 170.dp, icon = icon) { media = key }
                        }
                    }
                }
            }
        }
        val manual = mode == "TMDB" || mode == "Trakt"
        EditorFooter("Cancel", close, error ?: if (multiple) SourcePicks.count(picked.size) else null, warn = error != null && !error!!.startsWith("Added")) {
            if (manual) PrimaryAction(if (multiple) "Add this source" else "Use source", name.isNotBlank() && id.isNotBlank()) {
                val number = CollectionDrafts.numericId(id)
                error = when { name.isBlank() -> "Enter a source name."; number == null -> "Enter a positive numeric ID or a link containing it."; mode == "TMDB" && number > Int.MAX_VALUE -> "That TMDB ID is too large."; else -> null }
                if (error == null) {
                    take(if (mode == "TMDB") CollectionSource(provider = "tmdb", title = name.trim(), tmdbSourceType = kind, tmdbId = number!!.toInt(), mediaType = media)
                        else CollectionSource(provider = "trakt", title = name.trim(), traktListId = number, mediaType = media))
                    if (multiple) { id = ""; name = ""; error = "Added. Add another, or choose " + SourcePicks.addLabel(picked.size) + "." }
                }
            }
            if (multiple && picked.isNotEmpty()) {
                Chip("Clear") { picked = emptyList() }
                PrimaryAction(SourcePicks.addLabel(picked.size), modifier = Modifier.testTag("sources_add")) { onChooseMany!!(picked) }
            }
        }
    }
}

/** #468: the kinds of source, as cards. */
internal object SourceKinds {
    data class Kind(val name: String, val about: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val all = listOf(
        Kind("Catalogs", "From your addons", Icons.Outlined.Widgets),
        Kind("Your lists", "Watchlist and smart rows", Icons.Outlined.Bookmarks),
        Kind("Saved searches", "From Discover", Icons.Outlined.Tune),
        Kind("TMDB", "Collections, people…", Icons.Outlined.Movie),
        Kind("Trakt", "A list's link", Icons.Outlined.PlaylistPlay),
    )
    val tmdb = listOf(
        Triple("COLLECTION", "Collection", Icons.Outlined.VideoLibrary), Triple("LIST", "List", Icons.Outlined.FormatListBulleted),
        Triple("COMPANY", "Company", Icons.Outlined.Business), Triple("NETWORK", "Network", Icons.Outlined.Tv),
        Triple("PERSON", "Person", Icons.Outlined.Person), Triple("DIRECTOR", "Director", Icons.Outlined.Videocam),
    )
}
