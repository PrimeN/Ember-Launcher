package app.ember.tv.data

import java.util.concurrent.Executor

/** One queued drain keeps only the newest immutable snapshot; executor barriers still flush it. */
internal class LatestWriteQueue<T : Any>(private val executor: Executor, private val write: (T) -> Unit,
    private val failure: (Exception) -> Unit = {}) {
    private var pending: T? = null
    private var scheduled = false
    @Synchronized fun submit(value: T) {
        pending = value
        if (scheduled) return
        scheduled = true
        try { executor.execute(::drain) }
        catch (e: Exception) { scheduled = false; throw e }
    }
    private fun drain() {
        while (true) {
            val value = synchronized(this) {
                val next = pending
                pending = null
                if (next == null) { scheduled = false; return }
                next
            }
            try { write(value) } catch (e: Exception) { runCatching { failure(e) } }
        }
    }
}
