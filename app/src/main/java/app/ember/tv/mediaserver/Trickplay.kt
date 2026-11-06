package app.ember.tv.mediaserver

/**
 * Preview pictures for seeking (the advanced player's progress bar), from your media server.
 * Pictures come as tiles of [cols] × [rows] thumbnails, each [width] × [height] pixels, one
 * every [intervalMs]; Plex serves one picture per request ([cols] = [rows] = 1).
 */
class Trickplay(
    val intervalMs: Long,
    val count: Int,
    val cols: Int,
    val rows: Int,
    val width: Int,
    val height: Int,
    private val tileUrl: (Int) -> String,
) {
    val valid: Boolean get() = intervalMs > 0 && count > 0 && cols > 0 && rows > 0 &&
        width > 0 && height > 0 && cols.toLong() * rows <= Int.MAX_VALUE &&
        width.toLong() * cols <= 16_384 && height.toLong() * rows <= 16_384

    /** The tile image for [positionMs] and the column and row of its thumbnail in that tile. */
    fun at(positionMs: Long): Triple<String, Int, Int> {
        // Invalid previews are omitted by callers; keep this calculation safe defensively too.
        if (!valid) return Triple("", 0, 0)
        val n = (positionMs.coerceAtLeast(0) / intervalMs).coerceAtMost(count.toLong() - 1).toInt()
        val perTile = cols * rows
        val inTile = n % perTile
        return Triple(tileUrl(n / perTile), inTile % cols, inTile / cols)
    }
}
