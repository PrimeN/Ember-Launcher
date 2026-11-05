package app.ember.tv

import androidx.lifecycle.viewModelScope
import app.ember.tv.mediaserver.ServerAccount
import app.ember.tv.mediaserver.ServerUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun HomeViewModel.switchServerUser(account: ServerAccount?, user: ServerUser, secret: String, result: (Boolean) -> Unit) {
    if (!settingsAllowed) { result(false); return }
    val owner = profiles.profileId
    val rootToken = servers.accounts.plexToken
    serverUserJob?.cancel()
    serverMessage = "Switching server user…"
    serverUserJob = viewModelScope.launch {
        try {
            if (account == null) {
                val session = servers.plex.authenticateHomeUser(user, secret)
                ensureActive()
                if (profiles.profileId != owner || servers.accounts.plexToken != rootToken) { result(false); return@launch }
                servers.plex.selectHomeUser(session)
                plexUserName = session.name
            } else {
                val replacement = servers.jellyfin.login(account.kind, account.url, user.name, secret).copy(key = account.key)
                ensureActive()
                if (profiles.profileId != owner || servers.accounts.servers().none { it == account }) { result(false); return@launch }
                servers.accounts.saveServers(servers.accounts.servers().map { if (it.key == account.key) replacement else it })
                serverAccounts = servers.accounts.servers()
            }
            initialSyncJob?.cancel()
            serverRefreshJob?.cancel()
            serverRefreshRevision++
            serverRows = emptyList()
            serversLoadedAt = 0L
            servers.invalidateAccounts()
            invalidateServerLibraries()
            closeDetails()
            serverMessage = "Now using ${user.name}."
            result(true)
            syncServers(initial = true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (profiles.profileId == owner) {
                serverMessage = "Could not switch user. Check the PIN/password and server connection. Your previous user is unchanged."
                result(false)
            }
        }
    }
}
