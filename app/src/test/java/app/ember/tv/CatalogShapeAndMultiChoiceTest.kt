package app.ember.tv

import app.ember.tv.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Catalog posterShape and multi-choice filters (optionsLimit) from addon manifests (#72). */
class CatalogShapeAndMultiChoiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun catalog(extra: String): CatalogDef {
        val url = "https://example.com/manifest.json"
        val addon = AddonInfo.parse(url, url.removeSuffix("/manifest.json"), Json.parseToJsonElement(
            """{"id":"test","name":"Addon","version":"1","catalogs":[{"type":"movie","id":"top","name":"Top","extra":[$extra]}]}""",
        ))
        return AddonClient().catalogsOf(addon).single()
    }

    @Test fun optionsLimitIsReadAndClamped() {
        val def = catalog("""{"name":"genre","options":["Action","Comedy","Drama"],"optionsLimit":2}""")
        assertEquals(2, def.extras.single { it.name == "genre" }.limit)
        assertEquals(1, catalog("""{"name":"genre","options":["Action"]}""").extras.single().limit)
        assertEquals(20, catalog("""{"name":"genre","options":["Action"],"optionsLimit":500}""").extras.single().limit)
        assertEquals(1, catalog("""{"name":"genre","options":["Action"],"optionsLimit":0}""").extras.single().limit)
    }

    @Test fun severalValuesAreCheckedAgainstTheOptionsAndTheLimit() {
        val def = catalog("""{"name":"genre","options":["Action","Comedy","Drama"],"optionsLimit":2}""")
        val sep = EXTRA_VALUES_SEPARATOR
        assertEquals(listOf("Action", "Comedy"), extraValues("Action${sep}Comedy"))
        assertNull(def.extraError(mapOf("genre" to "Action${sep}Comedy")))
        assertNotNull(def.extraError(mapOf("genre" to "Action${sep}Comedy${sep}Drama")))
        assertNotNull(def.extraError(mapOf("genre" to "Action${sep}Western")))
        assertNull(def.extraError(mapOf("genre" to "Drama")))
    }

    @Test fun catalogsThatNeedAGenreCanStillFeedTheHero() {
        // AIOMetadata style: genre required, "None" (all genres) first. Never a Home row, but the hero can use it (#101).
        val genre = catalog("""{"name":"genre","isRequired":true,"options":["None","Action"]}""")
        assertFalse(genre.homeable)
        assertTrue(genre.featurable)
        assertTrue(catalog("""{"name":"genre","options":["Action"]}""").featurable)
        assertFalse("a search-only catalog needs typing", catalog("""{"name":"search","isRequired":true}""").featurable)
    }

    @Test fun posterShapeSetsTheCardAndKeepsTheAddonsOwnArt() {
        val square = json.decodeFromString<Meta>("""{"id":"tt1","type":"movie","name":"Radio","poster":"https://x/p.png","posterShape":"square"}""")
        assertEquals("square", square.posterShape)
        assertEquals(1f, square.cardRatio)
        assertFalse(square.isPortraitArt)
        assertNull("rating posters are portrait only", Posters.url(square))
        val wide = json.decodeFromString<Meta>("""{"id":"tt2","type":"movie","name":"Wide","posterShape":"landscape"}""")
        assertTrue(wide.cardWidthScale > 1f && wide.cardRatio < 1f)
        val plain = json.decodeFromString<Meta>("""{"id":"tt3","type":"movie","name":"Plain"}""")
        assertEquals(1.5f, plain.cardRatio)
        assertEquals(1f, plain.cardWidthScale)
        assertTrue(plain.isPortraitArt)
    }
}
