package app.ember.tv.player

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Auxiliary metadata belongs to one playback visit, even when an old request ignores cancellation. */
internal class PlaybackMetadataLoads(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var generation = 0L

    fun reset() {
        generation++
        val pending = jobs.toList()
        jobs.clear()
        pending.forEach { it.cancel() }
    }

    fun <T> load(fetch: suspend () -> T, publish: (T) -> Unit) {
        val visit = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try { fetch() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { return@launch }
            ensureActive()
            if (visit == generation) publish(result)
        }
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        job.start()
    }
}
