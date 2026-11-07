package app.ember.tv

import app.ember.tv.data.LightHomeOptions
import app.ember.tv.data.Meta
import app.ember.tv.light.LightCarousel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #61: Light's chosen carousel catalog plays right after Continue watching. */
class LightCarouselCatalogTest {
    private fun meta(id: String) = Meta(id = id, type = "movie", name = id, background = "https://img.example/$id.jpg")

    @Test fun chosenCatalogFollowsContinueWatchingAndLeadsRecommendations() {
        val slides = LightCarousel.compose(own = listOf(meta("tt1")), external = emptyList(),
            recommendations = listOf(meta("tt9")), featured = listOf(meta("tt5"), meta("tt6")))
        assertEquals(listOf("tt1", "tt5", "tt6", "tt9"), slides.map { it.meta!!.id })
        assertEquals(true, slides.first().continuing)
    }

    @Test fun olderSavedOptionsMixAutomatically() {
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString(LightHomeOptions.serializer(), """{"watchNext":true,"idleSeconds":30}""")
        assertNull(saved.heroCatalog)
    }
}
