package app.ember.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SourceFailure {
    ACCESS, OFFLINE, TIMEOUT, QUOTA, UNAVAILABLE;
    companion object {
        private fun http(code:Int)=when(code) {401,403->ACCESS;429->QUOTA;408,504->TIMEOUT;else->UNAVAILABLE}
        fun from(error: Throwable): SourceFailure = when (error) {
            is app.ember.tv.mediaserver.ServerHttpException -> http(error.status)
            is AddonHttpException -> http(error.status)
            is app.ember.tv.iptv.IptvHttpException -> http(error.status)
            is kotlinx.coroutines.TimeoutCancellationException, is java.net.SocketTimeoutException -> TIMEOUT
            is java.net.UnknownHostException, is java.net.ConnectException -> OFFLINE
            else -> UNAVAILABLE
        }
    }
}

/** User-facing source health never includes URLs, tokens or raw exception messages. */
data class SourceStatus(
    val id: String,
    val name: String,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val lastSuccess: Long? = null,
    val cached: Boolean = false,
    val failure: SourceFailure? = null,
    val empty: Boolean = false,
    val lastDurationMillis: Long? = null,
)

class SourceHealth(
    private val namespace: String? = null,
    private val readSaved: (String) -> Long? = { SourceFreshness.read(namespace, it) },
    private val saveSuccess: (String, Long) -> Unit = { id, time -> SourceFreshness.write(namespace, id, time) },
) {
    private val started=mutableMapOf<String,Long>()
    internal var elapsedMillis:()->Long={System.nanoTime()/1_000_000}
    var diagnosticGroup:String?=namespace
    var entries by mutableStateOf<Map<String, SourceStatus>>(emptyMap()); private set
    @Synchronized fun cancelLoading(ids: Set<String> = entries.keys) {
        ids.forEach {started.remove(it)}
        entries = entries.mapValues { (id, status) -> if (id in ids) status.copy(loading = false) else status }
    }
    @Synchronized fun retain(ids: Set<String>) { started.keys.retainAll(ids); entries = entries.filterKeys { it in ids } }
    @Synchronized fun saved(id: String, name: String, at: Long) {
        if(id !in entries) entries=entries+(id to SourceStatus(id,name,lastSuccess=at,cached=true))
    }
    @Synchronized fun rename(id:String,name:String) { entries[id]?.let {entries=entries+(id to it.copy(name=name))} }
    @Synchronized fun start(id: String, name: String) {
        started[id]=elapsedMillis()
        entries = entries + (id to (entries[id] ?: SourceStatus(id, name, lastSuccess = readSaved(id))).copy(name = name, loading = true, failed = false, failure = null))
    }
    @Synchronized fun finish(id: String, success: Boolean, cached: Boolean, now: Long = System.currentTimeMillis(), failure: SourceFailure? = null, empty: Boolean = false) {
        val old = entries[id] ?: return
        val duration=started.remove(id)?.let {(elapsedMillis()-it).coerceAtLeast(0)}
        SourceDiagnostics.record(diagnosticGroup,id,now,duration,success,cached,empty,failure)
        if (success) saveSuccess(id, now)
        entries = entries + (id to old.copy(loading = false, failed = !success, cached = cached, failure = if(success) null else failure, empty = empty,
            lastSuccess = if (success) now else old.lastSuccess, lastDurationMillis=duration))
    }
}
