package app.ember.tv.data

/** Saved accent positions are append-only, including full colour palettes. */
object ColorThemes {
    const val SHADOW_EMBER = 1
    const val MODERN_EMBER = 5
    const val CINEMA_RED = 6
    const val LAST = CINEMA_RED
    /** #208: Blue (2) is no longer offered; it keeps its place so the others' saved positions hold, and reads as Ember. */
    const val RETIRED_BLUE = 2
    fun offered(index:Int):Boolean = index in 0..LAST && index != RETIRED_BLUE
    fun normalize(index:Int):Int = index.takeIf {offered(it)} ?: 0
    /** Reuse the blue Ember brand artwork; no additional launcher alias is needed. */
    fun artworkIndex(index:Int):Int = normalize(index).let {if(it==MODERN_EMBER)2 else if(it==CINEMA_RED)0 else it}
}
