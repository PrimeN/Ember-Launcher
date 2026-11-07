package app.ember.tv.ui

import app.ember.tv.isSaved

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta

@Composable
internal fun ServerLibraryScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit, onBack: () -> Unit) {
    val b = vm.serverBrowser
    val lib = b.library ?: return
    var search by remember(lib.ref) { mutableStateOf(b.filter.query) }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    fun exit() { if (!b.back()) onBack() }
    BackHandler(onBack = ::exit)
    val fingerprint = b.filter.toString()
    var previous by rememberSaveable(lib.ref) { mutableStateOf(fingerprint) }
    LaunchedEffect(lib.ref, fingerprint) {
        val restore = b.restorePosition
        if (restore != null) { grid.scrollToItem(restore.first, restore.second); b.restored() }
        else if (previous != fingerprint) grid.scrollToItem(0)
        previous = fingerprint; search = b.filter.query
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(b.folderName ?: lib.title, fontSize = 26.sp)
        ButtonRow(spacing=10.dp) {
            ToolbarIcon("Back",androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack,onClick=::exit)
            TvTextField(search, { search = it }, modifier = Modifier.width(280.dp), placeholder = "Search this library", fontSize = 15.sp,
                imeAction = androidx.compose.ui.text.input.ImeAction.Search, onSubmit = { b.applyFilter(b.filter.copy(query = search.trim())) })
            ToolbarIcon("Search",androidx.compose.material.icons.Icons.Outlined.Search) { b.applyFilter(b.filter.copy(query = search.trim())) }
            ToolbarIcon("Refresh",androidx.compose.material.icons.Icons.Outlined.Refresh) { b.reload() }
            if(!vm.profiles.profile.kids) {
                val shortcut=app.ember.tv.data.HomeShortcut("server",b.folderName ?: lib.title,lib.ref,b.filter.parent)
                Chip(if(vm.homeShortcuts.contains(shortcut))"Unpin from Home" else "Pin to Home") {vm.homeShortcuts.toggle(shortcut)}
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
            items(listOf("added" to "Newest", "title" to "Title", "year" to "Year", "rating" to "Rating")) { (id,label) -> Chip(label, selected = b.filter.sort == id) { b.applyFilter(b.filter.copy(sort = id)) } }
            if (b.filter.parent == null) item { Chip("Collections", selected = b.filter.collections) { b.applyFilter(b.filter.copy(collections = !b.filter.collections)) } }
            item { Chip("Unwatched", selected = b.filter.unwatched) { b.applyFilter(b.filter.copy(unwatched = !b.filter.unwatched)) } }
            items(listOf("comfortable", "default", "dense")) { density -> Chip(density.replaceFirstChar { it.uppercase() }, selected = b.density == density) { b.updateDensity(density) } }
        }
        if (b.genres.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(4.dp)) {
            item { Chip("All genres", selected = b.filter.genre == null) { b.applyFilter(b.filter.copy(genre = null)) } }
            items(b.genres, key = { it.first }) { (id,label) -> Chip(label, selected = b.filter.genre == id) { b.applyFilter(b.filter.copy(genre = id)) } }
        }
        b.error?.let { RecoveryPanel("Library unavailable", it, "Retry page") { b.retry() } }
        if (b.loading && b.items.isEmpty()) LoadingCards()
        if (!b.loading && b.items.isEmpty() && b.error == null) Text("No titles match these filters.", color = Mute, fontSize = 15.sp)
        if (b.items.isNotEmpty()) TitleGrid(b.items, vm::isSaved, { meta -> if (meta.type == "collection") b.enterCollection(meta, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) else onOpen(meta) }, Modifier.fillMaxWidth().weight(1f), state = grid,
            densityDelta = when(b.density) { "comfortable" -> -2; "dense" -> 2; else -> 0 },
            // The footer stays present during paging, preserving D-pad focus.
            footer = if(b.end) null else { { PageFooter(vm.collections.autoPage, b.loading, b.error != null) { if (b.error != null) b.retry() else b.more() } } })
        AutoLoadMore(grid, b.items.size, vm.collections.autoPage && !b.loading && !b.end && b.error == null) { b.more() }
    }
}
