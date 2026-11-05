package app.ember.tv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.encodeToString
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import okhttp3.Request
import java.io.File

/**
 * A collection is a Home row of folders. Each folder opens addon catalogs, TMDB lists/searches
 * or Trakt lists. Imports and exports retain the existing collection JSON fields, including
 * older catalogSources folders and unknown extension fields.
 */

@Serializable
data class MediaCollection(
    val id: String = "",
    val title: String = "",
    val backdropImageUrl: String? = null,
    /** Shown at the top of Home rather than after your catalogs. */
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean? = null,
    /** "TABBED_GRID" (tabs per source, then a grid), "ROWS" (a row per source) or "FOLLOW_LAYOUT" (rows here). */
    val viewMode: String = "TABBED_GRID",
    /** A first tab with every source together. */
    val showAllTab: Boolean = true,
    val folders: List<CollectionFolder> = emptyList(),
    @kotlinx.serialization.Transient val extensionFields: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class CollectionFolder(
    val id: String = "",
    val title: String = "",
    val coverImageUrl: String? = null,
    /** Focused video/GIF cover; respects [focusGifEnabled] and the global Animated collections choice. */
    val focusGifUrl: String? = null,
    val focusGifEnabled: Boolean? = null,
    val coverEmoji: String? = null,
    /** "POSTER", "LANDSCAPE" or "SQUARE" (also "poster", "wide", "square"). */
    val tileShape: String = "SQUARE",
    val hideTitle: Boolean = false,
    val sources: List<CollectionSource>? = null,
    /** Older exports: addon catalogs only. */
    val catalogSources: List<CollectionSource> = emptyList(),
    val heroBackdropUrl: String? = null,
    val titleLogoUrl: String? = null,
    /** Muted video/GIF hero preview; starts only when Animated collections is enabled. */
    val heroVideoUrl: String? = null,
    @kotlinx.serialization.Transient val extensionFields: Map<String, JsonElement> = emptyMap(),
) {
    val allSources: List<CollectionSource> get() = sources ?: catalogSources

    val shape: String
        get() = when (tileShape.lowercase()) {
            "poster" -> "poster"
            "landscape", "wide" -> "wide"
            else -> "square"
        }
}

@Serializable
data class CollectionSource(
    /** "addon" (default), "tmdb" or "trakt". */
    val provider: String = "addon",
    val addonId: String? = null,
    val type: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    /** TMDB: LIST, COLLECTION, COMPANY, NETWORK, DISCOVER, PERSON or DIRECTOR. */
    val tmdbSourceType: String? = null,
    val title: String? = null,
    val tmdbId: Int? = null,
    val traktListId: Long? = null,
    /** "MOVIE" or "TV" (TMDB), or "movie" / "tv". */
    val mediaType: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val filters: Map<String, JsonElement>? = null,
    /** Ember extensions retain configured catalog extras and complete Discover criteria. */
    val extraArgs: Map<String, String> = emptyMap(),
    val addonKey: String? = null,
    val exploreQuery: app.ember.tv.discover.ExploreQuery? = null,
    val ownerProfile: String? = null,
    val secretArgsRef: String? = null,
    @kotlinx.serialization.Transient val extensionFields: Map<String, JsonElement> = emptyMap(),
)

object Collections {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

    private fun file(context: Context) = File(context.filesDir, "collections.json")

    // Old saved packs may contain an empty collection; do not discard the rest on upgrade.
    fun load(context: Context): List<MediaCollection> =
        runCatching { decode(file(context).readText()) }.getOrDefault(emptyList())

    fun save(context: Context, list: List<MediaCollection>): Result<Unit> =
        runCatching {
            val safe = CollectionSecrets.seal(list) { key, value -> app.ember.tv.tracker.TokenStore(context).putEncrypted(key, value) }
            AtomicFile.write(file(context), encode(safe)).getOrThrow()
        }

    internal fun encode(list: List<MediaCollection>): String = CollectionCodec.encode(list)
    private fun decode(text: String): List<MediaCollection> = CollectionCodec.decode(text)

    data class Import(val collections: List<MediaCollection> = emptyList(), val error: String? = null)

    /** All-or-nothing validation: never silently remove an entry or select a duplicate ID. */
    fun inspect(text: String): Import {
        val list = runCatching { decode(text) }.getOrElse {
            return Import(error = "Could not read the collections JSON. Check its format; nothing was added.")
        }
        fun invalid(path: String, field: String) = Import(error = "$path: $field. Nothing was added.")
        if (list.isEmpty()) return invalid("Collections", "the pack is empty")
        val ids = HashSet<String>()
        list.forEachIndexed { index, collection ->
            val at = "Collection ${index + 1}"
            if (collection.id.isBlank()) return invalid(at, "id is required")
            if (!ids.add(collection.id)) return invalid(at, "duplicate collection id")
            if (collection.title.isBlank()) return invalid(at, "title is required")
            if (collection.viewMode.uppercase(Locale.ROOT) !in setOf("TABBED_GRID", "ROWS", "FOLLOW_LAYOUT"))
                return invalid(at, "unsupported viewMode")
            if (collection.folders.isEmpty()) return invalid(at, "at least one folder is required")
            val folders = HashSet<String>()
            collection.folders.forEachIndexed { folderIndex, folder ->
                val where = "$at, folder ${folderIndex + 1}"
                if (folder.id.isBlank()) return invalid(where, "id is required")
                if (!folders.add(folder.id)) return invalid(where, "duplicate folder id")
                if (folder.title.isBlank()) return invalid(where, "title is required")
                if (folder.tileShape.lowercase(Locale.ROOT) !in setOf("poster", "landscape", "wide", "square"))
                    return invalid(where, "unsupported tileShape")
                if (folder.allSources.isEmpty()) return invalid(where, "at least one source is required")
                folder.allSources.forEachIndexed { sourceIndex, source ->
                    val location = "$where, source ${sourceIndex + 1}"
                    when (source.provider.lowercase(Locale.ROOT)) {
                        "addon" -> if (source.addonId.isNullOrBlank() || source.catalogId.isNullOrBlank() || source.type.isNullOrBlank())
                            return invalid(location, "addonId, catalogId and type are required")
                        "tmdb" -> {
                            val kind = source.tmdbSourceType?.uppercase(Locale.ROOT)
                            if (kind !in setOf("LIST", "COLLECTION", "COMPANY", "NETWORK", "DISCOVER", "PERSON", "DIRECTOR"))
                                return invalid(location, "unsupported or missing tmdbSourceType")
                            if (kind != "DISCOVER" && (source.tmdbId ?: 0) <= 0) return invalid(location, "a positive tmdbId is required")
                            fun filter(name: String) = (source.filters?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                            filter("year")?.let { if (it.toIntOrNull()?.let { year -> year in 1870..java.time.LocalDate.now().year } != true) return invalid(location, "enter a valid release year") }
                            listOf("voteAverageGte", "voteAverageLte").forEach { name -> filter(name)?.let { if (it.toDoubleOrNull()?.let { n -> n.isFinite() && n in 0.0..10.0 } != true) return invalid(location, "ratings must be between 0 and 10") } }

                        }
                        "trakt" -> if ((source.traktListId ?: 0) <= 0) return invalid(location, "a positive traktListId is required")
                        "ember" -> if (source.ownerProfile.isNullOrBlank() || source.exploreQuery == null || source.exploreQuery.error() != null)
                            return invalid(location, "a valid Discover query and profile are required")
                        else -> return invalid(location, "unsupported provider")
                    }
                    if (source.provider.lowercase(Locale.ROOT) in setOf("tmdb", "trakt") &&
                        source.mediaType?.lowercase(Locale.ROOT)?.let { it !in setOf("movie", "tv") } == true)
                        return invalid(location, "mediaType must be MOVIE or TV")
                }
            }
        }
        return Import(collections = list)
    }

    /** Compatibility helper for callers that only need acceptance/rejection. */
    fun parse(text: String): List<MediaCollection>? = inspect(text).takeIf { it.error == null }?.collections

    suspend fun download(url: String): String = coroutineScope {
        val call = Net.client(10, 30).newCall(Request.Builder().url(url.trim()).build())
        withResponse(call) { response -> withContext(Dispatchers.IO) {
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body.boundedText(ResponseLimits.IMPORT)
        } }
    }

    // ---------------------------------------------------------------- loading a source

    /** How a source is named on its tab. */
    fun label(s: CollectionSource, defs: List<CatalogDef>): String = when (s.provider.lowercase()) {
        "ember" -> s.title ?: "Saved Discover search"
        "tmdb" -> s.title ?: "TMDB"
        "trakt" -> s.title ?: "Trakt list"
        else -> defs.firstOrNull { matches(it, s) }?.let { d -> d.name + (s.genre?.let { " · $it" } ?: "") }
            ?: s.title ?: s.catalogId.orEmpty()
    }

    fun matches(d: CatalogDef, s: CollectionSource): Boolean =
        d.addon.manifestId == s.addonId && d.id == s.catalogId && d.type == s.type &&
            (s.addonKey == null || s.addonKey == AddonAccess.key(d.addon.url))

    /**
     * Titles for one source. Addon sources need that addon installed (matched by its manifest id);
     * TMDB sources need a TMDB key; Trakt lists use the Trakt client id. Errors become a message.
     */
    fun nextSkip(s: CollectionSource, skip: Int, received: Int): Int = when (s.provider.lowercase(Locale.ROOT)) {
        "tmdb" -> (skip / 20 + 1) * 20
        "trakt" -> (skip / 100 + 1) * 100
        else -> skip + received
    }

    /** Names the catalog a source needs, so a folder says which one to enable in its add-on. */
    internal fun missingCatalog(s: CollectionSource): String {
        val name = (s.extensionFields["catalogName"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: s.title ?: s.catalogId
        val addon = (s.extensionFields["addonName"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: "its add-on"
        return if (name.isNullOrBlank()) "This collection needs an installed add-on catalog."
        else "\"$name\" is not one of $addon's catalogs. Turn it on in the add-on's settings, or remove it from this folder."
    }
    private val requests by lazy { CollectionRequests() }
    suspend fun page(s: CollectionSource, defs: List<CatalogDef>, client: AddonClient, skip: Int = 0, readSecret: (String) -> String? = { null }): Result<CollectionPage> = try {
        Result.success(when (s.provider.lowercase(Locale.ROOT)) {
            "tmdb", "trakt" -> requests.page(s, skip)
            else -> {
                val def = defs.firstOrNull { matches(it, s) }
                    ?: error(missingCatalog(s))
                val items = client.browse(def, genre = s.genre, skip = skip, extras = CollectionSecrets.resolve(s, readSecret))
                CollectionPage(items, nextSkip(s, skip, items.size), items.isEmpty() || !def.supportsSkip, CollectionPage.fingerprint(items.map { it.mediaKey() }))
            }
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}
