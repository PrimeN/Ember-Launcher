package app.ember.tv.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * #420: seek thumbnails for streamed files (debrid, addon links), where no server made any.
 * One background reader opens the file once and takes a picture at a keyframe near each
 * requested time, smallest first; pictures snap to a 10-second grid and the most recent 60 are
 * kept. Only the newest request is worked on, so holding Left or Right never queues a backlog.
 * Android's frame reader prefers software decoders, so the player's own decoder is left alone.
 */
internal class StreamSeekThumbs(private val url: String, private val headers: Map<String, String>) {
    /** Pictures by grid time (ms); Compose reads this map. */
    val frames = mutableStateMapOf<Long, Bitmap>()
    @Volatile var failed = false; private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ember-seek-thumbs").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val wanted = AtomicReference<List<Long>>(emptyList())
    private val order = ArrayDeque<Long>()
    private var retriever: MediaMetadataRetriever? = null
    private var misses = 0
    @Volatile private var closed = false
    @Volatile private var busy = false

    /** Asks for pictures at these times (already on the grid), the first most urgently. */
    fun request(times: List<Long>) {
        if (closed || failed) return
        wanted.set(times.filter { it !in frames })
        if (busy) return
        busy = true
        worker.execute { drain() }
    }

    fun close() {
        closed = true
        worker.execute {
            runCatching { retriever?.release() }
            retriever = null
        }
        worker.shutdown()
    }

    private fun drain() {
        try {
            while (!closed) {
                val next = wanted.get().firstOrNull { it !in frames } ?: break
                wanted.set(wanted.get().filter { it != next })
                val frame = grab(next)
                if (closed) break
                if (frame == null) {
                    // A file the reader can't open or decode at all: stop trying for this stream.
                    if (++misses >= 3 && frames.isEmpty()) { failed = true; break }
                    continue
                }
                frames[next] = frame
                order.addLast(next)
                // Old pictures are dropped, not recycled: one may still be on screen.
                while (order.size > MAX_FRAMES) order.removeFirstOrNull()?.let { old -> frames.remove(old) }
            }
        } finally {
            busy = false
            if (!closed && !failed && wanted.get().any { it !in frames }) request(wanted.get())
        }
    }

    private fun grab(atMs: Long): Bitmap? = runCatching {
        val r = retriever ?: MediaMetadataRetriever().also { it.setDataSource(url, headers); retriever = it }
        r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
    }.getOrElse {
        runCatching { retriever?.release() }
        retriever = null
        null
    }

    companion object {
        const val GRID_MS = 10_000L
        private const val MAX_FRAMES = 60
        private const val WIDTH = 320
        private const val HEIGHT = 180

        /** The grid time for a position. */
        fun snap(positionMs: Long): Long = (positionMs.coerceAtLeast(0) + GRID_MS / 2) / GRID_MS * GRID_MS

        /** Streams the reader can open: plain files over HTTP(S), not playlists or live streams. */
        fun supported(url: String?, live: Boolean): Boolean {
            if (url == null || live) return false
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            val path = lower.substringBefore('?')
            return listOf(".m3u8", ".mpd", ".ism").none { path.endsWith(it) } && "/manifest" !in path
        }

        /** The three grid times around a preview position: before, at, after. */
        fun strip(positionMs: Long, durationMs: Long): List<Long> {
            val at = snap(positionMs).coerceAtMost(durationMs.coerceAtLeast(0))
            return listOf(at - GRID_MS, at, at + GRID_MS).map { it.coerceIn(0, durationMs.coerceAtLeast(0)) }.distinct()
        }
    }
}
