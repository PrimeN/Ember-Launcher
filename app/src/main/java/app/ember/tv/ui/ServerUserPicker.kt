package app.ember.tv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ember.tv.HomeViewModel
import app.ember.tv.switchServerUser
import app.ember.tv.mediaserver.ServerAccount
import app.ember.tv.mediaserver.ServerUser
import kotlinx.coroutines.CancellationException

@Composable
internal fun ServerUserPicker(vm: HomeViewModel, account: ServerAccount?, close: () -> Unit) {
    var users by remember(account?.key) { mutableStateOf<List<ServerUser>>(emptyList()) }
    var selected by remember(account?.key) { mutableStateOf<ServerUser?>(null) }
    var name by remember(account?.key) { mutableStateOf(account?.userName.orEmpty()) }
    var secret by remember(account?.key) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { vm.serverUserJob?.cancel() } }
    LaunchedEffect(account) {
        try {
            users = if (account == null) vm.servers.plex.homeUsers() else vm.servers.jellyfin.users(account)
            val id = account?.userId ?: vm.servers.accounts.plexUserId
            selected = users.firstOrNull { it.id == id }
            selected?.let { name = it.name }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { note = if (account == null) "Plex Home users could not be loaded. Check the connection and try again." else "The server did not expose a user list. You can enter a username below." }
        finally { loading = false }
    }
    MediaDialog(if (account == null) "Plex Home user" else "User on ${account.name}", { if (!busy) close() }) {
        if (loading) Note("Loading users…")
        users.forEach { user ->
            Chip(user.name + if (user.protected) " · PIN/password required" else "", selected = selected?.id == user.id, maxLines = 1) {
                if (!busy) { selected = user; name = user.name; secret = "" }
            }
        }
        if (account != null) TextInput(name, { if (!busy) { name = it; selected = null } }, "Username (including hidden users)")
        if (account != null || selected?.protected == true) TextInput(secret, { if (!busy) secret = it },
            if (account == null) "Plex Home PIN" else "Password", password = true)
        note?.let { Note(it) }
        vm.serverMessage?.let { Note(it) }
        if (!busy && !loading && (account != null && name.isNotBlank() || account == null && selected != null)) {
            Chip("Use ${selected?.name ?: name}") {
                val user = selected ?: ServerUser("", name.trim())
                busy = true
                vm.switchServerUser(account, user, secret) { ok ->
                    secret = ""; busy = false
                    if (ok) close()
                }
                secret = ""
            }
        }
        if (busy) Note("Signing in…")
        Spacer(Modifier.height(2.dp))
    }
}
