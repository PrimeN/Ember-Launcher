package app.ember.tv.data

/** Media pages are a saved choice, independent of Home and playback controls. */
object MediaPageLayouts {
    const val MODERN = "modern"
    const val EMBER_X = "ember_x"
    val options = listOf(MODERN to "Modern", EMBER_X to "Ember X")
    fun normalize(value: String?): String = value?.takeIf { it == EMBER_X } ?: MODERN
    fun initial(launcher: String): String = if (InterfaceStyles.normalize(launcher) == InterfaceStyles.EMBER_X) EMBER_X else MODERN
    fun interfaceStyle(layout: String): String = if (normalize(layout) == EMBER_X) InterfaceStyles.EMBER_X else InterfaceStyles.MODERN
    fun split(layout: String, width: Float, fontScale: Float, height: Float = 540f): Boolean = normalize(layout) == EMBER_X &&
        width.isFinite() && fontScale.isFinite() && height.isFinite() && width >= 740f && height >= 360f && fontScale <= 1.3f
}
