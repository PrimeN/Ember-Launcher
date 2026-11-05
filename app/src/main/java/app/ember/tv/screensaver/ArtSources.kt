package app.ember.tv.screensaver

import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.list
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import java.net.URLEncoder

/** Public image collections for the screensaver. */
object ArtSources {
    /**
     * Cleveland Museum of Art open access: public-domain (CC0) paintings with images.
     * A random offset gives a different set each day.
     */
    suspend fun museum(count: Int = 40): List<Slide> {
        val skip = (0..2000).random()
        val r = Http.get(
            "https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&type=Painting&limit=$count&skip=$skip"
        )
        return r.arr("data").list().mapNotNull { a ->
            val image = a.obj("images").obj("web").str("url") ?: return@mapNotNull null
            val artist = a.arr("creators").list().firstOrNull().str("description")
            Slide(
                image = image,
                title = a.str("title") ?: "Untitled",
                credit = listOfNotNull(artist, "Cleveland Museum of Art").joinToString(" · "),
            )
        }
    }

    private val spaceTopics = listOf("nebula", "galaxy", "aurora", "earth from orbit", "saturn", "jupiter", "hubble", "milky way")

    /**
     * NASA Image and Video Library. Search results link a small thumbnail; the larger
     * "~large" copy is tried first, with the thumbnail as a fallback.
     */
    suspend fun space(count: Int = 40): List<Slide> {
        val topic = URLEncoder.encode(spaceTopics.random(), "UTF-8")
        val r = Http.get("https://images-api.nasa.gov/search?q=$topic&media_type=image&page_size=100")
        return r.obj("collection").arr("items").list().mapNotNull { item ->
            val thumb = item.arr("links").list().firstOrNull().str("href") ?: return@mapNotNull null
            val data = item.arr("data").list().firstOrNull()
            Slide(
                image = thumb.replace("~thumb.", "~large."),
                title = data.str("title") ?: "NASA",
                credit = "NASA",
                fallback = thumb,
            )
        }.shuffled().take(count)
    }
}
