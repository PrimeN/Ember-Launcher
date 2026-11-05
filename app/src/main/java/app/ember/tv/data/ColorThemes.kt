package app.ember.tv.data

/** Saved accent positions are append-only, including full colour palettes. */
object ColorThemes {
    const val SHADOW_EMBER = 1
    const val MODERN_EMBER = 5
    const val CINEMA_RED = 6
    const val LAST = CINEMA_RED
    fun normalize(index:Int):Int = index.takeIf {it in 0..LAST} ?: 0
    /** Reuse the blue Ember brand artwork; no additional launcher alias is needed. */
    fun artworkIndex(index:Int):Int = normalize(index).let {if(it==MODERN_EMBER)2 else if(it==CINEMA_RED)0 else it}
}
