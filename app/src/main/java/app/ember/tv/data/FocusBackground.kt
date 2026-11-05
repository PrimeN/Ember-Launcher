package app.ember.tv.data

/** Focused rows can use their already-loaded tile, an optional larger image, or just the theme. */
object FocusBackground {
    const val COLORS = "colors"
    const val ARTWORK = "artwork"
    const val OFF = "off"
    val choices = listOf(COLORS to "Tile colors", ARTWORK to "Artwork backdrop", OFF to "Theme only")
    fun normalize(value: String?) = value?.takeIf { it in setOf(COLORS, ARTWORK, OFF) } ?: COLORS
    /** Opening a row-based page must not fetch a large backdrop before any tile has focus. */
    fun idleArtwork(heroVisible: Boolean, mode: String) = heroVisible || mode == ARTWORK
}

/** Small RGB histogram. Prefer populated colorful bins over black borders, white logos and transparency. */
internal object TilePalette {
    fun color(pixels: IntArray): Int? {
        val bins = HashMap<Int, Int>()
        for (pixel in pixels) {
            if (pixel ushr 24 < 192) continue
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            val high = maxOf(r, g, b); val low = minOf(r, g, b)
            if (high < 24 || low > 235) continue
            val key = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
            bins[key] = (bins[key] ?: 0) + 1
        }
        val key = bins.maxByOrNull { (rgb, count) ->
            val r = rgb shr 10 and 31; val g = rgb shr 5 and 31; val b = rgb and 31
            count * (1f + 3f * (maxOf(r,g,b) - minOf(r,g,b)) / 31f)
        }?.key ?: return null
        return (0xff shl 24) or (((key shr 10 and 31) * 8 + 4) shl 16) or
            (((key shr 5 and 31) * 8 + 4) shl 8) or ((key and 31) * 8 + 4)
    }
}
