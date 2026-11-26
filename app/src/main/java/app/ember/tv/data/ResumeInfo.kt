package app.ember.tv.data

/** Only show remaining time when duration and watched fraction are actually known. */
fun Meta.resumeLabel(): String? {
    val p = progress?.takeIf { it.isFinite() && it in 0f..1f } ?: return null
    val episodeLabel = if (season != null && episode != null) "S$season E$episode" else null
    val remaining = runtimeMinutes?.let { kotlin.math.ceil(it * (1.0 - p)).toInt().coerceAtLeast(0) }
    val source = serverName?.takeIf { it.isNotBlank() } ?: progressSource?.substringBefore(" + ")?.takeIf { it.isNotBlank() } ?: when {
        id.startsWith("trakt:") -> "Trakt"
        id.startsWith("simkl:") -> "Simkl"
        id.startsWith("anilist:") -> "AniList"
        id.startsWith("mal:") -> "MyAnimeList"
        id.startsWith("wetrakr:") -> "WeTrakr"
        else -> null
    }
    // #332: "Resume · 81 min left", or "Resume at 40%" when the length is unknown, as on the Play button.
    val resume = episodeLabel?.let { "Resume $it" } ?: "Resume"
    return listOfNotNull(remaining?.let { "$resume · $it min left" } ?: "$resume at ${(p * 100).toInt()}%", EpisodesLeft.label(this), source).joinToString(" · ")
}

fun Meta.spokenLabel(saved: Boolean): String = listOfNotNull(
    name, type.takeIf { it.isNotBlank() }, releaseInfo, resumeLabel() ?: badge,
    "On your watchlist".takeIf { saved },
).joinToString(", ")
