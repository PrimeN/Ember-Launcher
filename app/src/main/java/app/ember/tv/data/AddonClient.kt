package app.ember.tv.data

import app.ember.tv.tracker.arr
import app.ember.tv.tracker.bool
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

/**
 * Reads Stremio-style addons. An addon is a web service that publishes {base}/manifest.json
 * and serves {base}/{resource}/{type}/{id}.json for catalog, meta, stream and subtitles.
 */
class AddonClient(private val http: OkHttpClient = Net.client(10, 30)) {
    private val json = Json { ignoreUnknownKeys = true }
    private val manifests = AsyncCache<String, AddonInfo>(CacheKind.METADATA)

    fun baseOf(url: String): String {
        val parsed = url.trim().replace("stremio://", "https://").toHttpUrlOrNull() ?: error("Enter a valid HTTP or HTTPS add-on URL.")
        return parsed.newBuilder().encodedPath(parsed.encodedPath.removeSuffix("/manifest.json").trimEnd('/').ifBlank { "/" }).build().toString()
    }
    private fun endpoint(base: String, path: String): String {
        val url = base.toHttpUrl()
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/" + path).build().toString()
    }

    /**
     * Reads an addon's manifest, again after [MANIFEST_MAX_AGE_MILLIS] so a reconfigured addon's
     * catalogs appear without restarting Ember. If that re-read fails the previous copy is kept.
     * The user's name for it, if any, replaces the manifest's.
     */
    suspend fun manifest(addonUrl: String): AddonInfo {
        AddonAccess.requireAvailable(addonUrl)
        val previous = manifests.peek(addonUrl)
        val at = manifestTimes[addonUrl]
        if (previous != null && at != null && now() - at !in 0 until MANIFEST_MAX_AGE_MILLIS) {
            val version = manifests.invalidate(addonUrl)
            return named(try { loadManifest(addonUrl) }
                catch (e: kotlinx.coroutines.CancellationException) { manifests.restore(addonUrl, previous, version); throw e }
                catch (e: Exception) {
                    val fallback = manifests.restore(addonUrl, previous, version) ?: throw e
                    manifestTimes[addonUrl] = now()
                    fallback
                })
        }
        return named(loadManifest(addonUrl))
    }

    private val manifestTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun now() = android.os.SystemClock.elapsedRealtime()
    private suspend fun loadManifest(addonUrl: String): AddonInfo = manifests.load(addonUrl) {
        val base = baseOf(addonUrl)
        AddonInfo.parse(addonUrl, base, getJson(endpoint(base, "manifest.json"), addonUrl)).also { manifestTimes[addonUrl] = now() }
    }

    fun cachedManifest(addonUrl: String): AddonInfo? = manifests.peek(addonUrl)?.let { named(it) }

    private fun named(i: AddonInfo): AddonInfo =
        renames[AddonAccess.key(i.url)]?.takeIf { it.isNotBlank() && it != i.name }?.let { i.copy(name = it, originalName = i.name) } ?: i

    companion object {
        const val MANIFEST_MAX_AGE_MILLIS = 30 * 60_000L
        /** Addon link to the name the user gave it. Shared by every client (Home, player auto-play). */
        @Volatile var renames: Map<String, String> = emptyMap()
        private val calls = mutableMapOf<String, MutableSet<okhttp3.Call>>()
        fun cancel(url: String) = synchronized(calls) { calls.remove(AddonAccess.key(url))?.forEach { it.cancel() }; Unit }
    }

    fun forget(addonUrl: String) { manifestTimes.remove(addonUrl); manifests.invalidate(addonUrl) }

    // ---- catalogs ----
    // ---- browsing and search ----
    /** Every catalog an addon lists, with its genre, paging and search options. */
    fun catalogsOf(addon: AddonInfo): List<CatalogDef> = addon.catalogs.mapNotNull { c ->
        val type = c.str("type") ?: return@mapNotNull null
        val id = c.str("id") ?: return@mapNotNull null
        val extras = c.arr("extra").list()
        // Newer manifests use "extra" objects; older ones use extraSupported / extraRequired name lists.
        val required = extras.filter { it.bool("isRequired") == true }.mapNotNull { it.str("name") }.toSet() +
            c.arr("extraRequired").strings()
        val supported = extras.mapNotNull { it.str("name") }.toSet() + c.arr("extraSupported").strings()
        val genres = (extras.firstOrNull { it.str("name") == "genre" }.arr("options").strings() + c.arr("genres").strings()).distinct()
        CatalogDef(
            addon = addon,
            type = type,
            id = id,
            name = c.str("name") ?: id,
            genres = genres,
            genreRequired = "genre" in required,
            searchable = "search" in supported,
            searchOnly = "search" in required,
            otherRequired = (required - setOf("genre", "search", "skip")).isNotEmpty(),
            extras = extras.mapNotNull { option -> option.str("name")?.takeIf { it != "skip" && (it != "search" || "search" in required) }?.let { name ->
                CatalogExtraDef(name, name in required, option.arr("options").strings(), option.str("default") ?: option.str("defaultValue"))
            } } + (required - extras.mapNotNull { it.str("name") }.toSet() - setOf("skip")).map { CatalogExtraDef(it, true, if (it == "genre") genres else emptyList()) },
            supportsSkip = "skip" in supported,
        )
    }

    /** One page of a catalog, optionally filtered by genre or a search term. */
    suspend fun browse(def: CatalogDef, genre: String? = null, skip: Int = 0, search: String? = null,
        extras: Map<String, String> = emptyMap(), refresh: Boolean = false): List<Meta> {
        AddonAccess.requireAvailable(def.addon.url)
        val requested = def.requestExtras + extras + listOfNotNull(genre?.takeIf { it.isNotBlank() }?.let { "genre" to it }, search?.let { "search" to it }).toMap()
        // An optional value the catalog does not offer (a collection's genre "None" on a catalog without
        // it) is left out, so the catalog loads unfiltered; a required one is sent as given.
        val args = requested.filter { (name, value) ->
            val extra = def.extras.firstOrNull { it.name == name }
            extra == null || extra.required || extra.options.isEmpty() || value in extra.options
        }
        def.missingRequired(args)?.let { error(it) }
        val parts = args.filterValues { it.isNotBlank() }.map { "${enc(it.key)}=${enc(it.value)}" }.toMutableList()
        if (skip > 0 && def.supportsSkip) parts += "skip=$skip"
        val extra = if (parts.isEmpty()) "" else "/" + parts.joinToString("&")
        return getJson(endpoint(def.addon.base, "catalog/${def.type}/${def.id}$extra.json"), def.addon.url, CacheKind.CATALOGS, refresh).arr("metas").list().map {value->
            json.decodeFromJsonElement<Meta>(value).copy(ageRating=addonAgeRating(value))
        }
    }

    /** Like JavaScript's encodeURIComponent, which Stremio addons expect. */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun kotlinx.serialization.json.JsonArray?.strings(): List<String> =
        this?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    // ---- meta ----
    /** Full details for one title, including the episode list for series. */
    suspend fun meta(addon: AddonInfo, type: String, id: String): Pair<JsonElement?, List<Episode>> {
        AddonAccess.requireAvailable(addon.url)
        val meta = getJson(endpoint(addon.base, "meta/$type/$id.json"), addon.url, CacheKind.METADATA).obj("meta")
        val episodes = meta.arr("videos").list().mapNotNull { v ->
            val season = v.int("season") ?: return@mapNotNull null
            val number = v.int("episode") ?: v.int("number") ?: return@mapNotNull null
            Episode(
                id = v.str("id") ?: "$id:$season:$number",
                season = season,
                episode = number,
                title = v.str("title") ?: v.str("name") ?: "Episode $number",
                released = v.str("released")?.take(10),
                thumbnail = v.str("thumbnail"),
                overview = (v.str("overview") ?: v.str("description"))?.takeIf { it.isNotBlank() },
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
        return meta to episodes
    }

    // ---- streams and subtitles ----
    suspend fun streams(addon: AddonInfo, type: String, id: String): List<StreamOption> = sources(addon, type, id).first

    /** Playable sources, and links that open in another app (externalUrl, ytId, #73). */
    suspend fun sources(addon: AddonInfo, type: String, id: String): Pair<List<StreamOption>, List<app.ember.tv.streaming.Offer>> {
        AddonAccess.requireAvailable(addon.url)
        val all = getJson(endpoint(addon.base, "stream/$type/$id.json"), addon.url).arr("streams").list()
        return all.mapNotNull { StreamParser.parse(addon.name, it) } to all.mapNotNull { StreamParser.link(addon.name, it) }
    }

    suspend fun subtitles(addon: AddonInfo, type: String, id: String): List<SubtitleOption> {
        AddonAccess.requireAvailable(addon.url)
        return getJson(endpoint(addon.base, "subtitles/$type/$id.json"), addon.url).arr("subtitles").list()
            .mapNotNull { StreamParser.subtitle(addon.name, it) }
    }

    // ---- http ----
    private suspend fun getJson(url: String, owner: String, kind: CacheKind? = null, refresh: Boolean = false): JsonElement {
        AddonAccess.requireAvailable(owner)
        val text = if (kind == null) getText(url, owner) else CacheManagement.json(kind, url, refresh) { getText(url, owner) }
        AddonAccess.requireAvailable(owner)
        return withContext(Dispatchers.IO) { json.parseToJsonElement(text) }
    }

    /** Cancellable: when an addon runs past the time limit, its request is dropped at once. */
    private suspend fun getText(url: String, owner: String): String {
        val request = Request.Builder().url(url).header("Cache-Control","no-cache, no-store").build()
        val call = http.newBuilder().cache(null).build().newCall(request)
        val key = AddonAccess.key(owner)
        synchronized(calls) { AddonAccess.requireAvailable(owner); calls.getOrPut(key) { mutableSetOf() }.add(call) }
        try {
        val r = with(Net) { call.await() }
        return try {
            withContext(Dispatchers.IO) {
                r.use {
                    if (!it.isSuccessful) throw AddonHttpException(it.code)
                    val text = it.body.boundedText(ResponseLimits.ADDON)
                    AddonAccess.requireAvailable(owner)
                    text
                }
            }
        } finally {
            r.close()
        }
        } finally { synchronized(calls) { calls[key]?.let { it.remove(call); if (it.isEmpty()) calls.remove(key) } } }
    }
}
