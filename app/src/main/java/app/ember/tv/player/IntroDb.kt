package app.ember.tv.player

import app.ember.tv.data.Net
import app.ember.tv.data.boundedText
import app.ember.tv.data.withResponse
import app.ember.tv.mediaserver.Marker
import app.ember.tv.tracker.Kind
import app.ember.tv.tracker.TrackedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

internal data class IntroDbQuery(val imdb: String, val season: Int?, val episode: Int?) {
    companion object {
        fun from(item: TrackedItem): IntroDbQuery? {
            val imdb = item.imdbId?.takeIf { Regex("tt[0-9]{5,12}").matches(it) } ?: return null
            if (item.kind == Kind.MOVIE) return IntroDbQuery(imdb, null, null)
            return if (item.season != null && item.season >= 0 && item.episode != null && item.episode > 0)
                IntroDbQuery(imdb, item.season, item.episode) else null
        }
    }
    fun url(): String = "https://api.introdb.app/segments".toHttpUrl().newBuilder()
        .addQueryParameter("imdb_id", imdb).apply {
            season?.let { addQueryParameter("season", it.toString()) }
            episode?.let { addQueryParameter("episode", it.toString()) }
        }.build().toString()
}

/** Read-only public lookup. No account, API key, filename, URL or viewing position is sent. */
internal class IntroDbClient(
    private val fetch: suspend (String) -> String? = { url -> coroutineScope {
        val client = Net.client(4, 6).newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        withResponse(client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())) { response ->
            withContext(Dispatchers.IO) {
                if (response.code == 404) null
                else { check(response.isSuccessful); response.body.boundedText(128L * 1024) }
            }
        }
    } },
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Cached(val at: Long, val markers: List<Marker>)
    private val cache = object : LinkedHashMap<IntroDbQuery, Cached>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IntroDbQuery, Cached>?) = size > 64
    }

    suspend fun markers(query: IntroDbQuery): List<Marker> {
        synchronized(cache) { cache[query]?.let { entry ->
            val age = now() - entry.at
            if (age in 0 until if (entry.markers.isEmpty()) 60_000L else 3_600_000L) return entry.markers
        } }
        val result = try { fetch(query.url())?.let { body -> withContext(Dispatchers.Default) { IntroDbSegments.parse(body) } }.orEmpty() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
        currentCoroutineContext().ensureActive()
        synchronized(cache) { cache[query] = Cached(now(), result) }
        return result
    }
}

internal object IntroDbSegments {
    fun parse(text: String): List<Marker> {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        return listOf("intro" to "intro", "recap" to "recap", "outro" to "credits").mapNotNull { (field, kind) ->
            val segment = root[field] as? JsonObject ?: return@mapNotNull null
            val start = time(segment["start_sec"]) ?: return@mapNotNull null
            val end = time(segment["end_sec"]) ?: return@mapNotNull null
            if (end <= start || end - start > if (kind == "credits") 1_800_000 else 600_000) null
            else Marker(kind, start, end)
        }.sortedBy { it.startMs }.fold(emptyList()) { accepted, marker ->
            if (accepted.lastOrNull()?.endMs?.let { it > marker.startMs } == true) accepted else accepted + marker
        }
    }

    private fun time(value: JsonElement?): Long? {
        val raw = (value as? JsonPrimitive)?.contentOrNull ?: return null
        val parts = raw.split(':')
        if (parts.size !in 1..3) return null
        val numbers = parts.map { it.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n >= 0 } ?: return null }
        if (parts.size > 1 && numbers.drop(1).any { it >= 60 }) return null
        val seconds = numbers.fold(0.0) { total, n -> total * 60 + n }
        return (seconds * 1000).takeIf { it <= 86_400_000 }?.toLong()
    }

    /** Community timings can describe another cut. Unknown/out-of-range runtimes never trigger skips. */
    fun forRuntime(markers: List<Marker>, duration: Long): List<Marker> = if (duration <= 0) emptyList() else markers.filter {
        it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= duration &&
            if (it.kind == "credits") it.startMs >= duration / 2
            else it.startMs < duration / 2 && it.endMs - it.startMs <= duration / 3
    }
}

internal data class LoadedMarkers(val markers: List<Marker>, val introDb: Boolean)

internal suspend fun resolveMarkers(server: suspend () -> List<Marker>, database: suspend () -> List<Marker>): LoadedMarkers {
    val local = try { server().filter { it.startMs >= 0 && it.endMs > it.startMs } }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { emptyList() }
    return if (local.isNotEmpty()) LoadedMarkers(local, false) else LoadedMarkers(database(), true)
}
