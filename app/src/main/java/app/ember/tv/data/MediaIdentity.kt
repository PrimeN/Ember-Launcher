package app.ember.tv.data

import java.util.Locale

/** Provider IDs are only unique within a media type; episodes also need their position. */
fun Meta.mediaKey(): String {
    val kind = type.lowercase(Locale.ROOT).let { if (it == "anime") "series" else it }
    return listOf(kind, id, season?.toString().orEmpty(), episode?.toString().orEmpty())
        .joinToString("") { "${it.length}:$it" }
}
