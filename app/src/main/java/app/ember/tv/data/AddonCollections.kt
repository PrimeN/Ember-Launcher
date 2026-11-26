package app.ember.tv.data

import app.ember.tv.tracker.arr
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #459: collections an addon serves itself. AIOMetadata (3.2 and later) lists each collection made
 * in its builder as a catalog of type "collection"; each entry is a folder whose collection block
 * (AIOStreams' collection extension) lists the catalogs it opens as `sources` and its subfolders
 * among its `items`. A catalog entry may carry an empty block; the folder's meta has the rest. Ember shows them as collections that follow the
 * addon: on Home as folder tiles, in Discover, and in the Home widgets list.
 */
object AddonCollections {
    const val TYPE = "collection"
    private const val AIOM = "aiom.collection."
    private const val MAX_DEPTH = 4

    /**
     * The collection's id. AIOMetadata names the catalog after the collection, so a copy imported
     * from its Nuvio export has the same id, and Ember shows that one instead.
     */
    fun collectionId(def: CatalogDef): String =
        if (def.id.startsWith(AIOM) && def.id.length > AIOM.length) def.id.removePrefix(AIOM) else "linked-" + AddonAccess.key(def.key)

    private fun block(entry: JsonElement?): JsonObject? = entry.obj("collection")

    /** The catalogs a folder opens: `sources`, or `catalogs` as some addons write it. */
    private fun sources(b: JsonObject): List<JsonElement> = b.arr("sources").list().ifEmpty { b.arr("catalogs").list() }

    /** Whether an entry already says what its folder opens, so it needn't be opened first. */
    fun complete(entry: JsonElement): Boolean = block(entry)?.let { b ->
        sources(b).isNotEmpty() || b.arr("items").list().any { block(it) != null }
    } == true

    private fun manifestUrl(url: String) = url.trim().trimEnd('/').let { if (it.endsWith("manifest.json")) it else "$it/manifest.json" }

    /**
     * One catalog a folder opens. Without an addon id it is the collection addon's own catalog; the
     * id may also be another addon's manifest link, which is then matched by that link.
     */
    fun source(value: JsonElement, def: CatalogDef): CollectionSource? {
        val catalogId = value.str("catalogId")?.takeIf { it.isNotBlank() } ?: return null
        val type = value.str("type")?.takeIf { it.isNotBlank() } ?: return null
        val given = value.str("addonId")?.trim()?.takeIf { it.isNotBlank() }
        val link = given?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let(::manifestUrl)
        val own = given == null || given == def.addon.manifestId || link == manifestUrl(def.addon.url)
        val addonId = if (own) def.addon.manifestId else link ?: given!!
        val name = value.str("name") ?: value.str("title")
        return CollectionSource(provider = "addon", addonId = addonId, type = type, catalogId = catalogId,
            genre = value.str("genre")?.takeIf { it.isNotBlank() }, title = name,
            addonKey = if (own) AddonAccess.key(def.addon.url) else link?.let(AddonAccess::key),
            extensionFields = name?.let { mapOf("catalogName" to JsonPrimitive(it)) }.orEmpty())
    }

    private fun shape(entry: JsonElement): String = when (entry.str("posterShape")?.lowercase()) {
        "landscape" -> "LANDSCAPE"
        "square" -> "SQUARE"
        else -> "POSTER"
    }

    /** An entry's folder (when it opens catalogs) and its subfolders after it, flattened as AIOMetadata does for Nuvio. */
    fun folders(entry: JsonElement, def: CatalogDef, depth: Int = 0): List<CollectionFolder> {
        if (depth > MAX_DEPTH) return emptyList()
        val b = block(entry) ?: return emptyList()
        val id = entry.str("id")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val name = entry.str("name")?.takeIf { it.isNotBlank() } ?: id
        val sources = sources(b).mapNotNull { source(it, def) }
            .distinctBy { listOf(it.addonId, it.type, it.catalogId, it.genre) }
        val tile = shape(entry)
        val cover = (if (tile == "LANDSCAPE") entry.str("landscapePoster") else null) ?: entry.str("poster") ?: entry.str("landscapePoster") ?: entry.str("background")
        val own = if (sources.isEmpty()) emptyList()
            else listOf(CollectionFolder(id = id, title = name, coverImageUrl = cover, tileShape = tile, sources = sources,
                heroBackdropUrl = entry.str("background"), titleLogoUrl = entry.str("logo")))
        return own + b.arr("items").list().filter { block(it) != null }.flatMap { folders(it, def, depth + 1) }
    }

    private fun file(context: android.content.Context) = java.io.File(context.filesDir, "linked-collections.json")

    /** The last collections addons sent, so Home shows them before the addons answer again. */
    fun load(context: android.content.Context): List<MediaCollection> =
        runCatching { file(context).takeIf { it.isFile }?.readText()?.let { Collections.inspect(it).collections } }.getOrNull().orEmpty()

    fun save(context: android.content.Context, values: List<MediaCollection>) {
        runCatching {
            val target = file(context); val temp = java.io.File(target.path + ".tmp")
            temp.writeText(Collections.encode(values)); if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }
    }

    /** The collection one catalog serves, from its entries (each opened first by [details] when it says too little). */
    suspend fun collection(def: CatalogDef, entries: List<JsonElement>, details: suspend (JsonElement) -> JsonElement?): MediaCollection {
        val folders = entries.filter { block(it) != null || it.str("type").equals(TYPE, ignoreCase = true) }.flatMap { entry ->
            val full = if (complete(entry)) entry else runCatching { details(entry) }.getOrNull()?.takeIf { block(it) != null } ?: entry
            folders(full, def)
        }.distinctBy { it.id }
        return MediaCollection(id = collectionId(def), title = def.name.ifBlank { "Collection" }, folders = folders)
    }
}
