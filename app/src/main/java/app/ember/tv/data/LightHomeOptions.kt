package app.ember.tv.data

import kotlinx.serialization.Serializable

/** Light-only choices; the interface ID and older wallpaper values remain compatible. */
@Serializable
data class LightHomeOptions(
    val watchNext: Boolean = true,
    val recommendations: Boolean = true,
    val idleSeconds: Int = 30,
    val motion: Boolean = true,
    /**
     * A Home catalog whose titles lead the carousel after Continue watching (#61); null mixes
     * them automatically, as before. Separate from Modern's hero banner choice.
     */
    val heroCatalog: String? = null,
) {
    fun normalized() = copy(idleSeconds = idleSeconds.takeIf { it in setOf(0, 15, 30, 60, 120) } ?: 30)
}

internal object LightLayout {
    const val COLUMNS = 6
    val backgrounds = listOf("art" to "Title art", "aurora" to "Aurora", "ocean" to "Ocean", "ember" to "Ember glow", "black" to "Black")
    data class Grid(val outer: Float, val inset: Float, val gap: Float, val width: Float) {
        val height get() = width * 3f / 5f
        val radius get() = width * .071f
    }
    private data class Spacing(val outside: Double, val inside: Double, val gutter: Double)
    private val spacing = Spacing(24.0, 21.0, 25.0)

    /** The spacing is visual design data; solve equal tile tracks within the available width. */
    fun grid(window: Float): Grid {
        val available = window.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 960.0
        val shrink = minOf(available / 960.0, 1.0)
        val edge = (spacing.outside + spacing.inside) * shrink
        val gutter = spacing.gutter * shrink
        val pitch = (available - 2 * edge + gutter) / COLUMNS
        return Grid((spacing.outside * shrink).toFloat(), (spacing.inside * shrink).toFloat(), gutter.toFloat(),
            (pitch - gutter).coerceAtLeast(java.lang.Float.MIN_NORMAL.toDouble()).toFloat())
    }
    fun idleAllowed(seconds: Int, resumed: Boolean, overlay: Boolean, count: Int) = seconds > 0 && resumed && !overlay && count > 0
}
