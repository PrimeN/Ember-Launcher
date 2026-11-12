package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CollectionManager(vm: HomeViewModel) {
    var editing by remember(vm.profiles.profileId) { mutableStateOf(false) }
    var original by remember { mutableStateOf<MediaCollection?>(null) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var removingFolder by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removingAll by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val cs = vm.collections
    val focus = remember { FocusRequester() }; val createFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val matches = cs.list.filter { (it.title + it.folders.joinToString { f -> f.title }).contains(query, true) }
    LaunchedEffect(cs.list.map { it.id }, restore) {
        if (restore != null && cs.list.none { it.id == restore }) {
            restore = matches.getOrNull(listState.firstVisibleItemIndex.coerceAtMost((matches.size - 1).coerceAtLeast(0)))?.id
            delay(80); runCatching { if (restore == null) createFocus.requestFocus() else focus.requestFocus() }
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).height(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingToggle("Animated collections", "Muted video and GIF previews when focused. Off keeps static covers.", cs.heroPreviews) { cs.enableHeroPreviews(!cs.heroPreviews) }
        ButtonRow {
            Chip("Create collection", modifier = Modifier.focusRequester(createFocus)) { original = null; editing = true }
            Chip("Import pack") { importing = true }
            // #157
            if (cs.list.isNotEmpty()) {
                Chip("Export all") { exporting = EXPORT_ALL }
                Chip("Delete all", modifier = Modifier.testTag("collections_delete_all")) { removingAll = true }
            }
            Text("${cs.list.size} collections · ${cs.list.sumOf { it.folders.size }} folders", color = Mute, fontSize = 12.sp)
        }
        TvTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Find collections or folders")
        cs.message?.let { Text(it, color = Mute, fontSize = 12.sp) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(matches, key = { it.id }) { c ->
                // #231: a picture of each collection; OK opens its contents in a dialog.
                CollectionPreviewCard(vm, c, query, if (restore == c.id) Modifier.focusRequester(focus) else Modifier) { expanded = c.id }
            }
        }
    }
    expanded?.let { id ->
        val c = cs.list.firstOrNull { it.id == id }
        if (c == null) expanded = null else CollectionContentDialog(vm, c, { restore = id; expanded = null; scope.launch { delay(80); runCatching { focus.requestFocus() } } },
            onEdit = { original = c; editing = true },
            onDuplicate = { scope.launch { cs.duplicateCollection(c.id) } },
            onExport = { exporting = c.id },
            onDelete = { removing = c.id },
            onDuplicateFolder = { f -> scope.launch { cs.duplicateFolder(c.id, f.id) } },
            onDeleteFolder = { f -> removingFolder = c.id to f.id })
    }
    if (editing) CollectionEditor(vm, original) { editing = false }
    if (importing) CollectionImportDialog(vm) { importing = false }
    exporting?.let { id -> CollectionExportDialog(vm, id) { exporting = null } }
    // #157: the same prompt as other deletions: it starts on Cancel and says what is lost.
    removing?.let { id ->
        val c = cs.list.firstOrNull { it.id == id }
        ConfirmDestructive("Delete ${c?.title?.ifBlank { null } ?: "this collection"}?", CollectionDeletion.collectionMessage(c?.folders?.size ?: 0),
            { removing = null }) { restore = id; expanded = null; cs.remove(id) }
    }
    removingFolder?.let { (collectionId, folderId) ->
        val owner = cs.list.firstOrNull { it.id == collectionId }
        val f = owner?.folders?.firstOrNull { it.id == folderId }
        ConfirmDestructive("Delete ${f?.title?.ifBlank { null } ?: "this folder"}?", CollectionDeletion.folderMessage(owner?.folders?.size == 1),
            { removingFolder = null }) { restore = collectionId; cs.removeFolder(collectionId, folderId) }
    }
    if (removingAll) ConfirmDestructive("Delete all ${cs.list.size} collections?", CollectionDeletion.ALL_MESSAGE,
        { removingAll = false }, "Delete all") { expanded = null; cs.removeAll(); runCatching { createFocus.requestFocus() } }
}

@Composable
private fun CollectionImportDialog(vm: HomeViewModel, close: () -> Unit) {
    var url by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }; var revision by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    MediaDialog("Import collection pack", close, resizeForKeyboard = true) {
        TvTextField(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "Pack URL", password = true)
        TvTextField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "Passphrase (encrypted Ember packs)", password = true)
        message?.let { Text(it, color = Mute) }
        Chip(if (downloading) "Opening…" else "Preview import") { if (!downloading && url.isNotBlank()) { downloading = true; scope.launch {
            val result = runCatching { val raw = Collections.download(url); withContext(Dispatchers.Default) { CollectionTransfer.open(raw, password) } }
            val check = result.getOrNull()?.let(Collections::inspect)
            message = if (result.isFailure) "Could not open this pack. Check the URL, connection and passphrase." else check?.error
            if (message == null) { revision = vm.collections.revision; pending = result.getOrNull() }
            downloading = false
        } } }
    }
    pending?.let { text -> ImportCollectionPreview(vm, text, revision) { pending = null } }
}

@Composable
private fun CollectionExportDialog(vm: HomeViewModel, id: String, close: () -> Unit) {
    var password by remember { mutableStateOf("") }; var message by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val all = id == EXPORT_ALL
    MediaDialog(if (all) "Export all collections" else "Export collection", close, resizeForKeyboard = true) {
        Text((if (all) "Save every collection and their private source settings in one encrypted pack." else "Save this collection and its private source settings in an encrypted pack.") +
            " Keep the passphrase to import it on another TV.", color = Mute)
        TvTextField(password, { password = it }, Modifier.fillMaxWidth(), placeholder = "Passphrase (at least 8 characters)", password = true)
        message?.let { Text(it, color = Mute) }
        Chip(if (busy) "Saving…" else "Save encrypted pack") { if (!busy) { busy = true; scope.launch {
            message = runCatching { withContext(Dispatchers.IO) {
                val pack = if (all) vm.collections.exportAll(password) else vm.collections.exportPack(id, password)
                Backup.saveToDownloads(context, (if (all) "ember-collections-" else "ember-collection-") + "${System.currentTimeMillis()}.json", pack.toByteArray())
            } }.fold({ "Saved to $it" }, { "Could not export. Use a passphrase of at least 8 characters and restore any missing private source settings." })
            busy = false
        } } }
        Text("Phone setup also offers encrypted collection downloads.", color = Mute, fontSize = 12.sp)
    }
}

@Composable
internal fun ImportCollectionPreview(vm: HomeViewModel, text: String, revision: Long, close: () -> Unit) {
    var draft by remember(text) { mutableStateOf(Collections.inspect(text).collections) }
    val checked = remember(draft) { Collections.inspect(Collections.encode(draft)) }
    var mode by remember { mutableStateOf("merge") }; var message by remember { mutableStateOf(checked.error) }; var saving by remember { mutableStateOf(false) }
    var repair by remember { mutableStateOf<CollectionSource?>(null) }
    val scope = rememberCoroutineScope()
    val conflicts = draft.count { c -> vm.collections.list.any { it.id == c.id } }
    val sources = draft.flatMap { it.folders }.flatMap { it.allSources }
    val missing = sources.filter { s -> s.provider == "addon" && vm.collectionCatalogs.none { Collections.matches(it, s) } }.distinct()
    fun replace(old: CollectionSource, value: CollectionSource) { draft = draft.map { c -> c.copy(folders = c.folders.map { f -> f.copy(sources = f.allSources.map { if (it == old) value else it }, catalogSources = emptyList()) }) } }
    MediaDialog("Review collection import", close) {
        Text("${draft.size} collections · ${draft.sumOf { it.folders.size }} folders · $conflicts matching IDs", fontSize = 15.sp)
        draft.forEach { c -> Text(c.title + " · ${c.folders.size} folders", color = Mute, fontSize = 13.sp) }
        val future = draft.sumOf { it.extensionFields.size + it.folders.sumOf { f -> f.extensionFields.size + f.allSources.sumOf { s -> s.extensionFields.size } } }
        if (future > 0) Text("$future additional fields will be preserved. Their features may not be displayed in Ember.", color = Mute)
        missing.forEach { source ->
            Text("Unavailable catalog: ${source.title ?: source.catalogId.orEmpty()}", color = Mute)
            ButtonRow {
                vm.addons.firstOrNull { AddonAccess.key(it) == source.addonKey && !vm.addonAvailable(it) }?.let { url -> Chip("Resume add-on") { vm.pauseAddon(url, false) } }
                Chip("Choose replacement") { repair = source }
            }
        }
        if (missing.isNotEmpty()) Text("You can keep unavailable references and repair them later in the source editor.", color = Mute, fontSize = 12.sp)
        if (sources.any { it.secretArgsRef != null }) Text("Some sources need private settings from an Ember backup. Encrypted collection exports include those settings.", color = Mute)
        val foreign = sources.filter { it.provider == "ember" && it.ownerProfile != vm.profiles.profileId }
        if (foreign.isNotEmpty()) Chip("Assign ${foreign.size} Discover searches to this profile") { foreign.forEach { replace(it, it.copy(ownerProfile = vm.profiles.profileId)) } }
        ButtonRow { listOf("merge" to "Merge / update IDs", "copy" to "Keep both", "replace" to "Replace all").forEach { (value,label) -> Chip(label, mode == value) { mode = value } } }
        Text(when (mode) { "replace" -> "Replace every saved collection with this pack."; "copy" -> "Keep existing collections and create copies for matching IDs."; else -> "Update matching IDs and retain other saved collections." }, color = Mute, fontSize = 13.sp)
        message?.let { Text(it, color = Mute) }
        Chip(if (saving) "Saving…" else "Import collections") { if (!saving && checked.error == null) { saving = true; scope.launch {
            message = vm.collections.importText(Collections.encode(draft), mode, revision); saving = false; if (message == null) close()
        } } }
    }
    repair?.let { source -> CollectionSourcePicker(vm, { chosen -> replace(source, chosen.copy(extensionFields = source.extensionFields + chosen.extensionFields)); repair = null }, { repair = null }) }
}

@Composable
internal fun ContentOrganizer(vm: HomeViewModel, close: () -> Unit) {
    var mode by remember { mutableStateOf("Add-ons") }; var query by remember { mutableStateOf("") }; var expanded by remember { mutableStateOf<String?>(null) }
    var collect by remember { mutableStateOf<CollectionSource?>(null) }; var filters by remember { mutableStateOf<CatalogDef?>(null) }
    MediaDialog("Organize content", close, resizeForKeyboard = true, scrollBody = false, wide = true) {
        ButtonRow { listOf("Add-ons", "Catalogs", "Collections").forEach { value -> Chip(value, mode == value) { mode = value; expanded = null; query = "" } } }
        if (mode == "Collections") CollectionManager(vm) else {
            TvTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Find ${mode.lowercase()}")
            val urls = vm.addons.filter { vm.addonLabel(it).contains(query, true) }
            val catalogs = vm.allCatalogs.filter { (it.label + it.addon.name).contains(query, true) }
            Text(if (mode == "Add-ons") "${urls.size} add-ons · ${vm.activeAddons.size} enabled" else "${catalogs.size} catalogs", color = Mute, fontSize = 12.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mode == "Add-ons") items(urls, key = { AddonAccess.key(it) }) { url ->
                    Column(Modifier.fillMaxWidth().background(PanelHi, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chip(vm.addonLabel(url) + if (!vm.addonAvailable(url)) " · Paused" else "", expanded == url) { expanded = if (expanded == url) null else url }
                        vm.addonInfo[url]?.let { Text(it.provides.joinToString(" · "), color = Mute, fontSize = 12.sp) }
                        if (expanded == url) ButtonRow {
                            SwitchChip("Enabled", vm.addonAvailable(url)) { vm.pauseAddon(url, vm.addonAvailable(url)) }
                            Chip("Refresh manifest") { vm.refreshAddon(url) }
                        }
                    }
                } else items(catalogs, key = { it.key }) { c ->
                    Column(Modifier.fillMaxWidth().background(PanelHi, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Chip(c.label + " · " + c.addon.name, expanded == c.key) { expanded = if (expanded == c.key) null else c.key }
                        Text(c.type + " · " + if (c.supportsSkip) "Multiple pages" else "Single page", color = Mute, fontSize = 12.sp)
                        if (expanded == c.key) ButtonRow(spacing = 6.dp) {
                            CatalogRenameButton(vm, c.key, c.originalLabel)
                            // Locked where the catalog can't go, saying why (#102).
                            SwitchChip("Home", vm.isOnHome(c), locked = !c.homeable) { if (c.homeable) vm.toggleCatalogHome(c) else vm.showNotice(catalogLockReason(c, "Home")) }
                            SwitchChip("Discover", vm.isInDiscover(c), locked = !c.browsable) { if (c.browsable) vm.toggleCatalogDiscover(c) else vm.showNotice(catalogLockReason(c, "Discover")) }
                            SwitchChip("Live", vm.isOnLive(c), locked = !c.homeable) { if (c.homeable) vm.toggleCatalogLive(c) else vm.showNotice(catalogLockReason(c, "Live TV")) }
                            MoveButton(c.label, vm.allCatalogs.indexOfFirst { it.key == c.key }, vm.allCatalogs.lastIndex) { step -> vm.moveCatalog(c, step) }
                            Chip("Add to collection") { if (c.extras.isEmpty()) collect = CollectionDrafts.catalog(c) else filters = c }
                        }
                    }
                }
            }
        }
    }
    filters?.let { def -> CatalogOptions(def, def.requestExtras, { filters = null }) { values -> collect = CollectionDrafts.catalog(def, extras = values); filters = null } }
    collect?.let { source -> AddToCollection(vm, source) { collect = null } }
}

/** The export dialog's id for every collection at once (#158); never a real collection id. */
internal const val EXPORT_ALL = "\u0000all"

/** What each delete prompt says (#157). Nothing here can be undone, so each names the way to keep a copy. */
internal object CollectionDeletion {
    const val FOLDER_MESSAGE = "This deletes the folder and its sources from this collection. This can't be undone."
    const val LAST_FOLDER_MESSAGE = "This is the collection's only folder, so the whole collection is deleted with it. This can't be undone."
    fun folderMessage(last: Boolean) = if (last) LAST_FOLDER_MESSAGE else FOLDER_MESSAGE
    const val ALL_MESSAGE = "This deletes every collection and folder from Home and Discover. This can't be undone; export the ones you want to keep first."
    fun collectionMessage(folders: Int) =
        "This deletes the collection" + (if (folders > 0) " and its $folders folder" + (if (folders == 1) "" else "s") else "") + " from Home and Discover. This can't be undone; use Export first to keep a copy."
}
