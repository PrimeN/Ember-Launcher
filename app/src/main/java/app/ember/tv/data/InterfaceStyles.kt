package app.ember.tv.data

/** Unsupported or retired interface values safely use the default, including restored backups. */
object InterfaceStyles {
    const val MODERN = "modern"
    const val LIGHT = "light"
    const val EMBER_X = "ember_x"
    val options = listOf(MODERN to "Ember", LIGHT to "Ember Light")
    /** Home choices; the cinema value remains supported by independent media/player scopes. */
    fun home(value: String?): String = if(value==LIGHT)LIGHT else MODERN
    fun normalize(value: String?): String = value?.takeIf { it == LIGHT || it == EMBER_X } ?: MODERN
}
