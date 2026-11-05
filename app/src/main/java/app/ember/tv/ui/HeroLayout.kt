package app.ember.tv.ui

/** Home inserts a small leading spacer when the first configured block is not the hero. */
object HeroLayout {
    fun textFraction(fontScale:Float)=if(fontScale>1.25f).80f else .56f
    fun height(viewport:Float,fontScale:Float)=maxOf(viewport.coerceAtLeast(0f)*app.ember.tv.data.HeroBanner.HEIGHT_FRACTION,340f*fontScale.coerceAtLeast(1f))
    fun focusScroll(heroFocused:Boolean,normal:Float,fitsViewport:Boolean=true)=if(heroFocused && fitsViewport)0f else normal
    fun index(keys: List<String>): Int = keys.indexOf("hero").let {
        if (it < 0) -1 else it + if (keys.firstOrNull() == "hero") 0 else 1
    }
    fun visibleFraction(offset: Int, size: Int, start: Int, end: Int): Float =
        if (size <= 0) 0f else ((minOf(offset + size, end) - maxOf(offset, start)).coerceAtLeast(0).toFloat() / size)
}
