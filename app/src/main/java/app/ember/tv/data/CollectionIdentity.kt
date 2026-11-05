package app.ember.tv.data

import java.util.Locale

private val collectionEpisodeId = Regex("^(tt[0-9]+):([0-9]+):([0-9]+)$", RegexOption.IGNORE_CASE)
private val collectionImdbId = Regex("tt[0-9]+")

/** Known provider aliases only: title text cannot merge remakes or unrelated server entries. */
internal fun distinctCollectionTitles(items: List<Meta>): List<Meta> {
    val parent = IntArray(items.size) { it }
    fun root(index: Int): Int {
        var i = index
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
        return i
    }
    val aliases = HashMap<String, Int>()
    items.forEachIndexed { index, item ->
        val kind = when (val type = item.type.lowercase(Locale.ROOT)) { "anime", "tv" -> "series"; else -> type }
        val episodeId = collectionEpisodeId.matchEntire(item.id)
        val season = item.season ?: episodeId?.groupValues?.get(2)?.toIntOrNull()
        val episode = item.episode ?: episodeId?.groupValues?.get(3)?.toIntOrNull()
        val prefix = listOf(kind, season?.toString().orEmpty(), episode?.toString().orEmpty()).joinToString("") { "${it.length}:$it" }
        val imdb = item.imdb?.lowercase(Locale.ROOT)?.takeIf { collectionImdbId.matches(it) }
        val tmdb = item.tmdbId?.takeIf { it > 0 } ?: item.id.removePrefix("tmdb:").takeIf { item.id.startsWith("tmdb:") }?.toLongOrNull()?.takeIf { it > 0 }
        val keys = listOfNotNull(item.id.takeIf { it.isNotBlank() }?.let { "raw:$it" }, imdb?.let { "imdb:$it" }, tmdb?.let { "tmdb:$it" })
        keys.forEach { key ->
            val old = aliases.putIfAbsent(prefix + key, index)
            if (old != null) { val a = root(old); val b = root(index); parent[maxOf(a, b)] = minOf(a, b) }
        }
    }
    val result = LinkedHashMap<Int, Meta>()
    items.forEachIndexed { index, item ->
        val at = root(index)
        val existing = result[at]
        result[at] = existing?.copy(imdbId = existing.imdbId ?: item.imdb?.takeIf { existing.imdb == null }, tmdbId = existing.tmdbId ?: item.tmdbId) ?: item
    }
    return result.values.toList()
}
