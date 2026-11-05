package app.ember.tv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.mediaserver.*
import kotlinx.coroutines.*

@Composable
internal fun ServerActionsDialog(ref: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val servers = remember { MediaServers.forPlayback(context) }
    val scope = rememberCoroutineScope()
    var state by remember(ref) { mutableStateOf<ServerActionState?>(null) }
    var busy by remember(ref) { mutableStateOf(true) }
    var note by remember(ref) { mutableStateOf<String?>(null) }
    suspend fun reload() { state = servers.actions(ref) }
    LaunchedEffect(ref) {
        try { reload() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { note = "Server unavailable. Check the connection and reopen this menu." }
        finally { busy = false }
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true; note = null
        scope.launch {
            try { action(); reload(); note = "Updated on your server." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { note = "The server did not confirm this change. Retry after checking its connection and permissions." }
            finally { busy = false }
        }
    }
    val labels=if(busy)emptyList() else state?.let {current->buildList {
        add(if(current.watched) "Mark unwatched on server" else "Mark watched on server")
        current.favorite?.let {add(if(it)"Remove server favorite" else "Favorite on server")}
        if(current.inResume)add("Hide from server Continue watching")
    }}.orEmpty()
    CompactActionMenu("Server actions",labels,onDismiss) {first->
        Text("Updates your selected server user.", color = Mute, fontSize = 13.sp)
        if (busy) Text("Contacting server…", fontSize = 14.sp)
        else state?.let { current ->
            MenuActionRow(if(current.watched) "Mark unwatched on server" else "Mark watched on server",
                Modifier.focusRequester(first),Icons.Outlined.Visibility,selected=current.watched) { perform { check(servers.markWatched(ref,!current.watched)) } }
            current.favorite?.let { favorite -> MenuActionRow(if(favorite) "Remove server favorite" else "Favorite on server",
                icon=Icons.Outlined.Star,selected=favorite) { perform { servers.favorite(ref,!favorite) } } }
            if(current.inResume) MenuActionRow("Hide from server Continue watching",icon=Icons.Outlined.RemoveCircleOutline,
                destructive=true,divider=true) { perform { servers.hideResume(ref) } }
        }
        note?.let { Text(it, color = Mute, fontSize = 13.sp) }
    }
}
