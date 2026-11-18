package app.ember.tv.ui

import app.ember.tv.data.DisplayDates

/**
 * #332: the line under a title, in one order on Home and every title page: what it is, when it came
 * out, its genres, how long it runs, then any extra facts. A full release date replaces the bare year.
 */
internal object TitleInfoLine {
    const val SEPARATOR = " · "

    fun type(type: String?): String? = when (type?.lowercase()) {
        null, "" -> null
        "movie" -> "Movie"
        "series", "show" -> "Series"
        "anime" -> "Anime"
        "tv", "channel" -> "Live"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    fun parts(type: String?, release: String?, genres: List<String>?, runtime: String?, facts: List<String> = emptyList(), maxGenres: Int = 2): List<String> {
        val year = DisplayDates.releaseYear(release)
        return (listOfNotNull(type(type), DisplayDates.release(release), genres?.filter(String::isNotBlank)?.take(maxGenres)?.takeIf { it.isNotEmpty() }?.joinToString(", "), runtime) + facts)
            .map(String::trim).filter(String::isNotBlank).filterNot { year != null && it == year }.distinct()
    }

    fun text(type: String?, release: String?, genres: List<String>?, runtime: String?, facts: List<String> = emptyList(), maxGenres: Int = 2): String =
        parts(type, release, genres, runtime, facts, maxGenres).joinToString(SEPARATOR)
}
