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
    /** #447: the tracker sources the Trackers page's Sources choice covers; Ember's own progress and media servers stay as they are. */
    val trackerSources = listOf("trakt", "simkl", "wetrakr", "anilist", "mal", "other")
    const val ALL = "all"
    const val MIXED = "mixed"

    /** Which trackers feed [list]: [ALL], one tracker's id, or [MIXED] for any other set chosen in the widget editor. */
    fun trackerChoice(off: Set<String>, profile: String, list: String): String {
        val on = trackerSources.filter { enabled(off, profile, list, it) }
        return when {
            on.size == trackerSources.size -> ALL
            on.size == 1 && on[0] != "other" -> on[0]
            else -> MIXED
        }
    }

    /** [off] with [list] fed by every tracker ([ALL]) or by [choice] alone; other lists, profiles and sources keep their setting. */
    fun withTrackerChoice(off: Set<String>, profile: String, list: String, choice: String): Set<String> =
        trackerSources.fold(off) { acc, source ->
            val key = key(profile, list, source)
            if (choice == ALL || source == choice) acc - key else acc + key
        }

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
    /** #451: every source a merged title came from ("Trakt + Simkl"); one is enough for it to show. */
    fun sources(meta: Meta, fallback: String): List<String> {
        val parts = app.ember.tv.tracker.TrackedSources.split(meta.progressSource)
        if (parts.size < 2) return listOf(source(meta, fallback))
        return parts.map { source(meta.copy(progressSource = it), fallback) }.distinct()
    }

}
