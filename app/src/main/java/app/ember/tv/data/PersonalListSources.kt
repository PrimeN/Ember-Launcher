package app.ember.tv.data

import app.ember.tv.mediaserver.ServerRef

/** Filter origins before deduplication so an enabled copy can replace an excluded one. */
object PersonalListSources {
    val lists = listOf("watchlist" to "Watchlist", "continue" to "Continue watching", "recent" to "Recently watched")
    val sources = listOf("ember" to "Ember / local", "trakt" to "Trakt", "simkl" to "Simkl",
        "wetrakr" to "WeTrakr", "anilist" to "AniList", "mal" to "MyAnimeList",
        "plex" to "Plex", "jellyfin" to "Jellyfin", "emby" to "Emby", "other" to "Other trackers")
    fun key(profile: String, list: String, source: String) = "${profile.length}:$profile:$list:$source"
    fun enabled(off: Set<String>, profile: String, list: String, source: String) = key(profile, list, source) !in off
    fun source(meta: Meta, fallback: String): String {
        ServerRef.of(meta)?.let { return it.provider }
        if (meta.id.startsWith("plexwatchlist:")) return "plex"
        if (fallback == "ember") return "ember"
        val label = meta.progressSource.orEmpty().lowercase().filter(Char::isLetter)
        return when {
            label == "myanimelist" -> "mal"
            sources.any { it.first == label } -> label
            else -> fallback
        }
    }
}
