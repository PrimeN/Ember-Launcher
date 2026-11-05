package app.ember.tv.data

/** Unsupported or retired interface values safely use the default, including restored backups. */
object InterfaceStyles {
    const val MODERN = "modern"
    const val LIGHT = "light"
    const val EMBER_X = "ember_x"
    val options = listOf(MODERN to "Modern", LIGHT to "Light", EMBER_X to "Ember X")
    fun normalize(value: String?): String = value?.takeIf { it == LIGHT || it == EMBER_X } ?: MODERN
}
