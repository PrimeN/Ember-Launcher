package app.ember.tv

import app.ember.tv.data.AddonInfo
import app.ember.tv.data.CatalogDef
import app.ember.tv.data.CatalogIconSignature
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #50: signing catalog artwork must not repeat each addon's manifest once per catalog. */
class CatalogIconSignatureTest {
    private val manifestCatalogs = List(300) { i ->
        buildJsonObject { put("id", JsonPrimitive("c$i")); put("name", JsonPrimitive("Catalog $i " + "x".repeat(400))) }
    }
    private val addon = AddonInfo("https://big.example/manifest.json", "https://big.example", "Big", listOf("movie"), null, emptyList(), manifestCatalogs)
    private fun defs() = List(300) { i -> CatalogDef(addon, "movie", "c$i", "Catalog $i", emptyList(), false, false, false, false) }

    @Test fun staysSmallForAddonsWithManyCatalogs() {
        val signature = CatalogIconSignature.of(defs(), emptyMap(), emptyMap(), emptySet(), emptyMap())
        // The old toString() was over 300 × 300 × 400 characters (36 MB) for this addon.
        assertTrue("signature is ${signature.length} chars", signature.length < 50_000)
    }

    @Test fun changesWhenWhatTheIconShowsChanges() {
        val base = CatalogIconSignature.of(defs(), emptyMap(), emptyMap(), emptySet(), emptyMap())
        assertNotEquals(base, CatalogIconSignature.of(defs(), mapOf("k" to "Renamed"), emptyMap(), emptySet(), emptyMap()))
        assertNotEquals(base, CatalogIconSignature.of(defs().reversed(), emptyMap(), emptyMap(), emptySet(), emptyMap()))
        assertNotEquals(base, CatalogIconSignature.of(defs(), emptyMap(), mapOf("k" to false), emptySet(), emptyMap()))
        assertNotEquals(base, CatalogIconSignature.of(defs(), emptyMap(), emptyMap(), setOf("k"), emptyMap()))
        assertNotEquals(base, CatalogIconSignature.of(defs(), emptyMap(), emptyMap(), emptySet(), mapOf("k" to true)))
        assertTrue(base == CatalogIconSignature.of(defs(), emptyMap(), emptyMap(), emptySet(), emptyMap()))
    }
}
