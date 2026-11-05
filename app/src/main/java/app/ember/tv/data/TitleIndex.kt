package app.ember.tv.data

import java.util.TreeMap

/**
 * Membership with exactly Meta.sameTitle's aliases and optional-year semantics.
 * This is not a canonical-ID union: sameTitle is deliberately non-transitive when
 * a provider omits a year. Only titles actually added to this index participate.
 */
class TitleIndex(items: Iterable<Meta> = emptyList()) {
    private class Names {
        var anyYear = false
        val years = HashSet<String>()
    }
    private class Group {
        val ids = HashSet<String>()
        val imdb = HashSet<String>()
        val tmdb = HashSet<Long>()
        val serverRefs = HashSet<String>()
        val names = TreeMap<String, Names>(String.CASE_INSENSITIVE_ORDER)
    }
    private val movies = Group()
    private val shows = Group()

    init { items.forEach(::add) }

    fun add(meta: Meta) {
        val group = if (meta.type == "movie") movies else shows
        meta.id.takeIf { it.isNotBlank() }?.let { group.ids.add(it) }
        meta.imdb?.let { group.imdb.add(it) }
        meta.tmdbId?.let { group.tmdb.add(it) }
        meta.serverRef?.takeIf(String::isNotBlank)?.let { group.serverRefs.add(it) }
        meta.serverSeriesRef?.takeIf(String::isNotBlank)?.let { group.serverRefs.add(it) }
        val names = group.names.getOrPut(meta.name) { Names() }
        if (meta.releaseInfo == null) names.anyYear = true
        else names.years.add(meta.releaseInfo.take(4))
    }

    operator fun contains(meta: Meta): Boolean {
        val group = if (meta.type == "movie") movies else shows
        if (meta.id.isNotBlank() && meta.id in group.ids) return true
        if (meta.imdb?.let { it in group.imdb } == true) return true
        if (meta.tmdbId?.let { it in group.tmdb } == true) return true
        if (meta.serverRef?.takeIf(String::isNotBlank)?.let { it in group.serverRefs } == true || meta.serverSeriesRef?.takeIf(String::isNotBlank)?.let { it in group.serverRefs } == true) return true
        val names = group.names[meta.name] ?: return false
        return meta.releaseInfo == null || names.anyYear || meta.releaseInfo.take(4) in names.years
    }
}

/** First wins, without introducing aliases from titles that were discarded. */
fun mergeTitlesIndexed(vararg lists: List<Meta>): List<Meta> = buildList {
    val seen = TitleIndex()
    lists.forEach { list -> list.forEach { meta ->
        if (meta !in seen) { add(meta); seen.add(meta) }
    } }
}
