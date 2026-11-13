package app.ember.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.data.CollectionFolder
import app.ember.tv.data.MediaCollection

/** #231: collections and folders shown as pictures of themselves rather than text rows. */
internal object CollectionPreviews {
    /** Folder pictures beside a collection in the list. */
    const val STRIP = 5

    /** Folders that match the search come first, so a found folder is visible on its collection's card. */
    fun folders(c: MediaCollection, query: String, max: Int = STRIP): List<CollectionFolder> {
        val q = query.trim()
        val ordered = if (q.isEmpty()) c.folders else c.folders.sortedByDescending { it.title.contains(q, true) }
        return ordered.take(max)
    }

    fun more(c: MediaCollection, max: Int = STRIP): Int = (c.folders.size - max).coerceAtLeast(0)

    /** Width over height of a folder tile. */
    fun aspect(f: CollectionFolder): Float = when (f.shape) { "wide" -> 16f / 9f; "poster" -> 2f / 3f; else -> 1f }

    /** The collection's own backdrop, then the first folder picture. */
    fun cover(c: MediaCollection): String? = c.backdropImageUrl?.takeIf { it.isNotBlank() }
        ?: c.folders.firstNotNullOfOrNull { f -> (f.heroBackdropUrl ?: f.coverImageUrl)?.takeIf { it.isNotBlank() } }

    fun folderCover(f: CollectionFolder): String? = f.coverImageUrl?.takeIf { it.isNotBlank() } ?: f.heroBackdropUrl?.takeIf { it.isNotBlank() }

    /** What stands in for a missing picture: the emoji, else the title's first letters. */
    fun mark(title: String, emoji: String?): String = emoji?.takeIf { it.isNotBlank() }
        ?: title.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "•" }

    fun status(c: MediaCollection, onHome: Boolean, inDiscover: Boolean): String = buildList {
        add(if (c.folders.size == 1) "1 folder" else "${c.folders.size} folders")
        add(if (onHome) "On Home" else "Hidden from Home")
        if (inDiscover) add("In Discover")
    }.joinToString(" · ")

    fun sources(f: CollectionFolder): String = f.allSources.size.let { if (it == 1) "1 source" else "$it sources" }
}

/** A folder as its tile: its shape, its cover (or emoji/initials) and its title. */
@Composable
internal fun FolderPreview(f: CollectionFolder, height: Dp, modifier: Modifier = Modifier, dimmed: Boolean = false, showTitle: Boolean = true) {
    val cover = CollectionPreviews.folderCover(f)
    Box(modifier.height(height).width(height * CollectionPreviews.aspect(f)).clip(RoundedCornerShape(8.dp)).background(WashHi).alpha(if (dimmed) .45f else 1f),
        contentAlignment = Alignment.Center) {
        if (cover != null) CollectionImage(cover, f.title, Modifier.fillMaxSize())
        else Text(CollectionPreviews.mark(f.title, f.coverEmoji), color = Fg, fontSize = if (height < 70.dp) 16.sp else 26.sp, fontWeight = FontWeight.SemiBold)
        if (showTitle && !f.hideTitle && f.title.isNotBlank() && cover != null) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))).padding(horizontal = 5.dp, vertical = 3.dp)) {
                Text(f.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One collection in the list: its backdrop, name, where it shows and its first folders. OK opens it. #239: the same card as widgets. */
@Composable
internal fun CollectionPreviewCard(vm: HomeViewModel, c: MediaCollection, query: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = vm.collections
    val onHome = c.id !in cs.hidden
    ManagerCard(c.title.ifBlank { "Untitled collection" }, listOf(CollectionPreviews.status(c, onHome, c.id in cs.inDiscover)), onClick, modifier, tag = "collection_card",
        trailing = { SwitchChip("On Home", onHome) { cs.toggle(c.id) } },
        extra = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CollectionPreviews.folders(c, query).forEach { f -> FolderPreview(f, 52.dp, dimmed = !cs.folderOn(c, f), showTitle = false) }
                CollectionPreviews.more(c).takeIf { it > 0 }?.let { Text("+$it", color = LocalContentColor.current.copy(alpha = .7f), fontSize = 13.sp) }
            }
        }) {
        val cover = CollectionPreviews.cover(c)
        if (cover != null) CollectionImage(cover, c.title, Modifier.fillMaxSize())
        else Text(CollectionPreviews.mark(c.title, null), color = Fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** #231: a collection's contents in a dialog, its folders as tiles, in place of the old expanding list. */
@Composable
internal fun CollectionContentDialog(
    vm: HomeViewModel, c: MediaCollection, close: () -> Unit,
    onEdit: () -> Unit, onDuplicate: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
    onDuplicateFolder: (CollectionFolder) -> Unit, onDeleteFolder: (CollectionFolder) -> Unit,
) {
    val cs = vm.collections
    var folder by remember(c.id) { mutableStateOf<String?>(null) }
    MediaDialog(c.title.ifBlank { "Untitled collection" }, close, wide = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(192.dp).height(108.dp).clip(RoundedCornerShape(10.dp)).background(WashHi), contentAlignment = Alignment.Center) {
                val cover = CollectionPreviews.cover(c)
                if (cover != null) CollectionImage(cover, c.title, Modifier.fillMaxSize())
                else Text(CollectionPreviews.mark(c.title, null), color = Fg, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(CollectionPreviews.status(c, c.id !in cs.hidden, c.id in cs.inDiscover), color = Mute, fontSize = 13.sp)
                ButtonRow(spacing = 6.dp) {
                    Chip("Edit") { onEdit() }
                    Chip("Duplicate") { onDuplicate() }
                    Chip("Export") { onExport() }
                    SwitchChip("Home", c.id !in cs.hidden) { cs.toggle(c.id) }
                    SwitchChip("Discover", c.id in cs.inDiscover) { cs.toggleDiscover(c.id) }
                    MoveButton(c.title, cs.list.indexOfFirst { it.id == c.id }, cs.list.lastIndex) { step -> cs.move(c.id, step) }
                    Chip("Delete", modifier = Modifier.testTag("collection_delete")) { onDelete() }
                }
            }
        }
        Text("Folders", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (c.folders.isEmpty()) Text("No folders yet. Choose Edit to add one.", color = Mute, fontSize = 13.sp)
        ButtonRow(spacing = 12.dp) {
            c.folders.forEach { f ->
                val on = cs.folderOn(c, f)
                Focusable(onClick = { folder = f.id }, modifier = Modifier.width(176.dp).testTag("collection_folder"), radius = TvTokens.Shape.Card, container = PanelHi) {
                    Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.height(96.dp), contentAlignment = Alignment.Center) { FolderPreview(f, if (f.shape == "wide") 90.dp else 96.dp, dimmed = !on, showTitle = false) }
                        Text(f.title.ifBlank { "Untitled folder" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(if (on) CollectionPreviews.sources(f) else "Hidden", color = LocalContentColor.current.copy(alpha = .7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
    folder?.let { id ->
        val f = c.folders.firstOrNull { it.id == id }
        if (f == null) folder = null else FolderActionsDialog(c, f, cs.folderOn(c, f), { folder = null },
            onToggle = { cs.toggleFolder(c, f) }, onDuplicate = { folder = null; onDuplicateFolder(f) }, onDelete = { folder = null; onDeleteFolder(f) })
    }
}

@Composable
private fun FolderActionsDialog(c: MediaCollection, f: CollectionFolder, on: Boolean, close: () -> Unit,
    onToggle: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    MediaDialog(f.title.ifBlank { "Untitled folder" }, close) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderPreview(f, if (f.shape == "wide") 120.dp else 150.dp, dimmed = !on)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("In ${c.title.ifBlank { "this collection" }}", color = Mute, fontSize = 13.sp)
                Text(CollectionPreviews.sources(f), color = Mute, fontSize = 13.sp)
                f.allSources.take(4).forEach { s -> Text(s.title ?: s.catalogId ?: s.provider, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        ButtonRow(spacing = 6.dp) {
            SwitchChip("Visible", on) { onToggle() }
            Chip("Duplicate folder") { onDuplicate() }
            Chip("Delete folder") { onDelete() }
        }
    }
}
