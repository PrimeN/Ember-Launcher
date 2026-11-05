package app.ember.tv

import app.ember.tv.data.AddonAccess
import android.app.Application
import androidx.lifecycle.viewModelScope
import app.ember.tv.data.Features
import app.ember.tv.data.CatalogRow
import app.ember.tv.data.Posters
import app.ember.tv.data.ApiKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

// HomeViewModel: instant Home snapshot, saved and restored across launches, and the private set.
/**
 * Opens the built-in private set with [password] and applies it: API keys, addons (new ones only)
 * IPTV sources (new links only, checked one at a time) and Collection links (same ids replaced). [done] gets a short summary or an error.
 */
internal fun HomeViewModel.applyPrivate(password: String, done: (String) -> Unit) {
    if (privateBusy) return
    val now = System.currentTimeMillis()
    if (now < privateLockedUntil) {
        done("Too many tries. Wait a minute.")
        return
    }
    privateBusy = true
    viewModelScope.launch {
        try {
            val got = withContext(Dispatchers.Default) { app.ember.tv.data.PrivateBundle.open(password) }.getOrElse {
                privateFails++
                if (privateFails >= 5) {
                    privateFails = 0
                    privateLockedUntil = System.currentTimeMillis() + 60_000
                }
                done("That password does not open it.")
                return@launch
            }
            privateFails = 0
            var keys = 0
            withContext(Dispatchers.IO) {
                got.keys.forEach { (id, v) ->
                    if (id in ApiKeys.ALL_IDS && v.isNotBlank()) {
                        ApiKeys.set(id, v.trim())
                        keys++
                    }
                }
            }
            if (keys > 0) {
                keysRev++
                trackerRev++
                Posters.keyRev++
                ApiKeys.ALL_IDS.forEach { watch.keyChanged(it) }
                smartRowsLoadedAt = 0L
                refreshTrackers()
            }
            var addonsAdded = 0
            got.addons.forEach { if (addAddon(it) == null) addonsAdded++ }
            var iptvAdded = 0
            val problems = ArrayList<String>()
            val have = iptv.sources.map { it.url.trim().trimEnd('/') }.toMutableSet()
            for (s in got.iptv) {
                val u = s.url.trim()
                if (u.isBlank() || u.trimEnd('/') in have) continue
                val err = iptv.add(s.kind, s.name, u, s.username, s.password, s.epg, s.userAgent, s.format.ifBlank { "ts" })
                if (err == null) {
                    iptvAdded++
                    have += u.trimEnd('/')
                } else {
                    problems += "${s.name.ifBlank { "IPTV" }}: $err"
                }
            }
            var collectionsAdded = 0
            for (link in got.collections.map { it.trim() }.filter { it.startsWith("http") }.distinct()) {
                val text = runCatching { app.ember.tv.data.Collections.download(link) }.getOrElse {
                    ensureActive() // Cancelled: stop here rather than reporting a network error.
                    problems += "Collections ($link): ${it.message ?: "network error"}"
                    continue
                }
                val err = collections.importText(text)
                if (err == null) collectionsAdded++ else problems += "Collections ($link): $err"
            }
            done(buildString {
                append("Loaded $keys keys, $addonsAdded new addons, $iptvAdded new IPTV sources and $collectionsAdded collection links.")
                if (problems.isNotEmpty()) append(" Not added: " + problems.joinToString("; "))
            })
        } finally {
            privateBusy = false
        }
    }
}

/**
 * Debug builds only: adds the addons listed in TEST_ADDONS (see BUILDING.md) the first time
 * each one appears. Release builds compile TEST_ADDONS as empty, so this does nothing there.
 */
internal fun HomeViewModel.seedTestAddons() {
    val test = BuildConfig.TEST_ADDONS.split(',').map { it.trim() }.filter { it.startsWith("http") }
    if (test.isEmpty()) return
    val seeded = prefs.seededTestAddons
    val fresh = test.filter { AddonAccess.key(it) !in seeded && it !in addons }
    if (fresh.isNotEmpty()) {
        if (!prefs.saveAddons(addons + fresh)) return
        addons = addons + fresh
        fresh.forEach { catalogs.addonAdded(it) }
    }
    prefs.seededTestAddons = seeded + test.map(AddonAccess::key)
}

/** Saves what Home shows, a moment after the last change, for the next start. */
internal fun HomeViewModel.saveSnapshot() {
    snapshotJob?.cancel()
    snapshotJob = viewModelScope.launch {
        kotlinx.coroutines.delay(2_000)
        val data = with(app.ember.tv.data.HomeSnapshot) {
            app.ember.tv.data.HomeSnapshot.Data(
                rows = rows.map { it.toSnap() },
                live = liveRows.map { it.toSnap() },
                smart = smartRows.map { it.toSnap() },
                continueItems = continueItems,
                savedAt = System.currentTimeMillis(),
                generation=cacheGeneration,
            )
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.ember.tv.data.HomeSnapshot.save(getApplication<Application>(), data) }
    }
}

internal suspend fun HomeViewModel.restoreServerHome() {
    if(!personalOn || !featureOn(Features.SERVERS))return
    val who=profiles.profileId
    val cached=servers.restoreHome(who)
    if(who==profiles.profileId && serverRows.isEmpty())serverRows=cached.map {CatalogRow(it.source,it.title,it.items,inProgress=it.inProgress,catalogKey="server:"+it.id)}
}

/** Shows the rows saved last time straight away; the normal refresh then replaces them. */
internal suspend fun HomeViewModel.restoreSnapshot() {
    val rowRevision = catalogs.revision
    val snap = withContext(Dispatchers.IO) { app.ember.tv.data.HomeSnapshot.load(getApplication<Application>()) } ?: return
    with(app.ember.tv.data.HomeSnapshot) {
        if (addons.isNotEmpty() && rowRevision == catalogs.revision) {
            catalogs.rows = app.ember.tv.data.visibleCatalogRows(snap.rows.map { it.toRow() }, profile, activeAddons, catalogHome, prefs.catalogHomeDefaultsOff)
            catalogs.liveRows = app.ember.tv.data.visibleCatalogRows(snap.live.map { it.toRow() }, profile, activeAddons, catalogLive, live = true)
        }
        if (personalOn && featureOn(app.ember.tv.data.Features.SMART_ROWS)) smartRows = snap.smart.map { it.toRow() }
        if (personalOn && featureOn(app.ember.tv.data.Features.TRACKERS)) continueItems = snap.continueItems
    }
}
