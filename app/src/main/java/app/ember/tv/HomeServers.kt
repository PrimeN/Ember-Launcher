package app.ember.tv

import android.app.Application
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.Features
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.NavTabs
import app.ember.tv.mediaserver.LanDiscovery
import app.ember.tv.mediaserver.MediaServers
import app.ember.tv.tracker.list
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

// HomeViewModel: media servers (Plex, Jellyfin, Emby), their Home rows, libraries and network discovery.
internal fun HomeViewModel.syncServers(initial:Boolean=false,only:String?=null) {
    initialSyncJob?.cancel()
    val owner=profiles.profileId
    initialSyncJob=viewModelScope.launch {
        app.ember.tv.mediaserver.initialServerSync(initial,load={
            if(profiles.profileId!=owner)return@initialServerSync emptyList()
            refreshServers(force=true,only=only)
            openMedia(force=true,only=only)
            serverRefreshJob?.join();librariesJob?.join()
            ensureActive()
            if(profiles.profileId!=owner)emptyList() else serverHealth.filter {only==null || it.id.startsWith("$only:")}
        })
    }
}

/** Reloads server rows, at most once a minute unless forced (e.g. after watching something). */
internal fun HomeViewModel.refreshServers(force: Boolean = false, only: String? = null) {
    if (!plexConnected && serverAccounts.isEmpty() || !featureOn(app.ember.tv.data.Features.SERVERS)) {
        serverRefreshJob?.cancel()
        serverRows = emptyList()
        invalidateServerLibraries()
        return
    }
    if(!force && serverRefreshJob?.isActive == true) return
    val now = System.currentTimeMillis()
    if (!force && now - serversLoadedAt < 60_000L) return
    serverRefreshJob?.cancel()
    val revision=++serverRefreshRevision
    val owner=profiles.profileId
    serverRefreshJob = viewModelScope.launch {
        val fresh = runCatching { servers.rows(only) { rows ->
            ensureActive()
            if(revision==serverRefreshRevision && profiles.profileId==owner)
                serverRows = rows.map { CatalogRow(it.source,it.title,it.items,inProgress=it.inProgress,catalogKey="server:"+it.id) }
        } }.getOrElse { ensureActive(); null }?.map { CatalogRow(it.source, it.title, it.items, inProgress = it.inProgress,catalogKey="server:"+it.id) }
        ensureActive()
        // A server that fails keeps its last rows (MediaServers caches them per server); an empty
        // success really empties them. If the whole load fails, keep what was shown.
        // Removing a server clears the rows first.
        if(revision!=serverRefreshRevision || profiles.profileId!=owner)return@launch
        if (fresh != null) serverRows = fresh
        if(serverHealth.none {it.id.endsWith(":rows") && it.failed})serversLoadedAt=System.currentTimeMillis()
        // A server that signed Ember out says so once, instead of its rows quietly going stale (#94).
        val refused = servers.signedOut()
        val newly = refused - signedOutNoticed
        if (newly.isNotEmpty()) {
            val names = newly.map { id -> if (id == "plex") "Plex" else serverAccounts.firstOrNull { it.key == id }?.name ?: "A media server" }
            notice = "${names.joinToString(" and ")} signed Ember out. Sign in again in Settings › Media servers."
        }
        signedOutNoticed = refused
        servers.saveHome(profiles.profileId)
        updateScreensaver()
    }
}

internal fun HomeViewModel.startPlexLink() {
    serverMessage = null
    runLink("plex", "Plex", { servers.plex.beginLink() }, { servers.plex.pollLink(it) }, { serverMessage = it }) {
        plexConnected = true
        plexUserName = null
        invalidateServerLibraries()
        enableTab(NavTabs.LIBRARY)
        syncServers(initial=true,only="plex")
    }
}

internal fun HomeViewModel.disconnectPlex() {
    linkTasks.cancel("plex")
    servers.plex.disconnect()
    plexConnected = false
    plexUserName = null
    serverUserJob?.cancel()
    invalidateServerLibraries()
    serverMessage = null
    serverRows = emptyList()
    refreshServers(force = true)
}

/** [kind] is "jellyfin" or "emby". */
internal fun HomeViewModel.addServer(kind: String, url: String, user: String, password: String) {
    if (url.isBlank() || user.isBlank()) {
        serverMessage = "Enter the server address and your username."
        return
    }
    serverMessage = "Signing in..."
    viewModelScope.launch {
        serverMessage = runCatching { servers.addServer(kind, url, user, password) }.fold(
            onSuccess = { a ->
                serverAccounts = servers.accounts.servers()
                invalidateServerLibraries()
                enableTab(NavTabs.LIBRARY)
                syncServers(initial=true,only=a.key)
                "${a.name} added."
            },
            onFailure = { "Could not sign in. Check the address, username and password. ${it.message.orEmpty()}" },
        )
    }
}

/**
 * Jellyfin Quick Connect (#97): the TV shows a code, you approve it in the Jellyfin app or web
 * (Profile › Quick Connect), and Ember signs in without a password.
 */
internal fun HomeViewModel.quickConnect(rawUrl: String) {
    if (rawUrl.isBlank()) { serverMessage = "Enter the server address first."; return }
    val url = servers.jellyfin.normalize(rawUrl)
    serverMessage = null
    var approved: String? = null
    runLink("quickconnect", "Jellyfin", begin = {
        check(servers.jellyfin.quickConnectEnabled(url)) { "Quick Connect is off on this server. Turn it on in the Jellyfin dashboard (General), or sign in with your password." }
        val (secret, code) = servers.jellyfin.quickConnectBegin(url)
        approved = secret
        app.ember.tv.tracker.LinkSession(code, "the Jellyfin app › Profile › Quick Connect", secret, 5, 600)
    }, poll = { session -> servers.jellyfin.quickConnectApproved(url, session.deviceCode) }, report = { serverMessage = it }) {
        // runLink already cleared the code; finish with the approved secret.
        val secret = approved ?: return@runLink
        viewModelScope.launch {
            serverMessage = runCatching { servers.addQuickConnect(url, secret) }.fold(
                onSuccess = { a ->
                    serverAccounts = servers.accounts.servers()
                    invalidateServerLibraries()
                    enableTab(NavTabs.LIBRARY)
                    syncServers(initial = true, only = a.key)
                    "${a.name} added."
                },
                onFailure = { "Quick Connect was approved but signing in failed. ${it.message.orEmpty()}" },
            )
        }
    }
}

internal fun HomeViewModel.removeServer(key: String) {
    servers.removeServer(key)
    serverAccounts = servers.accounts.servers()
    invalidateServerLibraries()
    serverMessage = null
    serverRows = emptyList()
    refreshServers(force = true)
}

internal fun HomeViewModel.retryServer(statusId: String) {
    val id = statusId.substringBeforeLast(':')
    if(id=="plex") servers.refreshConnections()
    if (statusId.endsWith(":rows")) refreshServers(force = true, only = id)
    else openMedia(force = true, only = id)
}

internal fun HomeViewModel.invalidateServerLibraries() {
    librariesRevision++
    serverBrowser.clear()
    librariesJob?.cancel()
    serverLibraries = emptyList()
    librariesLoading = false
    librariesAt = 0L
}

/** Loads the libraries when the tab opens; reloads after ten minutes or when forced. */
internal fun HomeViewModel.openMedia(force: Boolean = false, only: String? = null) {
    if (only == null) refreshServers()
    if (!servers.connected || !featureOn(app.ember.tv.data.Features.SERVERS)) return
    if ((!force && librariesLoading) || (!force && System.currentTimeMillis() - librariesAt in 0 until 10 * 60_000L && serverLibraries.isNotEmpty())) return
    librariesJob?.cancel()
    val revision=++librariesRevision
    val owner=profiles.profileId
    librariesLoading = true
    librariesJob = viewModelScope.launch {
        try {
            val fresh = runCatching { servers.libraries(only) {
                ensureActive()
                if(revision==librariesRevision && profiles.profileId==owner)serverLibraries=it
            } }.getOrElse {ensureActive();return@launch}
            ensureActive()
            if(revision==librariesRevision && profiles.profileId==owner) {
                serverLibraries=fresh
                if(serverHealth.none {it.id.endsWith(":libraries") && it.failed})librariesAt=System.currentTimeMillis()
            }
        } finally {if(revision==librariesRevision)librariesLoading=false}
    }
}

internal fun HomeViewModel.discoverServers() {
    if (discovering) return
    discovering = true
    serverMessage = "Looking for servers on your network..."
    viewModelScope.launch {
        val list = runCatching { LanDiscovery.discover(getApplication<Application>()) }.getOrDefault(emptyList())
        found = list
        discovering = false
        serverMessage = if (list.isEmpty()) "No servers answered. You can still type the address below." else null
    }
}
