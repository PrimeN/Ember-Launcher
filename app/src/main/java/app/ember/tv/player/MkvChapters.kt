package app.ember.tv.player

import app.ember.tv.data.Net
import app.ember.tv.mediaserver.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Chapters of a Matroska (MKV) file on the web, read with two or three small range requests
 * (Media3 does not pass them on). The file's SeekHead says where its Chapters element is; that
 * element lists each chapter's start and name. Used for addon sources (Options > Video > Chapters).
 */
object MkvChapters {
    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CHAPTERS = 0x1043A770L
    private const val EDITION = 0x45B9L
    private const val EDITION_DEFAULT = 0x45DBL
    private const val ATOM = 0xB6L
    private const val TIME_START = 0x91L
    private const val FLAG_HIDDEN = 0x98L
    private const val DISPLAY = 0x80L
    private const val STRING = 0x85L
    private const val UNKNOWN = -1L

    private val http by lazy { Net.client(10, 20) }

    /** The chapters, or an empty list (not MKV, no chapters, or the server does not allow ranges). */
    suspend fun read(url: String, userAgent: String? = null): List<Chapter> = withContext(Dispatchers.IO) {
        runCatching {
            val head = range(url, 0, 128 * 1024, userAgent) ?: return@runCatching emptyList()
            parseFile(head) { from, size -> range(url, from, size, userAgent) }
        }.getOrDefault(emptyList())
    }

    /** [fetch] gets [size] bytes at an absolute file offset (for a Chapters element further on). */
    internal fun parseFile(head: ByteArray, fetch: (Long, Int) -> ByteArray?): List<Chapter> {
        val r = Reader(head)
        if (r.id() != EBML) return emptyList()
        r.skip(r.size())
        if (r.id() != SEGMENT) return emptyList()
        r.size() // the segment's size (often unknown in streams)
        val segmentStart = r.pos.toLong()
        var chaptersAt: Long? = null
        // The segment's first elements: a SeekHead (where things are), sometimes Chapters itself.
        while (r.remaining() > 8) {
            val elementAt = r.pos
            val id = r.id()
            val size = r.size()
            val start = r.pos
            if (size == UNKNOWN || size > r.remaining()) {
                // Chapters that run past what was read: read them from where they start.
                if (id == CHAPTERS) chaptersAt = elementAt.toLong()
                break
            }
            when (id) {
                SEEK_HEAD -> seekHead(head, start, start + size.toInt())[CHAPTERS]?.let { chaptersAt = segmentStart + it }
                CHAPTERS -> return chapters(head, start, start + size.toInt())
            }
            if (id == 0x1F43B675L) break // a Cluster: the media has begun
            r.pos = start + size.toInt()
        }
        val at = chaptersAt ?: return emptyList()
        val first = fetch(at, 64 * 1024) ?: return emptyList()
        val c = Reader(first)
        if (c.id() != CHAPTERS) return emptyList()
        val size = c.size()
        if (size <= 0 || size > 4 * 1024 * 1024) return emptyList()
        val body = if (c.pos + size <= first.size) first else fetch(at, c.pos + size.toInt()) ?: return emptyList()
        return chapters(body, c.pos, c.pos + size.toInt())
    }

    private fun seekHead(b: ByteArray, from: Int, to: Int): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        val r = Reader(b, from)
        while (r.pos < to - 2) {
            val id = r.id()
            val size = r.size()
            if (size < 0) break
            val end = r.pos + size.toInt()
            if (id == SEEK) {
                var seekId: Long? = null
                var position: Long? = null
                val s = Reader(b, r.pos)
                while (s.pos < end - 1) {
                    val cid = s.id()
                    val csize = s.size().toInt()
                    when (cid) {
                        SEEK_ID -> seekId = s.uint(csize)
                        SEEK_POSITION -> position = s.uint(csize)
                        else -> s.skip(csize.toLong())
                    }
                }
                if (seekId != null && position != null) out.putIfAbsent(seekId, position)
            }
            r.pos = end
        }
        return out
    }

    /** The default edition's chapters (else the first edition's), in order, hidden ones left out. */
    private fun chapters(b: ByteArray, from: Int, to: Int): List<Chapter> {
        val editions = ArrayList<Pair<Boolean, List<Chapter>>>()
        val r = Reader(b, from)
        while (r.pos < to - 2) {
            val id = r.id()
            val size = r.size()
            if (size < 0) break
            val end = (r.pos + size.toInt()).coerceAtMost(to)
            if (id == EDITION) {
                var default = false
                val list = ArrayList<Chapter>()
                val e = Reader(b, r.pos)
                while (e.pos < end - 1) {
                    val cid = e.id()
                    val csize = e.size().toInt()
                    when (cid) {
                        EDITION_DEFAULT -> default = e.uint(csize) == 1L
                        ATOM -> {
                            atom(b, e.pos, e.pos + csize, list)
                            e.skip(csize.toLong())
                        }
                        else -> e.skip(csize.toLong())
                    }
                }
                editions += default to list
            }
            r.pos = end
        }
        val pick = editions.firstOrNull { it.first } ?: editions.firstOrNull() ?: return emptyList()
        return pick.second.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    private fun atom(b: ByteArray, from: Int, to: Int, out: MutableList<Chapter>) {
        var start: Long? = null
        var name: String? = null
        var hidden = false
        val r = Reader(b, from)
        while (r.pos < to - 1) {
            val id = r.id()
            val size = r.size().toInt()
            when (id) {
                TIME_START -> start = r.uint(size) / 1_000_000 // nanoseconds
                FLAG_HIDDEN -> hidden = r.uint(size) == 1L
                DISPLAY -> {
                    if (name == null) {
                        val d = Reader(b, r.pos)
                        val end = r.pos + size
                        while (d.pos < end - 1) {
                            val cid = d.id()
                            val csize = d.size().toInt()
                            if (cid == STRING && name == null) name = String(b, d.pos, csize, Charsets.UTF_8).trim()
                            d.skip(csize.toLong())
                        }
                    }
                    r.skip(size.toLong())
                }
                ATOM -> {
                    atom(b, r.pos, r.pos + size, out) // nested chapters
                    r.skip(size.toLong())
                }
                else -> r.skip(size.toLong())
            }
        }
        if (start != null && !hidden) out += Chapter(name?.takeIf { it.isNotBlank() } ?: "Chapter ${out.size + 1}", start)
    }

    private fun range(url: String, from: Long, size: Int, userAgent: String?): ByteArray? {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=$from-${from + size - 1}")
            .header("User-Agent", userAgent ?: "EmberTV/0.9")
            .build()
        return http.newCall(req).execute().use { r ->
            if (r.code != 206 && !(r.code == 200 && from == 0L)) return null
            // A 200 answer sends the whole file: read only what is needed.
            r.body?.byteStream()?.use { s ->
                val buf = ByteArray(size)
                var n = 0
                while (n < size) {
                    val k = s.read(buf, n, size - n)
                    if (k < 0) break
                    n += k
                }
                buf.copyOf(n)
            }
        }
    }

    /** Reads EBML ids and sizes (variable-length integers). */
    private class Reader(val b: ByteArray, var pos: Int = 0) {
        fun remaining() = b.size - pos

        fun id(): Long {
            if (pos >= b.size) return 0L
            val first = b[pos].toInt() and 0xFF
            val len = when {
                first and 0x80 != 0 -> 1
                first and 0x40 != 0 -> 2
                first and 0x20 != 0 -> 3
                first and 0x10 != 0 -> 4
                else -> return 0L.also { pos = b.size }
            }
            if (pos + len > b.size) return 0L.also { pos = b.size }
            var v = 0L
            repeat(len) { v = (v shl 8) or (b[pos + it].toLong() and 0xFF) }
            pos += len
            return v
        }

        /** The element size; [UNKNOWN] when the file leaves it open. */
        fun size(): Long {
            if (pos >= b.size) return UNKNOWN
            val first = b[pos].toInt() and 0xFF
            var len = 1
            var mask = 0x80
            while (len <= 8 && first and mask == 0) {
                len++
                mask = mask shr 1
            }
            if (len > 8 || pos + len > b.size) return UNKNOWN.also { pos = b.size }
            var v = (first and (mask - 1)).toLong()
            var allOnes = v == (mask - 1).toLong()
            for (i in 1 until len) {
                val x = b[pos + i].toLong() and 0xFF
                if (x != 0xFFL) allOnes = false
                v = (v shl 8) or x
            }
            pos += len
            return if (allOnes) UNKNOWN else v
        }

        fun uint(len: Int): Long {
            var v = 0L
            for (i in 0 until len.coerceAtMost(8)) if (pos + i < b.size) v = (v shl 8) or (b[pos + i].toLong() and 0xFF)
            pos += len
            return v
        }

        fun skip(n: Long) {
            pos = (pos + n).coerceAtMost(b.size.toLong()).toInt()
        }
    }
}
