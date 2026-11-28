package app.ember.tv.data

/**
 * #432: small corner tags Ember draws on posters from what it already knows about a title.
 * Nothing is fetched and no key is needed, so they work offline and with any poster.
 */
object PosterRibbons {
    enum class Kind(val id: String, val label: String, val example: String) {
        NEW("new", "New", "Released this year"),
        AGE("age", "Age rating", "PG-13, TV-MA"),
        KIND("kind", "Movie or series", "Series"),
        GENRE("genre", "Genre", "Comedy"),
        YEAR("year", "Year", "2019"),
        RUNTIME("runtime", "Running time", "2h 16m"),
    }

    val DEFAULT = setOf(Kind.NEW, Kind.AGE, Kind.KIND)
    const val MAX_SHOWN = 2

    fun parse(csv: String?): Set<Kind> =
        csv?.split(',')?.mapNotNull { id -> Kind.entries.firstOrNull { it.id == id.trim() } }?.toSet() ?: DEFAULT

    fun write(kinds: Set<Kind>): String = Kind.entries.filter { it in kinds }.joinToString(",") { it.id }

    private val YEAR = Regex("""(19|20)\d{2}""")

    /**
     * #476: the one ribbon a poster's corner shows: the first kind, in [Kind] order, that this
     * title has. A sash across a corner has room for one.
     */
    fun top(meta: Meta, kinds: Set<Kind>, thisYear: Int): Pair<Kind, String>? =
        Kind.entries.filter { it in kinds }.firstNotNullOfOrNull { k -> of(meta, setOf(k), thisYear).firstOrNull()?.let { k to it } }

    /** The tags for [meta], in the order of [Kind], at most [MAX_SHOWN]. */
    fun of(meta: Meta, kinds: Set<Kind>, thisYear: Int): List<String> {
        val year = meta.releaseInfo?.let { YEAR.find(it)?.value?.toIntOrNull() }
        return Kind.entries.filter { it in kinds }.mapNotNull { k ->
            when (k) {
                Kind.NEW -> if (year != null && year >= thisYear) "New" else null
                Kind.AGE -> meta.ageRating?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8 }
                Kind.KIND -> when (meta.type) { "movie" -> "Movie"; "series", "tv" -> "Series"; "anime" -> "Anime"; else -> null }
                Kind.GENRE -> meta.genres?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 14 }
                Kind.YEAR -> year?.toString()
                Kind.RUNTIME -> if (meta.type == "movie") meta.runtimeMinutes?.takeIf { it > 0 }?.let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" } else null
            }
        }.take(MAX_SHOWN)
    }
}
