package app.ember.tv.data

/** Settings > Look > Appearance > Labels under tiles (#43). */
object TileLabels {
    const val APPS = "apps"
    const val MEDIA = "media"
    const val COLLECTIONS = "collections"
    val kinds = setOf(APPS, MEDIA, COLLECTIONS)
    val choices = listOf(APPS to "Apps", MEDIA to "Titles", COLLECTIONS to "Collections")
    fun shown(off: Set<String>, kind: String) = kind !in off
    fun toggle(off: Set<String>, kind: String) = if (kind in off) off - kind else (off + kind).filter { it in kinds }.toSet()
}
