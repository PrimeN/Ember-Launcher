package app.ember.tv.discover

import app.ember.tv.data.Meta
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable enum class ExploreKind(val label: String, val path: String) { ALL("All", ""), MOVIE("Movies", "movie"), TV("Shows", "tv") }
@Serializable enum class ExploreSort(val label: String, val api: String) { POPULAR("Popular", "popularity.desc"), RATING("Highest rated", "vote_average.desc"), NEWEST("Newest", "") }
@Serializable enum class OscarMode(val label: String) { ANY("Any awards"), WIN("Winners"), NOMINATED("Winners & nominees") }

/**
 * #252: awards Discover can filter by, each a Wikidata item whose categories (and itself) are
 * recognised; [films]/[shows] say what it is given to. [best] is the top category, where one exists.
 */
data class AwardBody(val key: String, val name: String, val short: String, val qid: String, val films: Boolean, val shows: Boolean,
    val best: Pair<String, String>? = null)

object AwardBodies {
    val all = listOf(
        AwardBody("oscars", "Academy Awards", "Oscars", "Q19020", films = true, shows = false, best = "Q102427" to "Best Picture"),
        AwardBody("globes", "Golden Globes", "Golden Globes", "Q1011547", films = true, shows = true),
        AwardBody("bafta", "BAFTA Film Awards", "BAFTA", "Q732997", films = true, shows = false),
        AwardBody("cannes", "Palme d'Or · Cannes", "Palme d'Or", "Q179808", films = true, shows = false),
        AwardBody("venice", "Golden Lion · Venice", "Golden Lion", "Q209459", films = true, shows = false),
        AwardBody("berlin", "Golden Bear · Berlin", "Golden Bear", "Q154590", films = true, shows = false),
        AwardBody("emmys", "Primetime Emmys", "Emmys", "Q1044427", films = false, shows = true),
        AwardBody("actors", "Actor Awards (SAG)", "Actor Awards", "Q268200", films = true, shows = true),
    )
    fun of(key: String): AwardBody = all.firstOrNull { it.key == key } ?: all.first()
    fun kinds(body: AwardBody): List<ExploreKind> = listOfNotNull(ExploreKind.MOVIE.takeIf { body.films }, ExploreKind.TV.takeIf { body.shows })
    /** "Golden Globes winners", for tags and summaries. */
    fun label(q: ExploreQuery): String = of(q.award).short + when (q.oscars) { OscarMode.WIN -> " winners"; OscarMode.NOMINATED -> " winners & nominees"; else -> "" } +
        (of(q.award).best?.takeIf { q.bestPicture }?.let { " · " + it.second } ?: "")
}
@Serializable data class ExploreRef(val id: Long, val name: String, val image: String? = null,
    /** #253: "Acting · Inception", shown while choosing; never saved. */
    @kotlinx.serialization.Transient val note: String? = null)
@Serializable data class ExploreQuery(
    val kind: ExploreKind = ExploreKind.ALL,
    val sort: ExploreSort = ExploreSort.POPULAR,
    val genres: Set<String> = emptySet(),
    val allGenres: Boolean = false,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val minRating: Double? = null,
    val maxRating: Double? = null,
    val providers: List<ExploreRef> = emptyList(),
    val includeRentBuy: Boolean = false,
    val actors: List<ExploreRef> = emptyList(),
    val directors: List<ExploreRef> = emptyList(),
    val companies: List<ExploreRef> = emptyList(),
    val oscars: OscarMode = OscarMode.ANY,
    val bestPicture: Boolean = false,
    /** #252: which award [oscars] filters by (the field keeps its name for saved searches). */
    val award: String = "oscars",
    val minMovieVotes: Int = 500,
    val minTvVotes: Int = 200,
) {
    fun error(today: LocalDate = LocalDate.now()): String? = when {
        listOfNotNull(fromYear, toYear).any { it !in 1870..today.year } -> "Use years from 1870 to ${today.year}."
        fromYear != null && toYear != null && fromYear > toYear -> "The starting year must be before the ending year."
        listOfNotNull(minRating, maxRating).any { !it.isFinite() || it !in 0.0..10.0 } -> "Ratings must be between 0 and 10."
        minRating != null && maxRating != null && minRating > maxRating -> "The minimum rating must be below the maximum."
        minMovieVotes !in 0..1_000_000 || minTvVotes !in 0..1_000_000 -> "Minimum votes must be between 0 and 1,000,000."
        providers.size > 12 || actors.size > 8 || directors.size > 8 || companies.size > 12 -> "Choose fewer people, services or studios."
        (providers + actors + directors + companies).any { it.id <= 0 } -> "Choose an entry from the search results."
        genres.any { key -> ExploreGenres.all.none { it.key == key } } -> "Choose a supported genre."
        genres.isNotEmpty() && kinds().isEmpty() -> "Those genres do not apply to the selected type. Change the genres or content type."
        oscars != OscarMode.ANY && AwardBodies.kinds(AwardBodies.of(award)).none { kind == ExploreKind.ALL || it == kind } ->
            AwardBodies.of(award).short + (if (AwardBodies.of(award).films) " are film awards. Choose All or Movies, or clear Awards." else " are TV awards. Choose All or Shows, or clear Awards.")
        else -> null
    }
    val hasFilters get() = copy(kind = ExploreKind.ALL, sort = ExploreSort.POPULAR) != ExploreQuery()
    fun kinds(): List<ExploreKind> = (when {
        oscars != OscarMode.ANY -> AwardBodies.kinds(AwardBodies.of(award)).filter { kind == ExploreKind.ALL || it == kind }
        kind == ExploreKind.ALL -> listOf(ExploreKind.MOVIE, ExploreKind.TV)
        else -> listOf(kind)
    }).filter { type ->
        val groups = ExploreGenres.selected(this)
        groups.isEmpty() || if (allGenres) groups.all { it.ids(type).isNotEmpty() } else groups.any { it.ids(type).isNotEmpty() }
    }
    fun movieVotes(kind: ExploreKind) = if (sort != ExploreSort.RATING) 0 else if (kind == ExploreKind.MOVIE) minMovieVotes else minTvVotes
    fun summary(): List<String> = buildList {
        ExploreGenres.all.filter { it.key in genres }.forEach { add(it.label) }
        if (genres.size > 1) add(if (allGenres) "All genres" else "Any genre")
        if (fromYear != null || toYear != null) add("${fromYear ?: "Any"}–${toYear ?: "Now"}")
        if (minRating != null || maxRating != null) add("TMDB ${minRating ?: 0.0}–${maxRating ?: 10.0}")
        addAll(providers.map { it.name }); if (providers.isNotEmpty()) add(if (includeRentBuy) "Subscription, free, rent or buy" else "Subscription or free")
        addAll(actors.map { "Actor: ${it.name}" }); addAll(directors.map { "Director: ${it.name}" })
        addAll(companies.map { it.name })
        if (oscars != OscarMode.ANY) add(AwardBodies.label(this@ExploreQuery))
    }
}

data class ExploreGenre(val key: String, val label: String, val movie: Set<Int>, val tv: Set<Int>) {
    fun ids(kind: ExploreKind) = if (kind == ExploreKind.MOVIE) movie else tv
}
object ExploreGenres {
    val all = listOf(
        ExploreGenre("action", "Action & adventure", setOf(28,12), setOf(10759)),
        ExploreGenre("animation", "Animation", setOf(16), setOf(16)),
        ExploreGenre("comedy", "Comedy", setOf(35), setOf(35)),
        ExploreGenre("crime", "Crime", setOf(80), setOf(80)),
        ExploreGenre("documentary", "Documentary", setOf(99), setOf(99)),
        ExploreGenre("drama", "Drama", setOf(18), setOf(18)),
        ExploreGenre("family", "Family", setOf(10751), setOf(10751)),
        ExploreGenre("fantasy", "Science fiction & fantasy", setOf(878,14), setOf(10765)),
        ExploreGenre("history", "History", setOf(36), emptySet()),
        ExploreGenre("horror", "Horror", setOf(27), emptySet()),
        ExploreGenre("music", "Music", setOf(10402), emptySet()),
        ExploreGenre("mystery", "Mystery", setOf(9648), setOf(9648)),
        ExploreGenre("romance", "Romance", setOf(10749), emptySet()),
        ExploreGenre("thriller", "Thriller", setOf(53), emptySet()),
        ExploreGenre("war", "War & politics", setOf(10752), setOf(10768)),
        ExploreGenre("western", "Western", setOf(37), setOf(37)),
        ExploreGenre("kids", "Kids", emptySet(), setOf(10762)),
        ExploreGenre("news", "News", emptySet(), setOf(10763)),
        ExploreGenre("reality", "Reality", emptySet(), setOf(10764)),
        ExploreGenre("soap", "Soap", emptySet(), setOf(10766)),
        ExploreGenre("talk", "Talk", emptySet(), setOf(10767)),
    )
    fun selected(query: ExploreQuery) = all.filter { it.key in query.genres }
}

@Serializable data class ExploreTitle(
    val meta: Meta,
    val genreIds: Set<Int> = emptySet(),
    val date: String? = null,
    val popularity: Double = 0.0,
    val rating: Double = 0.0,
    val votes: Int = 0,
    val adult: Boolean = false,
) {
    val tmdb: Long get() = meta.tmdbId ?: 0
    fun matches(query: ExploreQuery, kind: ExploreKind, today: LocalDate): Boolean {
        if (adult || tmdb <= 0 || date.isNullOrBlank() || date > today.toString()) return false
        val year = date.take(4).toIntOrNull() ?: return false
        if (query.fromYear != null && year < query.fromYear || query.toYear != null && year > query.toYear) return false
        if (query.minRating != null && rating < query.minRating || query.maxRating != null && rating > query.maxRating) return false
        if (votes < query.movieVotes(kind)) return false
        val groups = ExploreGenres.selected(query).map { it.ids(kind) }
        return groups.isEmpty() || if (query.allGenres) groups.all { ids -> ids.any { it in genreIds } }
            else groups.any { ids -> ids.any { it in genreIds } }
    }
}

@Serializable data class SavedExplore(val id: String, val name: String, val query: ExploreQuery, val home: Boolean = false) {
    val section get() = "explore:$id"
}

object ExploreRanking {
    fun sort(items: List<ExploreTitle>, sort: ExploreSort): List<ExploreTitle> = items.distinctBy { it.meta.id }.sortedWith(
        when (sort) {
            ExploreSort.POPULAR -> compareByDescending<ExploreTitle> { it.popularity }
            ExploreSort.RATING -> compareByDescending<ExploreTitle> { it.rating }.thenByDescending { it.votes }
            ExploreSort.NEWEST -> compareByDescending<ExploreTitle> { it.date.orEmpty() }
        }.thenBy { it.meta.id })
    fun mix(movies: List<ExploreTitle>, shows: List<ExploreTitle>, limit: Int): List<ExploreTitle> = buildList {
        var m = 0; var s = 0
        while (size < limit && (m < movies.size || s < shows.size)) {
            if (m < movies.size) add(movies[m++])
            if (size < limit && s < shows.size) add(shows[s++])
        }
    }
}
