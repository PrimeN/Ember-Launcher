package app.ember.tv.data

import java.util.Locale

/** Provider IDs are only unique within a media type; episodes also need their position. */
fun Meta.mediaKey(): String {
    val kind = type.lowercase(Locale.ROOT).let { if (it == "anime") "series" else it }
    return listOf(kind, id, season?.toString().orEmpty(), episode?.toString().orEmpty())
        .joinToString("") { "${it.length}:$it" }
}

/** A movie and a series may share an ID; all episodes of one show share its title key. */
fun Meta.titleKey(): String = copy(season = null, episode = null).mediaKey()

/** Server episode rows and the episode picker must address the same saved position. */
fun Meta.playbackKey(episode: Episode? = null): String {
    if (episode != null) return episode.serverRef ?: episode.id
    if (type != "movie" && season != null && this.episode != null) {
        app.ember.tv.mediaserver.ServerRef.of(this)?.let { return it.value }
    }
    return imdb ?: id
}
