package app.ember.tv.streaming

import app.ember.tv.data.ApiKeys
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import java.util.Locale

/** TMDB/JustWatch offers retain the existing app-opening behavior and attribution. */
class TmdbWatchClient(
    private val key: () -> String = { ApiKeys.get("TMDB_API_KEY") },
    private val get: suspend (String, Map<String, String>) -> JsonElement = Http::privateGet,
    clock: () -> Long = System::currentTimeMillis,
) {
    val available get() = key().isNotBlank()
    private val cache = OfferCache(clock)
    private suspend fun request(path: String, credential: String, parameters: Map<String, String> = emptyMap()): JsonElement = withTimeout(10_000) {
        check(key() == credential) { "Streaming provider configuration changed." }
        val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org").addPathSegments("3/$path")
            .addQueryParameter("api_key", credential).apply { parameters.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        val data = get(url.toString(), mapOf("Cache-Control" to "no-store"))
        check(data is JsonObject && data.bool("success") != false) { "TMDB returned an invalid response." }
        data
    }
    suspend fun services(region: String): List<StreamingService> {
        val credential = key().takeIf { it.isNotBlank() } ?: return emptyList()
        val country = OfferRequests.country(region)
        val all = LinkedHashMap<Int, StreamingService>()
        for (type in listOf("movie", "tv")) {
            val results = request("watch/providers/$type", credential, mapOf("watch_region" to country)).arr("results")
                ?: error("TMDB returned invalid services.")
            results.forEach { p ->
                val id = p.int("provider_id") ?: return@forEach
                val name = p.str("provider_name") ?: return@forEach
                all.putIfAbsent(id, StreamingService(id, name, "sub", p.str("logo_path")?.let { "https://image.tmdb.org/t/p/w92$it" }))
            }
        }
        currentCoroutineContext().ensureActive()
        check(key() == credential) { "Streaming provider configuration changed." }
        return all.values.toList()
    }
    suspend fun offers(movie: Boolean, tmdbId: Long?, imdbId: String?, region: String): List<Offer> {
        val credential = key().takeIf { it.isNotBlank() } ?: return emptyList()
        val type = if (movie) "movie" else "tv"
        val tmdb = tmdbId?.takeIf { it > 0 }
        val imdb = OfferRequests.imdb(imdbId)
        val title = tmdb?.let { "tmdb:$it" } ?: imdb?.let { "imdb:$it" } ?: return emptyList()
        val country = OfferRequests.country(region)
        return cache.get(OfferRequests.identity(credential, country, "$type:$title"), { key() == credential }) {
            val id = tmdb ?: run {
                val matches = request("find/$imdb", credential, mapOf("external_source" to "imdb_id"))
                    .arr(if (movie) "movie_results" else "tv_results") ?: error("TMDB returned invalid title matches.")
                matches.firstOrNull().long("id")?.takeIf { it > 0 }
            } ?: return@get emptyList()
            val results = request("$type/$id/watch/providers", credential).obj("results")
                ?: error("TMDB returned invalid offers.")
            val regional = results[country] ?: return@get emptyList()
            check(regional is JsonObject) { "TMDB returned invalid regional offers." }
            val offers = ArrayList<Offer>()
            for ((group, kind) in listOf("flatrate" to "sub", "free" to "free", "ads" to "free", "rent" to "rent", "buy" to "buy")) {
                if (regional[group] != null) check(regional[group] is JsonArray) { "TMDB returned invalid offer entries." }
                regional.arr(group).orEmpty().forEach { p ->
                    offers += Offer(p.int("provider_id") ?: return@forEach, p.str("provider_name") ?: return@forEach,
                        kind, null, null, regional.str("link"), null)
                }
            }
            offers.groupBy { it.serviceId }.values.map { it.minBy { offer -> offer.rank } }
                .sortedWith(compareBy<Offer> { it.rank }.thenBy { it.name.lowercase(Locale.ROOT) })
        }
    }
}

object TmdbWatch {
    private val client = TmdbWatchClient()
    val available get() = client.available
    suspend fun services(region: String) = client.services(region)
    suspend fun offers(movie: Boolean, tmdbId: Long?, imdbId: String?, region: String) = client.offers(movie, tmdbId, imdbId, region)
}
