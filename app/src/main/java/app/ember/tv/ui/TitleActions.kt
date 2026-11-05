package app.ember.tv.ui

import app.ember.tv.isContinueItem
import app.ember.tv.isSaved
import app.ember.tv.markTitleWatched
import app.ember.tv.removeFromContinue
import app.ember.tv.toggleWatchlist

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.graphics.vector.ImageVector
import app.ember.tv.HomeViewModel
import app.ember.tv.data.Meta

val LocalTitleActions = compositionLocalOf<((Meta) -> Unit)?> { null }

private data class TitleMenuAction(val label:String,val icon:ImageVector,val destructive:Boolean=false,
    val selected:Boolean=false,val divider:Boolean=false,val perform:()->Unit)

@Composable
fun TitleActions(meta: Meta, vm: HomeViewModel, close: () -> Unit) {
    val actions = buildList<TitleMenuAction> {
        add(TitleMenuAction("Open details",Icons.AutoMirrored.Outlined.OpenInNew) { close(); vm.openDetails(meta) })
        if (vm.profiles.personalOn) {
            val saved=vm.isSaved(meta)
            add(TitleMenuAction(if(saved)"Remove from watchlist" else "Add to watchlist",Icons.Outlined.BookmarkBorder,selected=saved) { vm.toggleWatchlist(meta); close() })
            if (meta.type in setOf("movie", "series", "show", "tv", "anime")) {
                val label = when {
                    vm.isWatched(meta) -> "Mark as unwatched"
                    meta.type == "movie" -> "Mark as watched"
                    meta.season != null && meta.episode != null -> "Mark this episode watched"
                    else -> "Mark show as watched"
                }
                add(TitleMenuAction(label,Icons.Outlined.Visibility,selected=vm.isWatched(meta)) { vm.markTitleWatched(meta); close() })
            }
            if (vm.isContinueItem(meta)) add(TitleMenuAction("Remove from Continue Watching",Icons.Outlined.RemoveCircleOutline,destructive=true,divider=true) { vm.removeFromContinue(meta); close() })
        }
        add(TitleMenuAction("Hide from Home",Icons.Outlined.VisibilityOff,destructive=true,
            divider=none {it.destructive}) { vm.hideHomeTitle(meta); close() })
    }
    CompactActionMenu(meta.name, actions.map { it.label }, close) { first ->
        actions.forEachIndexed { index, action ->
            MenuActionRow(action.label, if (index == 0) Modifier.focusRequester(first) else Modifier, action.icon,
                destructive=action.destructive,selected=action.selected,divider=action.divider,onClick=action.perform)
        }
    }
}
