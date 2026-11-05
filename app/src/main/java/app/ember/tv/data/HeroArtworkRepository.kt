package app.ember.tv.data

import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.list
import app.ember.tv.tracker.long
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest

/** Bounded public-artwork cache. Neither API credentials nor account data are written here. */
internal class HeroArtworkRepository(
    private val directory: File?,
    // These request URLs contain API keys. Cache only the selected public artwork below,
    // never the credential-bearing API request/response in the shared HTTP disk cache.
    private val fetch: suspend (String) -> JsonElement = { Http.get(it, mapOf("Cache-Control" to "no-store")) },
    private val now: () -> Long = System::currentTimeMillis,
    private val limit: Int = 96,
) {
    @Serializable private data class Record(val saved: Long, val expires: Long, val art: HeroArtwork)
    private class Pending { val mutex = Mutex(); var users = 0 }
    private val pending = mutableMapOf<String, Pending>()
    private val memory = LinkedHashMap<String, Record>(16, .75f, true)
    private val permits = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = true }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(id: HeroIdentity, locale: String, tmdb: String, fanart: String) =
        hash("v2:${id.movie}:${id.tmdb}:${id.imdb}:${HeroArtworkSelection.language(locale)}:${hash("$tmdb\u0000$fanart")}")
    private fun valid(record: Record): Boolean = now() >= record.saved && now() < record.expires &&
        record.expires > record.saved && record.expires - record.saved <= 7 * 86_400_000L

    suspend fun resolve(meta: Meta, locale: String, tmdb: String, fanart: String,
        artworkMode: String = HeroArtworkMode.FANART): HeroArtwork = withContext(Dispatchers.IO) {
        val fanartKey = fanart.takeIf { HeroArtworkMode.normalize(artworkMode) == HeroArtworkMode.FANART }.orEmpty()
        val identity = HeroIdentity.of(meta) ?: return@withContext HeroArtwork()
        if (tmdb.isBlank() && fanartKey.isBlank()) return@withContext HeroArtwork()
        val key = key(identity, locale, tmdb, fanartKey)
        val guard = synchronized(pending) { pending.getOrPut(key) { Pending() }.also { it.users++ } }
        try { guard.mutex.withLock {
            val cached = synchronized(memory) { memory[key]?.takeIf(::valid) }
                ?: directory?.let { dir -> runCatching {
                    val file = File(dir, "$key.json")
                    if (file.length() > 32_768) null else json.decodeFromString<Record>(file.readText()).takeIf(::valid)
                }.getOrNull() }
            if (cached != null) { remember(key, cached); return@withLock cached.art }
            val record = permits.withPermit { load(identity, locale, tmdb, fanartKey) }
            currentCoroutineContext().ensureActive()
            remember(key, record)
            directory?.let { dir ->
                AtomicFile.write(File(dir, "$key.json"), json.encodeToString(record))
                runCatching { dir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                    ?.drop(limit.coerceAtLeast(1))?.forEach { it.delete() } }
            }
            record.art
        } } finally { synchronized(pending) { guard.users--; if (guard.users == 0) pending.remove(key) } }
    }

    private fun remember(key: String, value: Record) = synchronized(memory) {
        memory[key] = value
        while (memory.size > limit.coerceAtLeast(1)) memory.remove(memory.keys.first())
    }

    private suspend fun request(url: String): JsonElement? = try { withTimeoutOrNull(2500) { fetch(url) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null } // Failures must not expose provider URLs/credentials in UI or logs.

    private fun tmdbUrl(path: String, credential: String, language: String? = null): String {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder().addQueryParameter("api_key", credential)
        if (language != null) url.addQueryParameter("append_to_response", "images,external_ids")
            .addQueryParameter("include_image_language", listOf(language, "en", "null").distinct().joinToString(","))
        return url.build().toString()
    }

    private suspend fun load(id: HeroIdentity, locale: String, tmdb: String, fanart: String): Record = coroutineScope {
        val language = HeroArtworkSelection.language(locale)
        val details = async {
            if (tmdb.isBlank()) null else {
                val numeric = id.tmdb ?: id.imdb?.let { imdb ->
                    val url = tmdbUrl("find/$imdb", tmdb).toHttpUrl().newBuilder().addQueryParameter("external_source", "imdb_id").build().toString()
                    request(url)?.arr(if (id.movie) "movie_results" else "tv_results").list().firstOrNull()?.long("id")
                }
                numeric?.takeIf { it > 0 }?.let { request(tmdbUrl("${if (id.movie) "movie" else "tv"}/$it", tmdb, language)) }
            }
        }
        val fanartData = async {
            if (fanart.isBlank()) null else {
                val external = if (id.movie) id.imdb ?: details.await()?.obj("external_ids").str("imdb_id")
                    else details.await()?.obj("external_ids").long("tvdb_id")?.takeIf { it > 0 }?.toString()
                external?.takeIf { if (id.movie) Regex("^tt[0-9]+$").matches(it) else it.all(Char::isDigit) }?.let {
                    request("https://webservice.fanart.tv/v3/${if (id.movie) "movies" else "tv"}/$it".toHttpUrl().newBuilder()
                        .addQueryParameter("api_key", fanart).build().toString())
                }
            }
        }
        val tmdbData = details.await()
        val fanartJson = fanartData.await()
        val a = tmdbData?.let { HeroArtworkSelection.tmdb(it, locale) } ?: HeroArtwork()
        val b = fanartJson?.let { HeroArtworkSelection.fanart(it, id.movie, locale) } ?: HeroArtwork()
        val art = HeroArtwork((b.backgrounds + a.backgrounds).distinct().take(3), b.logo ?: a.logo, (b.posters + a.posters).distinct().take(3))
        val complete = (tmdb.isBlank() || tmdbData != null) && (fanart.isBlank() || fanartJson != null)
        val time = now()
        val ttl = if (!complete) 60_000L else if (art.empty) 3_600_000L else 7 * 86_400_000L
        Record(time, time + ttl, art)
    }
}
