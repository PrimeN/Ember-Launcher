package app.ember.tv.screensaver

import androidx.core.content.edit

import android.content.Context
import app.ember.tv.data.Meta
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class Slide(
    val image: String,
    val title: String,
    val credit: String = "",
    /** Tried when [image] fails to load, e.g. NASA's smaller copy. */
    val fallback: String? = null,
)

/**
 * What the screensaver shows. The launcher saves Home backdrops and the art and space sets here;
 * your own pictures are read live by the screensaver.
 */
class ScreensaverFeed(context: Context) {
    private val sp = context.getSharedPreferences("screensaver", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- which sources are on ----
    var sources: Set<String>
        get() = sp.getStringSet("sources", setOf(HOME))!!.toSet()
        set(v) = sp.edit { putStringSet("sources", v) }

    /** Folder for your own pictures; null means all folders. */
    var photoFolder: String?
        get() = sp.getString("photoFolder", null)
        set(v) = sp.edit { putString("photoFolder", v) }

    // ---- saved slides ----
    fun update(items: List<Meta>,clearEmpty:Boolean=false) {
        // Server images carry sign-in tokens in the link, so they are not saved here.
        val slides = items.mapNotNull { m -> m.background?.let { Slide(it, m.name) } }
            .filterNot { it.image.contains("X-Plex-Token=") || it.image.contains("api_key=") }
            .distinctBy { it.image }
            .take(40)
        if (slides.isNotEmpty() || clearEmpty) save("slides", slides)
    }

    fun homeSlides() = load("slides")
    fun artSlides() = load("art")
    fun spaceSlides() = load("space")
    fun saveArt(slides: List<Slide>) = save("art", slides)
    fun saveSpace(slides: List<Slide>) = save("space", slides)

    /** When the art and space sets were last fetched; they refresh once a day. */
    var collectionsFetchedAt: Long
        get() = sp.getLong("collectionsAt", 0L)
        set(v) = sp.edit { putLong("collectionsAt", v) }

    var weather: String?
        get() = sp.getString("weather", null)
        set(v) = sp.edit { putString("weather", v) }

    private fun save(key: String, slides: List<Slide>) = sp.edit { putString(key, json.encodeToString(slides)) }

    private fun load(key: String): List<Slide> =
        runCatching { json.decodeFromString<List<Slide>>(sp.getString(key, "[]")!!) }.getOrDefault(emptyList())

    companion object {
        const val HOME = "home"
        const val ART = "art"
        const val SPACE = "space"
        const val PHOTOS = "photos"

        /** Sources shown in Settings, in order. */
        val ALL = listOf(
            HOME to "Home backdrops",
            ART to "Paintings (Cleveland Museum of Art)",
            SPACE to "Space (NASA)",
            PHOTOS to "My pictures",
        )
    }
}
