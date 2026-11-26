package app.ember.tv.spark

import app.ember.tv.data.Meta
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** What Spark keeps about a title you watched: looked up once on TMDB, then kept on the TV. */
@Serializable
data class SparkFacts(
    val key: String,
    val name: String,
    val genres: List<Int> = emptyList(),
    val year: Int? = null,
    val language: String? = null,
    val fetchedAt: Long = 0,
)

/** One title from your history and how much it says about your taste (negative: stopped early). */
data class SparkSeed(val key: String, val name: String, val weight: Double, val at: Long)

/** A title Spark could suggest, with the titles that led to it. */
data class SparkCandidate(
    val meta: Meta,
    val key: String,
    val genres: Set<Int>,
    val language: String?,
    val year: Int?,
    val rating: Double,
    val votes: Int,
    val from: List<String> = emptyList(),
    val fromWeight: Double = 0.0,
    /** #454: found as a film under 100 minutes. */
    val short: Boolean = false,
)

/** Shares of your watching (0..1) by genre, original language and decade. */
data class SparkTaste(val genres: Map<Int, Double>, val languages: Map<String, Double>, val decades: Map<Int, Double>) {
    val isEmpty get() = genres.isEmpty()
    fun topGenres(n: Int, min: Double = 0.0) = genres.entries.filter { it.value >= min }.sortedByDescending { it.value }.take(n).map { it.key }
    /** Your most-watched language other than English, if it is a real part of your watching. */
    val otherLanguage: String? get() = languages.entries.filter { it.key != "en" && it.value >= 0.15 }.maxByOrNull { it.value }?.key
    val topDecade: Int? get() = decades.maxByOrNull { it.value }?.key
}

@Serializable
data class SparkRow(val id: String, val title: String, val subtitle: String? = null, val items: List<Meta>)

/** #452: what you told Spark about a title: [LOVE], [LIKE] or [NOT] for you. */
@Serializable
data class SparkVote(val vote: Int, val name: String, val movie: Boolean, val tmdb: Long? = null, val imdb: String? = null,
    val poster: String? = null, val at: Long = 0) {
    companion object { const val LOVE = 2; const val LIKE = 1; const val NOT = -1 }
}

/**
 * #454: moods for the Spark page: each keeps the titles with one of its kinds (by genre name, as
 * Spark's picks carry them) and drops those with a kind it rules out.
 */
enum class SparkMood(val label: String, val wants: Set<String>, val avoids: Set<String> = emptySet()) {
    ANY("Any mood", emptySet()),
    FEEL_GOOD("Feel-good", setOf("Comedy", "Family", "Animation", "Romance", "Music"), setOf("Horror", "War", "Crime")),
    THRILLING("Thrilling", setOf("Thriller", "Action", "Crime", "Action & adventure", "Mystery")),
    THOUGHTFUL("Thought-provoking", setOf("Drama", "Sci-fi", "History", "Documentary", "Sci-fi & fantasy", "War & politics")),
    SCARY("Scary", setOf("Horror", "Thriller", "Mystery")),
    ESCAPE("Escape", setOf("Fantasy", "Adventure", "Sci-fi", "Sci-fi & fantasy", "Animation", "Action & adventure"));

    fun matches(m: Meta): Boolean {
        if (this == ANY) return true
        val g = m.genres.orEmpty()
        return g.any { it in wants } && g.none { it in avoids }
    }
}

object SparkModel {
    /** Half of a play's say is gone after about four months. */
    private const val DECAY_DAYS = 180.0

    /**
     * How much one title says about you. A finished film counts 1, a show more with each episode
     * (but less for each one, so one long sitcom can't take over), and older plays count less.
     * Something stopped early, days ago, counts against its kind of title.
     */
    fun seedWeight(finished: Boolean, progress: Float, episodes: Int, ageDays: Double): Double {
        val base = when {
            finished -> 1.0
            progress >= 0.5f -> 0.7
            progress < 0.15f && ageDays > 3 -> -0.4
            else -> 0.4
        }
        val count = 1 + ln(episodes.coerceAtLeast(1).toDouble())
        val recency = maxOf(0.3, exp(-ageDays.coerceAtLeast(0.0) / DECAY_DAYS))
        return base * count * recency
    }

    /**
     * #436: a title from a tracker's history. Watched counts like a finished title (a show more for
     * each episode); your rating then counts most: 9–10 strongly for, 7–8 for, 5–6 a little, 1–4
     * against. A title only rated counts when the rating is clear (8+ or 4 and under).
     */
    fun importedWeight(plays: Int, rating: Int?, watched: Boolean, show: Boolean, ageDays: Double): Double {
        if (rating != null && rating <= 4) return -1.0
        val recency = maxOf(0.3, exp(-ageDays.coerceAtLeast(0.0) / DECAY_DAYS))
        val base = if (watched) (1 + ln(plays.coerceAtLeast(1).toDouble()) * (if (show) 1.0 else 0.3)) * recency else 0.0
        return when {
            rating == null -> base
            rating >= 9 -> maxOf(base, recency) * 2.5
            rating >= 7 -> maxOf(base, recency) * 1.5
            !watched -> 0.0
            else -> base * 0.6
        }.let { if (!watched && rating != null && rating < 8) 0.0 else it }
    }

    /** #452: Love it counts like a favourite you rated 9–10, Like it like a finished title, Not for me against its kind. */
    fun voteWeight(vote: Int): Double = when {
        vote >= SparkVote.LOVE -> 4.0
        vote == SparkVote.LIKE -> 2.0
        vote < 0 -> -2.5
        else -> 0.0
    }

    /**
     * #454: two people's tastes for couch mode: a kind counts by how much you both like it (the
     * geometric mean), so it leans to what you share rather than to whoever watches more.
     */
    fun blend(a: Map<Int, Double>, b: Map<Int, Double>): Map<Int, Double> {
        val both = a.keys.intersect(b.keys).associateWith { sqrt((a[it] ?: 0.0) * (b[it] ?: 0.0)) }.filterValues { it > 0 }
        val total = both.values.sum()
        return if (total <= 0) emptyMap() else both.mapValues { it.value / total }
    }

    /** #453: the two pairs of your favourite kinds that make a Mix: 1st with 2nd, 1st with 3rd. */
    fun mixes(taste: SparkTaste): List<Pair<Int, Int>> {
        val top = taste.topGenres(3, min = 0.1)
        return listOfNotNull(top.getOrNull(1)?.let { top[0] to it }, top.getOrNull(2)?.let { top[0] to it })
    }

    fun taste(seeds: List<SparkSeed>, facts: Map<String, SparkFacts>): SparkTaste {
        val genres = HashMap<Int, Double>(); val languages = HashMap<String, Double>(); val decades = HashMap<Int, Double>()
        for (s in seeds) {
            val f = facts[s.key] ?: continue
            if (f.genres.isNotEmpty()) f.genres.forEach { g -> genres[g] = (genres[g] ?: 0.0) + s.weight / f.genres.size }
            f.language?.let { languages[it] = (languages[it] ?: 0.0) + s.weight }
            f.year?.let { y -> (y / 10 * 10).let { d -> decades[d] = (decades[d] ?: 0.0) + s.weight } }
        }
        fun shares(m: Map<*, Double>): Double = m.values.filter { it > 0 }.sum()
        fun <K> norm(m: Map<K, Double>): Map<K, Double> { val t = shares(m); return if (t <= 0) emptyMap() else m.filterValues { it > 0 }.mapValues { it.value / t } }
        return SparkTaste(norm(genres), norm(languages), norm(decades))
    }

    fun score(c: SparkCandidate, taste: SparkTaste): Double {
        val genre = if (c.genres.isEmpty()) 0.0 else c.genres.sumOf { taste.genres[it] ?: 0.0 } / sqrt(c.genres.size.toDouble())
        val language = c.language?.let { taste.languages[it] } ?: 0.0
        val decade = c.year?.let { taste.decades[it / 10 * 10] } ?: 0.0
        val led = minOf(1.0, c.fromWeight / 2)
        val trust = minOf(1.0, ln(c.votes + 1.0) / ln(5000.0))
        val quality = ((c.rating - 6.0) / 4.0).coerceIn(-0.5, 1.0) * trust
        return 3 * genre + 1.5 * language + 0.5 * decade + 2 * led + quality
    }

    fun reason(c: SparkCandidate, taste: SparkTaste): String {
        val why = when {
            c.from.isNotEmpty() -> "Because you watched ${c.from.first()}"
            c.language != null && c.language != "en" && c.language == taste.otherLanguage -> "You watch ${SparkNames.language(c.language)} titles"
            else -> c.genres.maxByOrNull { taste.genres[it] ?: 0.0 }?.takeIf { (taste.genres[it] ?: 0.0) > 0 }?.let { "You like ${SparkNames.genre(it).lowercase()}" }
        }
        val stars = if (c.votes >= 20 && c.rating > 0) "★ %.1f".format(java.util.Locale.US, c.rating) else null
        return listOfNotNull(why, stars).joinToString(" · ").ifBlank { "Picked for you" }
    }

    /**
     * Named rows: your top picks, a row for each of your biggest tastes, "Because you watched" for
     * your latest titles, hidden gems and your other language. A title shows in two rows at most.
     */
    fun rows(candidates: List<SparkCandidate>, taste: SparkTaste, recentSeeds: List<SparkSeed>, excluded: Set<String>): List<SparkRow> {
        val pool = candidates.filter { it.key !in excluded }
            .groupBy { it.key }.map { (_, same) -> same.reduce { a, b -> a.copy(from = (a.from + b.from).distinct(), fromWeight = a.fromWeight + b.fromWeight, short = a.short || b.short) } }
            .map { it to score(it, taste) }.sortedByDescending { it.second }
        if (pool.isEmpty()) return emptyList()
        val uses = HashMap<String, Int>()
        fun take(list: List<Pair<SparkCandidate, Double>>, n: Int = 20, min: Int = 6): List<Meta>? {
            val picked = list.filter { (uses[it.first.key] ?: 0) < 2 }.take(n)
            if (picked.size < min) return null
            picked.forEach { uses[it.first.key] = (uses[it.first.key] ?: 0) + 1 }
            return picked.map { (c, _) -> c.meta.copy(badge = reason(c, taste), genres = c.genres.map(SparkNames::genre).distinct()) }
        }
        // Filled in this order (so the narrow rows get their titles first), shown in [show] order.
        val rows = ArrayList<Pair<Int, SparkRow>>()
        take(pool, min = 1)?.let { rows += 0 to SparkRow("top", "Top picks for you", "From what you've been watching", it) }
        recentSeeds.filter { it.weight > 0 }.take(2).forEachIndexed { i, s ->
            take(pool.filter { s.name in it.first.from }.sortedByDescending { it.first.fromWeight + it.second / 10 })
                ?.let { rows += (20 + i) to SparkRow("because:${s.key}", "Because you watched ${s.name}", null, it) }
        }
        taste.otherLanguage?.let { lang ->
            take(pool.filter { it.first.language == lang })?.let { rows += 40 to SparkRow("lang:$lang", "More in ${SparkNames.language(lang)}", null, it) }
        }
        // #453: Mixes, two of your favourite kinds in one title.
        mixes(taste).forEachIndexed { i, (a, b) ->
            take(pool.filter { a in it.first.genres && b in it.first.genres })?.let { rows += (15 + i) to SparkRow("mix:$a-$b", SparkNames.mix(a, b), "A Spark mix", it) }
        }
        // #454: Quick watch, films under 100 minutes (found with that limit, so their length is known).
        take(pool.filter { it.first.short }, min = 4)?.let { rows += 35 to SparkRow("quick", "Quick watch", "Films under 100 minutes", it.map { m -> m.copy(runtime = m.runtime ?: "Under 100 min") }) }
        taste.topGenres(3, min = 0.12).forEachIndexed { i, g ->
            take(pool.filter { g in it.first.genres })?.let { rows += (10 + i) to SparkRow("genre:$g", SparkNames.row(g), null, it) }
        }
        take(pool.filter { it.first.votes in 100..2500 && it.first.rating >= 7.2 })?.let { rows += 30 to SparkRow("gems", "Hidden gems for you", "Well loved, less known", it) }

        return rows.sortedBy { it.first }.map { it.second }
    }

    /** "Sci-fi · Thrillers · 2010s · Korean": what Spark has learned, for the top of its page. */
    fun summary(taste: SparkTaste): String? {
        if (taste.isEmpty) return null
        return (taste.topGenres(3, min = 0.08).map { SparkNames.genre(it) } +
            listOfNotNull(taste.topDecade?.let { "${it}s" }, taste.otherLanguage?.let { SparkNames.language(it) })).joinToString(" · ")
    }
}

/** TMDB genre names (films and shows) and Spark's names for their rows. */
object SparkNames {
    private val genres = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary",
        18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music",
        9648 to "Mystery", 10749 to "Romance", 878 to "Sci-fi", 10770 to "TV movies", 53 to "Thriller", 10752 to "War",
        37 to "Western", 10759 to "Action & adventure", 10762 to "Kids", 10763 to "News", 10764 to "Reality",
        10765 to "Sci-fi & fantasy", 10766 to "Soaps", 10767 to "Talk", 10768 to "War & politics",
    )
    private val rows = mapOf(
        28 to "Big action", 12 to "Adventures to get lost in", 16 to "Animated favourites", 35 to "Comedies to unwind with",
        80 to "Crime and capers", 99 to "Real stories", 18 to "Gripping dramas", 10751 to "Family time", 14 to "Fantasy worlds",
        36 to "History comes alive", 27 to "Scary nights in", 10402 to "Music and musicians", 9648 to "Mysteries to solve",
        10749 to "Love stories", 878 to "Mind-bending sci-fi", 53 to "Edge-of-your-seat thrillers", 10752 to "On the front line",
        37 to "Out west", 10759 to "Action-packed series", 10762 to "For the kids", 10764 to "Reality you can't stop watching",
        10765 to "Sci-fi and fantasy series", 10768 to "Power and politics",
    )
    fun genre(id: Int) = genres[id] ?: "Other"

    /** #437: Trakt's genre names as TMDB genre ids (shows use TMDB's combined TV genres). */
    fun traktGenres(names: List<String>, tv: Boolean): List<Int> = names.mapNotNull { n ->
        when (n.lowercase()) {
            "action" -> if (tv) 10759 else 28
            "adventure" -> if (tv) 10759 else 12
            "animation", "anime" -> 16
            "comedy" -> 35
            "crime" -> 80
            "documentary", "biography" -> 99
            "drama" -> 18
            "family" -> 10751
            "children" -> if (tv) 10762 else 10751
            "fantasy" -> if (tv) 10765 else 14
            "science-fiction" -> if (tv) 10765 else 878
            "superhero" -> if (tv) 10759 else 28
            "history" -> 36
            "horror" -> 27
            "music", "musical" -> 10402
            "mystery", "suspense" -> 9648
            "romance" -> 10749
            "thriller" -> if (tv) 9648 else 53
            "war" -> if (tv) 10768 else 10752
            "western" -> 37
            "reality", "game-show" -> 10764
            "soap" -> 10766
            "talk-show" -> 10767
            "news" -> 10763
            else -> null
        }
    }.distinct()
    fun row(id: Int) = rows[id] ?: "More ${genre(id).lowercase()}"

    private val mixNames = mapOf(
        setOf(878, 53) to "Sci-fi thrillers", setOf(35, 10749) to "Romcoms", setOf(27, 35) to "Horror comedies",
        setOf(80, 18) to "Crime dramas", setOf(28, 35) to "Action comedies", setOf(14, 12) to "Fantasy adventures",
        setOf(878, 28) to "Sci-fi action", setOf(9648, 53) to "Mystery thrillers", setOf(18, 10749) to "Romantic dramas",
        setOf(16, 35) to "Animated comedies", setOf(16, 10751) to "Animated family films", setOf(36, 18) to "Historical dramas",
        setOf(10752, 18) to "War dramas", setOf(27, 53) to "Horror thrillers", setOf(80, 53) to "Crime thrillers",
        setOf(878, 18) to "Sci-fi dramas", setOf(35, 18) to "Comedy dramas", setOf(80, 35) to "Crime comedies",
        setOf(12, 28) to "Action adventures", setOf(878, 12) to "Space and sci-fi adventures",
    )
    /** #453: a Mix's name: "Sci-fi thrillers", or "Drama meets mystery" when there's no common name. */
    fun mix(a: Int, b: Int): String = mixNames[setOf(a, b)] ?: "${genre(a)} meets ${genre(b).lowercase()}"
    fun language(code: String): String = java.util.Locale(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code }
}
