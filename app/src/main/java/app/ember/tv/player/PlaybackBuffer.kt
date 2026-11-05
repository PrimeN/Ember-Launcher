package app.ember.tv.player

/** Leave heap headroom for decoded frames, artwork, subtitles and the launcher itself. */
object PlaybackBuffer {
    fun bytes(preset: String, heapBytes: Long, lowMemory: Boolean): Int {
        val mib = 1024L * 1024
        val requested = when(preset) { "normal" -> 64; "huge" -> 400; else -> 200 } * mib
        val headroom = (heapBytes / 4).coerceIn(16 * mib, 192 * mib)
        return minOf(requested, headroom, if(lowMemory) 24 * mib else Long.MAX_VALUE).toInt()
    }
}
