package app.ember.tv.tracker

enum class Kind { MOVIE, SHOW, ANIME }

enum class ScrobbleState(val path: String) { START("start"), PAUSE("pause"), STOP("stop") }

enum class LinkMode { DEVICE_CODE, USERNAME }

/** One title coming from a tracker: something in progress or on a watchlist. */
data class TrackedItem(
    val key: String,
    val title: String,
    val year: Int? = null,
    val kind: Kind = Kind.SHOW,
    val poster: String? = null,
    /** 0..1, or null when the tracker does not say. */
    val progress: Float? = null,
    val detail: String? = null,
    val tmdbId: Long? = null,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val source: String = "",
    /** Trakt's own id, needed for "related" lookups. */
    val traktId: Long? = null,
    /** An id addons understand when there is no IMDb or TMDB id, e.g. kitsu:123 for anime (#81). */
    val addonId: String? = null,
) {
    /** Used to merge the same title coming from several trackers. */
    val dedupeKey: String
        get() = tmdbId?.let { "tmdb:${kind == Kind.MOVIE}:$it" }
            ?: "t:${kind == Kind.MOVIE}:${title.lowercase()}:${year ?: 0}"
}

/** A title to add to or remove from a tracker's watchlist. Needs at least one id. */
data class TitleRef(
    val kind: Kind,
    val tmdbId: Long?,
    val imdbId: String?,
    val title: String,
    val year: Int?,
) {
    val hasIds: Boolean get() = tmdbId != null || !imdbId.isNullOrBlank()
}

/** What the TV shows while the user signs in on their phone. */
data class LinkSession(
    val userCode: String,
    val url: String,
    val deviceCode: String,
    val intervalSec: Int,
    val expiresInSec: Int,
)
