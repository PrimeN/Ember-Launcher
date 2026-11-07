package app.ember.tv.data

import android.content.SharedPreferences
import kotlinx.serialization.json.*
import java.util.Base64

/** Catalog placement/permissions refer to one opaque installed instance, never its configured URL. */
object CatalogIdentity {
    fun prefix(url: String) = "addon:" + AddonAccess.key(url) + "|"
    fun key(url: String, type: String, id: String) = prefix(url) + type + "|" + id
    fun belongs(key: String?, url: String) = key != null && migrate(key).startsWith(prefix(url))
    fun migrate(value: String): String {
        if (value.startsWith("catalog:")) return runCatching {
            val original = String(Base64.getUrlDecoder().decode(value.removePrefix("catalog:")), Charsets.UTF_8)
            "catalog:" + Base64.getUrlEncoder().withoutPadding().encodeToString(migrate(original).toByteArray(Charsets.UTF_8))
        }.getOrDefault(value)
        if (!value.startsWith("http://") && !value.startsWith("https://") && !value.startsWith("stremio://")) return value
        val last = value.lastIndexOf('|'); val previous = value.lastIndexOf('|', last - 1)
        return if (previous > 0 && last > previous) key(value.substring(0, previous), value.substring(previous + 1, last), value.substring(last + 1)) else value
    }
    private fun tree(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.map { migrate(it.key) to tree(it.value) }.toMap())
        is JsonArray -> JsonArray(value.map(::tree))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(migrate(value.content)) else value
    }
    // By identity: a store's equals/hashCode are not part of its contract (test fixtures are proxies).
    private val migrated = HashMap<Int, java.lang.ref.WeakReference<SharedPreferences>>()
    /**
     * [migratePreferences] once per preferences store per process (#31). It ran in every Prefs
     * constructor, copying and re-parsing the catalog, profile and Home layout settings each time,
     * including once per private server image. Android returns the same store object for a name,
     * so this is once per process; a restore calls [forget] so restored values are migrated.
     */
    fun migrateOnce(sp: SharedPreferences) {
        val id = System.identityHashCode(sp)
        synchronized(migrated) {
            if (migrated[id]?.get() === sp) return
            migrated[id] = java.lang.ref.WeakReference(sp)
        }
        try { migratePreferences(sp) } catch (error: RuntimeException) { forget(sp); throw error }
    }
    fun forget(sp: SharedPreferences) {
        val id = System.identityHashCode(sp)
        synchronized(migrated) { if (migrated[id]?.get() === sp) migrated.remove(id) }
    }

    /** Idempotent migration also covers profiles and base64 Home section identities in backups. */
    fun migratePreferences(sp: SharedPreferences) {
        val names = setOf("catalogOrder", "catalogHome", "catalogNames", "catalogLive", "catalogDiscoverOff", "profiles", "homeLayout", "homeListStyle", "addonNames", "seededTestAddons")
        val edit = sp.edit(); var changed = false
        sp.all.orEmpty().filterKeys { it in names }.forEach { (name, value) ->
            when (value) {
                is String -> {
                    val next = if (name == "addonNames") runCatching {
                        JsonObject(Json.parseToJsonElement(value).jsonObject.map { (k, v) ->
                            (if (k.startsWith("http") || k.startsWith("stremio://")) AddonAccess.key(k) else k) to v
                        }.toMap()).toString()
                    }.getOrDefault(value) else if (name == "homeLayout") value.split(',').joinToString(",") { entry ->
                        val at = entry.lastIndexOf(':'); if (at > 0) migrate(entry.substring(0, at)) + entry.substring(at) else entry
                    } else runCatching { tree(Json.parseToJsonElement(value)).toString() }.getOrElse {
                        value.lines().joinToString("\n", transform = ::migrate)
                    }
                    if (next != value) { edit.putString(name, next); changed = true }
                }
                is Set<*> -> {
                    val old = value.filterIsInstance<String>().toSet()
                    val next = old.map { if (name == "seededTestAddons" && it.startsWith("http")) AddonAccess.key(it) else migrate(it) }.toSet()
                    if (old != next) { edit.putStringSet(name, next); changed = true }
                }
            }
        }
        // Synchronous: the migrated identities must be on disk before anything reads them.
        if (changed && !edit.commit()) android.util.Log.w("Ember", "Catalog identity migration not saved; it runs again next start.")
    }
}
