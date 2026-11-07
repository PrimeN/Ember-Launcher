package app.ember.tv.data

import app.ember.tv.tracker.SecretCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Bounded cached responses in Ember's private cache folder; request URLs and credentials never become
 * file names. The app stores them without its own encryption (#28): Android's app sandbox and
 * storage encryption already protect the folder. [cipher] remains for callers that want it.
 */
internal class ResponseCache(
    private val root: File,
    private val cipher: SecretCipher?,
    private val duration: (CacheKind) -> Long = { CachePolicy.config.millis(it) },
    private val epoch: (CacheKind) -> String = CachePolicy::epoch,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable private data class Entry(val at: Long, val body: String)
    private data class Memory(val entry: Entry, val bytes: Int)
    private class Flight { val gate = Mutex(); var users = 0 }
    private val flights = hashMapOf<String, Flight>()
    private val diskGate = Mutex()
    private val memory = LinkedHashMap<String, Memory>(16, .75f, true)
    private var memoryBytes = 0
    private val json = Json { ignoreUnknownKeys = true }

    private fun fresh(kind: CacheKind, entry: Entry) = clock() - entry.at in 0 until duration(kind)
    suspend fun get(kind: CacheKind, identity: String, refresh: Boolean = false, cacheable: (String) -> Boolean = { true }, load: suspend () -> String): String {
        require(kind == CacheKind.METADATA || kind == CacheKind.CATALOGS)
        val stamp = epoch(kind)
        val name = CachePolicy.digest("${kind.directory}:$stamp:$identity")
        val flight = synchronized(flights) { flights.getOrPut(name) { Flight() }.also { it.users++ } }
        try {
            return flight.gate.withLock {
                currentCoroutineContext().ensureActive()
                if (!refresh) synchronized(memory) { memory[name]?.entry?.takeIf { fresh(kind, it) } }?.let { return@withLock it.body }
                val file = File(File(root, kind.directory), "$name.cache")
                if (!refresh) {
                    val saved = withContext(Dispatchers.IO) { diskGate.withLock {
                        if (stamp != epoch(kind)) null else runCatching {
                            if (!file.isFile || file.length() > ENTRY_LIMIT * 2L + 1024) return@runCatching null
                            json.decodeFromString<Entry>(String(file.readBytes().let { cipher?.decrypt(name, it) ?: it }, Charsets.UTF_8))
                                .takeIf { fresh(kind, it) }.also { if (it == null) file.delete() }
                        }.getOrNull()
                    } }
                    if (saved != null && stamp == epoch(kind)) { remember(name, saved); return@withLock saved.body }
                }
                val result = load()
                currentCoroutineContext().ensureActive()
                val entry = Entry(clock(), result)
                val bytes = result.toByteArray(Charsets.UTF_8).size
                if (bytes <= ENTRY_LIMIT && stamp == epoch(kind) && cacheable(result)) {
                    withContext(Dispatchers.IO) { diskGate.withLock {
                        if (stamp == epoch(kind)) {
                            remember(name, entry)
                            runCatching {
                                AtomicFile.write(file, json.encodeToString(Entry.serializer(), entry).toByteArray(Charsets.UTF_8).let { cipher?.encrypt(name, it) ?: it }).getOrThrow()
                                prune(file.parentFile!!)
                            }
                        }
                    } }
                }
                result
            }
        } finally { synchronized(flights) { flight.users--; if (flight.users == 0) flights.remove(name) } }
    }
    private fun remember(name: String, entry: Entry) = synchronized(memory) {
        val bytes = entry.body.toByteArray(Charsets.UTF_8).size
        memory.remove(name)?.let { memoryBytes -= it.bytes }
        memory[name] = Memory(entry, bytes); memoryBytes += bytes
        while (memoryBytes > MEMORY_LIMIT || memory.size > 128) {
            val key = memory.keys.first(); memoryBytes -= memory.remove(key)!!.bytes
        }
    }
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "cache" }?.sortedByDescending { it.lastModified() }.orEmpty()
        var used = 0L
        files.forEachIndexed { index, file ->
            used += file.length()
            if (index >= 256 || used > DISK_LIMIT) file.delete()
        }
    }
    fun trimMemory() = synchronized(memory) { memory.clear(); memoryBytes = 0 }
    suspend fun clear(kinds: Set<CacheKind>) = withContext(Dispatchers.IO) { diskGate.withLock {
        if (kinds.none { it == CacheKind.METADATA || it == CacheKind.CATALOGS }) return@withLock
        synchronized(memory) { memory.clear(); memoryBytes = 0 }
        kinds.filter { it == CacheKind.METADATA || it == CacheKind.CATALOGS }.forEach {
            val dir = File(root, it.directory)
            if (dir.exists() && !CacheFiles.delete(dir)) error("Could not clear cached responses.")
        }
    } }
    private companion object { const val ENTRY_LIMIT = 2 * 1024 * 1024; const val MEMORY_LIMIT = 4 * 1024 * 1024; const val DISK_LIMIT = 16L * 1024 * 1024 }
}
