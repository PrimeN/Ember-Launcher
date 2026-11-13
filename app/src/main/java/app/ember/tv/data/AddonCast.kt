package app.ember.tv.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #265: cast an addon sends with faces. Metadata addons put it in different places ("app_extras",
 * "credits_cast", "cast" as objects); each person needs a name, and a photo and character are kept
 * when given. When the addon has none, TMDB's photos are matched by name.
 */
object AddonCast {
    private fun JsonElement?.text(vararg keys: String): String? = (this as? JsonObject)?.let { o ->
        keys.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }
    }
    private fun JsonElement?.list(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

    private fun photo(p: JsonElement): String? = p.text("photo", "image", "profile", "profile_path", "profilePath", "thumbnail")?.let {
        if (it.startsWith("/")) "https://image.tmdb.org/t/p/w185$it" else it.takeIf { u -> u.startsWith("https://") || u.startsWith("http://") }
    }

    private fun people(list: List<JsonElement>, crew: Boolean, role: String?): List<Person> = list.mapNotNull { p ->
        val name = p.text("name", "title") ?: return@mapNotNull null
        Person(name, if (crew) role else p.text("character", "role") ?: role, photo(p), isCrew = crew,
            tmdbId = p.text("id", "tmdb_id", "tmdbId")?.toLongOrNull())
    }

    /** The addon's cast with faces, directors first; empty when it sends only plain names. */
    fun people(meta: JsonElement): List<Person> {
        val extras = (meta as? JsonObject)?.get("app_extras")
        val cast = listOf(extras.list("cast"), meta.list("credits_cast"), meta.list("cast").filter { it is JsonObject }).firstOrNull { it.isNotEmpty() }.orEmpty()
        val directors = listOf(extras.list("directors"), meta.list("credits_crew").filter { it.text("job", "department")?.contains("Director", true) == true })
            .firstOrNull { it.isNotEmpty() }.orEmpty()
        val found = people(directors, crew = true, role = "Director") + people(cast, crew = false, role = null).take(15)
        return if (found.any { !it.photo.isNullOrBlank() || !it.role.isNullOrBlank() }) found.distinctBy { it.name to it.isCrew } else emptyList()
    }

    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** The addon's people in its order, with TMDB's photo, character and id where the addon has none. */
    fun withFaces(addon: List<Person>, tmdb: List<Person>): List<Person> {
        if (tmdb.isEmpty()) return addon
        val byName = tmdb.groupBy { key(it.name) }
        return addon.map { p ->
            val t = byName[key(p.name)]?.let { same -> same.firstOrNull { it.isCrew == p.isCrew } ?: same.first() } ?: return@map p
            p.copy(photo = p.photo?.takeIf { it.isNotBlank() } ?: t.photo, role = p.role?.takeIf { it.isNotBlank() } ?: t.role, tmdbId = p.tmdbId ?: t.tmdbId)
        }
    }
}
