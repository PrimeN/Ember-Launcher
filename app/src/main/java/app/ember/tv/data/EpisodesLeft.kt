package app.ember.tv.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** #425: a show's episodes per season (no specials) and the last one that has aired. */
data class ShowEpisodes(val seasons: Map<Int, Int>, val lastAired: Pair<Int, Int>?) {
    /** Aired episodes after S[season]E[episode]. */
    fun leftAfter(season: Int, episode: Int): Int? {
        val (lastSeason, lastEpisode) = lastAired ?: return null
        var left = 0
        for ((s, count) in seasons) {
            if (s < season || s > lastSeason) continue
            val from = if (s == season) episode + 1 else 1
            val to = if (s == lastSeason) minOf(count, lastEpisode) else count
            if (to >= from) left += to - from + 1
        }
        return left
    }
}

/**
 * #425: "8 episodes left" on Continue watching cards: aired episodes after the one in progress,
 * from TMDB (cached). Read during drawing, so cards update as counts arrive.
 */
object EpisodesLeft {
    var enabled by mutableStateOf(true)
    private val counts = mutableStateMapOf<String, Int>()
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun key(meta: Meta): String? {
        val s = meta.season ?: return null
        val e = meta.episode ?: return null
        if (meta.type == "movie" || s <= 0) return null
        val show = meta.tmdbId?.let { "t$it" } ?: meta.imdb?.let { "i$it" } ?: return null
        return "$show:$s:$e"
    }

    fun label(meta: Meta): String? {
        if (!enabled) return null
        val n = counts[key(meta) ?: return null] ?: return null
        return when (n) { 0 -> "Last episode"; 1 -> "1 episode left"; else -> "$n episodes left" }
    }

    /** Looks up counts for the shows in [items] that don't have one yet. */
    suspend fun load(items: List<Meta>) {
        if (!enabled || !app.ember.tv.tracker.Tmdb.available) return
        for (meta in items.take(30)) {
            val key = key(meta) ?: continue
            if (!asked.add(key)) continue
            val show = app.ember.tv.tracker.Tmdb.showEpisodes(meta.tmdbId, meta.imdb) ?: continue
            show.leftAfter(meta.season!!, meta.episode!!)?.let { counts[key] = it }
        }
    }
}
