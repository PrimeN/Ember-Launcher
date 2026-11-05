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
    /** The tile image for [positionMs] and the column and row of its thumbnail in that tile. */
    fun at(positionMs: Long): Triple<String, Int, Int> {
        val n = (positionMs / intervalMs).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
        val perTile = cols * rows
        val inTile = n % perTile
        return Triple(tileUrl(n / perTile), inTile % cols, inTile / cols)
    }
}
