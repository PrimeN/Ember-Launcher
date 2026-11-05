package app.ember.tv.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ember.tv.data.Prefs
import app.ember.tv.iptv.IptvController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Adding IPTV sources and the Live TV view (Settings > Live TV). Split out of HomeViewModel. */
class IptvSetupState(
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val iptv: () -> IptvController,
) {
    var iptvAddMessage by mutableStateOf<String?>(null); private set
    var iptvView by mutableStateOf(prefs.iptvView); private set

    fun updateIptvView(v: String) {
        iptvView = v
        prefs.iptvView = v
    }
    var iptvAdding by mutableStateOf(false); private set

    fun addIptvSource(kind: String, name: String, url: String, username: String, password: String, epg: String, userAgent: String, format: String, done: () -> Unit) {
        if (iptvAdding) return
        iptvAdding = true
        iptvAddMessage = if (kind == "xtream") "Signing in and loading channels..." else "Loading the playlist..."
        scope.launch {
            val err = iptv().add(kind, name, url, username, password, epg, userAgent, format)
            iptvAdding = false
            iptvAddMessage = err ?: "Added. Channels are on the Live TV page; the guide loads in the background."
            if (err == null) done()
        }
    }
}
